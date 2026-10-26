package hr.sting.hermes.device

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import hr.sting.hermes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file Hermes asked the phone to fetch (the `file.send` server request). */
data class IncomingFile(
    val serverPath: String,
    val downloadPath: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val caption: String = "",
)

data class SavedFile(
    val name: String,
    val uri: Uri,
    val size: Long,
    val location: String,
)

/**
 * Downloads a file the agent produced on the server over the already
 * authenticated REST client and stores it where the user can find it:
 * `Downloads/Hermes/` on Android 10+, the app's external Download folder on
 * older releases (no storage permission needed there either).
 */
object FileInbox {
    private const val FOLDER = "Hermes"
    private const val DISPLAY_FOLDER = "Download/$FOLDER"
    private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024

    suspend fun download(
        context: Context,
        client: OkHttpClient,
        url: String,
        incoming: IncomingFile,
    ): SavedFile = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(httpError(context, response.code))
            val body = response.body
                ?: throw IOException(context.getString(R.string.server_returned_no_file))
            val name = safeName(context, incoming.name)
            val mime = incoming.mimeType.ifBlank { "application/octet-stream" }
            body.byteStream().use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToDownloads(context, name, mime, input)
                } else {
                    saveToAppFolder(context, name, mime, input)
                }
            }
        }
    }

    private fun saveToDownloads(
        context: Context,
        name: String,
        mime: String,
        input: InputStream,
    ): SavedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: return saveToAppFolder(context, name, mime, input)
        var written = 0L
        try {
            resolver.openOutputStream(uri)?.use { output ->
                written = copy(context, input, output)
            } ?: throw IOException(context.getString(R.string.cannot_save_file))
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        return SavedFile(
            name = name,
            uri = uri,
            size = written,
            location = "$DISPLAY_FOLDER/$name",
        )
    }

    private fun saveToAppFolder(
        context: Context,
        name: String,
        mime: String,
        input: InputStream,
    ): SavedFile {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            FOLDER,
        ).apply { mkdirs() }
        val target = uniqueFile(dir, name)
        val written = try {
            target.outputStream().use { copy(context, input, it) }
        } catch (error: Exception) {
            runCatching { target.delete() }
            throw error
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target,
        )
        return SavedFile(
            name = target.name,
            uri = uri,
            size = written,
            location = "$DISPLAY_FOLDER/${target.name}",
        )
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            val suffix = if (ext.isBlank()) "" else ".$ext"
            candidate = File(dir, "$base ($index)$suffix")
            index += 1
        }
        return candidate
    }

    private fun copy(context: Context, input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_DOWNLOAD_BYTES) {
                throw IOException(context.getString(R.string.file_too_large_200))
            }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }

    /** Never let a server-supplied name escape the target folder. */
    private fun safeName(context: Context, raw: String): String {
        val cleaned = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("\u0000", "")
            .trim()
            .trimStart('.')
        return cleaned.ifBlank { context.getString(R.string.file) }.take(120)
    }

    private fun httpError(context: Context, code: Int): String = when (code) {
        403 -> context.getString(R.string.access_denied)
        404 -> context.getString(R.string.file_no_longer_exists)
        413 -> context.getString(R.string.file_too_large_transfer)
        else -> context.getString(R.string.download_failed_http, code)
    }
}
