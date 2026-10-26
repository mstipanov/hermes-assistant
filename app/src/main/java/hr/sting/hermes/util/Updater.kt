package hr.sting.hermes.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import hr.sting.hermes.data.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks the public AndroidAppDistributor endpoint and installs the newer APK.
 * The dashboard API token is deliberately not used here: the distributor's
 * /version and /apk paths are public.
 */
object Updater {
    private const val VERSION_HOST = "hermes-assistant.sting.hr"
    private const val VERSION_URL = "https://$VERSION_HOST/version"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL("$VERSION_URL?t=${System.currentTimeMillis()}").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "HermesAssistant/Android")
        }
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IOException("Server za ažuriranja je vratio HTTP $status")
            }
            val json = JSONObject(body)
            val latestCode = json.optInt("versionCode", 0)
            val latestName = json.optString("versionName").trim()
            val apkUrl = json.optString("apkUrl").trim()
            if (latestCode <= currentVersionCode || apkUrl.isBlank()) return@withContext null
            val parsed = Uri.parse(apkUrl)
            if (parsed.scheme != "https" || parsed.host != VERSION_HOST) {
                throw IOException("Server nudi APK s neočekivane adrese ($apkUrl).")
            }
            val sha256 = json.optString("sha256").trim()
                .takeIf { SHA256.matches(it) }
                ?.lowercase()
            val changelog = buildList {
                val array = json.optJSONArray("changelog") ?: return@buildList
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            UpdateInfo(
                versionName = latestName.ifBlank { latestCode.toString() },
                versionCode = latestCode,
                apkUrl = apkUrl,
                changelog = changelog,
                sha256 = sha256,
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float?) -> Unit,
    ) {
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw IOException("DownloadManager nije dostupan")
        val fileName = "Hermes-${info.versionName}-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Hermes ${info.versionName}")
            setDescription("Preuzimanje ažuriranja…")
            setMimeType(APK_MIME)
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val id = manager.enqueue(request)
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            cursor.use {
                if (!it.moveToFirst()) {
                    delay(400)
                    return@use
                }
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                )
                val total = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
                onProgress(if (total > 0) downloaded.toFloat() / total else null)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = manager.getUriForDownloadedFile(id)
                            ?: throw IOException("Preuzeti APK nije dostupan")
                        if (!info.sha256.isNullOrBlank()) {
                            try {
                                verifySha256(context, uri, info.sha256)
                            } catch (error: Exception) {
                                runCatching { manager.remove(id) }
                                throw error
                            }
                        } else {
                            AppLog.add("update: server nije poslao SHA-256, preskačem provjeru")
                        }
                        withContext(Dispatchers.Main.immediate) {
                            launchInstaller(context, uri)
                        }
                        return
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = it.getInt(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                        )
                        throw IOException("Preuzimanje nije uspjelo (razlog $reason)")
                    }
                }
            }
            delay(400)
        }
        throw IOException("Preuzimanje traje predugo")
    }

    private fun verifySha256(context: Context, uri: Uri, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Preuzeti APK nije dostupan")
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IOException("Kontrolna suma APK-a se ne poklapa — instalacija je zaustavljena.")
        }
    }

    private fun launchInstaller(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
