package hr.sting.hermes.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import hr.sting.hermes.BuildConfig
import hr.sting.hermes.R
import hr.sting.hermes.data.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks the public AndroidAppDistributor endpoint and installs the newer APK.
 * Stable builds use hermes-assistant.sting.hr, beta builds hermes-beta.sting.hr
 * (BuildConfig.UPDATE_BASE_URL), so a beta never crosses over to stable.
 * The dashboard API token is deliberately not used here: the distributor's
 * /version and /apk paths are public.
 */
object Updater {
    private val updateBase = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
    private val versionUrl = "$updateBase/version"
    private val updateHost = Uri.parse(updateBase).host.orEmpty()
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    suspend fun check(context: Context, currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL("$versionUrl?t=${System.currentTimeMillis()}").openConnection()
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
                throw IOException(context.getString(R.string.update_server_http, status))
            }
            val json = JSONObject(body)
            val latestCode = json.optInt("versionCode", 0)
            val latestName = json.optString("versionName").trim()
            val apkUrl = json.optString("apkUrl").trim()
            if (latestCode <= currentVersionCode || apkUrl.isBlank()) return@withContext null
            val parsed = Uri.parse(apkUrl)
            if (parsed.scheme != "https" || parsed.host != updateHost) {
                throw IOException(context.getString(R.string.unexpected_apk_url, apkUrl))
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
            ?: throw IOException(context.getString(R.string.download_manager_unavailable))
        val fileName = "Hermes-${info.versionName}-${System.currentTimeMillis()}.apk"
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle(context.getString(R.string.update_download_title, info.versionName))
            setDescription(context.getString(R.string.update_download_description))
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
                            ?: throw IOException(
                                context.getString(R.string.downloaded_apk_unavailable),
                            )
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
                        // The checksum is optional (the distributor does not publish one), so the
                        // real gate is the signing certificate: a build signed with a different key
                        // can never be installed over this app, whatever the server claims.
                        try {
                            verifySigner(context, apkFile)
                        } catch (error: Exception) {
                            runCatching { manager.remove(id) }
                            runCatching { apkFile.delete() }
                            throw error
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
                        throw IOException(
                            context.getString(R.string.download_failed_reason, reason),
                        )
                    }
                }
            }
            delay(400)
        }
        throw IOException(context.getString(R.string.download_timeout))
    }

    private fun verifySha256(context: Context, uri: Uri, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException(context.getString(R.string.downloaded_apk_unavailable))
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
            throw IOException(context.getString(R.string.checksum_mismatch))
        }
    }

    /**
     * A downloaded APK may only replace this app if it is the same package signed with the same
     * key. The distributor publishes no checksum, so this is the gate that actually stops a
     * swapped or re-signed build from being handed to the installer.
     */
    private fun verifySigner(context: Context, apk: File) {
        if (!apk.isFile || apk.length() == 0L) {
            throw IOException(context.getString(R.string.downloaded_apk_unavailable))
        }
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException(context.getString(R.string.apk_signature_mismatch))
        val installed = pm.getPackageInfo(context.packageName, flags)
        val expected = signerDigests(installed)
        val actual = signerDigests(archive)
        if (archive.packageName != context.packageName ||
            expected.isEmpty() ||
            actual.isEmpty() ||
            expected != actual
        ) {
            throw IOException(context.getString(R.string.apk_signature_mismatch))
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty()
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            .toSet()
    }

    private fun launchInstaller(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
