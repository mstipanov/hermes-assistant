package hr.sting.hermes.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ApiError(val code: Int, message: String, val body: String = "") : Exception(message)

class MemoryCookieJar : CookieJar {
    private val cookies = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = cookies.firstOrNull()?.domain ?: url.host
        val target = this.cookies.getOrPut(host) { ConcurrentHashMap() }
        cookies.forEach { target[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies[url.host]?.values
            ?.filter { it.expiresAt > now && it.matches(url) }
            ?: emptyList()
    }

    fun clear() = cookies.clear()
}

/**
 * HTTP side of the Hermes dashboard API: Access service token headers, the
 * dashboard password session (cookies), file/audio uploads and WS tickets.
 */
class HermesRest(val settings: AppSettings) {
    private val base = settings.serverUrl.trim().trimEnd('/')
    private val baseUrl = base.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Neispravna adresa servera: $base")
    val cookieJar = MemoryCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            // Credentials belong to this Hermes server only. Never attach them to
            // a foreign host, even if a server response points us there.
            val sameOrigin = request.url.scheme == baseUrl.scheme &&
                request.url.host == baseUrl.host &&
                request.url.port == baseUrl.port
            val builder = request.newBuilder()
                .header("User-Agent", "HermesAssistant/0.1 (Android)")
                .header("Accept", "application/json")
            if (sameOrigin) {
                val accessId = settings.accessClientId.trim()
                val accessSecret = settings.accessClientSecret.trim()
                val token = settings.apiToken.trim()
                if (accessId.isNotBlank()) {
                    builder.header("CF-Access-Client-Id", accessId)
                }
                if (accessSecret.isNotBlank()) {
                    builder.header("CF-Access-Client-Secret", accessSecret)
                }
                if (token.isNotBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
            }
            chain.proceed(builder.build())
        }
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * A server-supplied file path (``/api/fs/download?path=…``) turned into an
     * absolute URL on this server, so the phone fetches the agent's file through
     * the same authenticated client as everything else.
     *
     * Returns null when the path resolves to another origin: an absolute URL
     * from the server must never make the app send its credentials elsewhere.
     */
    fun fileDownloadUrl(downloadPath: String): String? {
        val raw = downloadPath.trim()
        if (raw.isEmpty()) return null
        val candidate = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            base + if (raw.startsWith("/")) raw else "/$raw"
        }
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.scheme != baseUrl.scheme || url.host != baseUrl.host || url.port != baseUrl.port) {
            return null
        }
        return url.toString()
    }

    fun webSocketUrl(ticket: String): String = wsUrl("/api/ws", ticket)

    fun audioWebSocketUrl(ticket: String): String = wsUrl("/api/audio/speak-stream", ticket)

    fun wsUrl(path: String, ticket: String): String {
        val ws = base
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val encoded = URLEncoder.encode(ticket, "UTF-8")
        val separator = if (path.contains('?')) "&" else "?"
        return "$ws$path${separator}ticket=$encoded"
    }

    suspend fun me(): JsonObject = withContext(Dispatchers.IO) {
        getJson("/api/auth/me")
    }

    suspend fun login(): Unit = withContext(Dispatchers.IO) {
        val body = jsonObject(
            "provider" to "basic",
            "username" to settings.username.trim(),
            "password" to settings.password,
        )
        postJson("/auth/password-login", body)
        Unit
    }

    /**
     * Verify the session; if it lapsed, log in again with the stored password.
     */
    suspend fun ensureAuthenticated() {
        val probe = runCatching { me() }
        if (probe.isSuccess) return
        val err = probe.exceptionOrNull()
        if (err is ApiError && err.code == 401) {
            if (settings.apiToken.isNotBlank()) {
                throw ApiError(401, "API token je istekao ili nije valjan. Generiraj novi na Macu.")
            }
            if (settings.password.isBlank()) {
                throw ApiError(401, "Sesija je istekla. Upiši lozinku ponovno.")
            }
            login()
            me()
            return
        }
        throw err ?: IllegalStateException("Prijava nije uspjela")
    }

    suspend fun wsTicket(): String = withContext(Dispatchers.IO) {
        val result = postJson("/api/auth/ws-ticket", jsonObject())
        result.str("ticket").ifBlank { throw ApiError(0, "Server nije vratio WS ticket") }
    }

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val dataUrl = "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP)
        val result = postJson(
            "/api/audio/transcribe",
            jsonObject("data_url" to dataUrl, "mime_type" to "audio/wav"),
        )
        result.str("transcript")
    }

    suspend fun speak(text: String): ByteArray = withContext(Dispatchers.IO) {
        val result = postJson("/api/audio/speak", jsonObject("text" to text))
        val dataUrl = result.str("data_url")
        val encoded = dataUrl.substringAfter(',', "")
        if (encoded.isBlank()) throw ApiError(0, "Server nije vratio audio")
        Base64.decode(encoded, Base64.NO_WRAP)
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        runCatching { postJson("/auth/logout", jsonObject()) }
        cookieJar.clear()
    }

    private fun getJson(path: String): JsonObject = execute(
        Request.Builder().url("$base$path").get().build(),
    )

    private fun postJson(path: String, body: JsonObject): JsonObject = execute(
        Request.Builder()
            .url("$base$path")
            .post(body.toString().toRequestBody(jsonMedia))
            .build(),
    )

    private fun execute(request: Request): JsonObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiError(response.code, friendlyError(response.code, text), text)
            }
            if (text.isBlank()) return JsonObject(emptyMap())
            return runCatching { HermesJson.parseToJsonElement(text).let { it as JsonObject } }
                .getOrElse { throw ApiError(response.code, "Neispravan odgovor servera") }
        }
    }

    private fun friendlyError(code: Int, body: String): String {
        val detail = runCatching {
            HermesJson.parseToJsonElement(body).let { element ->
                element as? JsonObject
            }?.let { obj ->
                obj.strOrNull("detail") ?: obj.strOrNull("error")
            }
        }.getOrNull()
        return when {
            !detail.isNullOrBlank() -> detail
            code == 401 -> "Prijava nije uspjela (401)"
            code == 403 -> "Cloudflare Access je odbio zahtjev (403). Provjeri service token."
            code == 429 -> "Previše pokušaja prijave. Pričekaj minutu."
            else -> "HTTP $code"
        }
    }
}
