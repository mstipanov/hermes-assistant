package hr.sting.hermes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import hr.sting.hermes.data.AttachmentKind
import hr.sting.hermes.data.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val MAX_INPUT_ATTACHMENT_BYTES = 8 * 1024 * 1024
private const val IMAGE_MAX_DIMENSION = 2048

fun cameraOutputUri(context: Context): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File.createTempFile("hermes-photo-", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

suspend fun readAttachment(
    context: Context,
    uri: Uri,
    fallbackName: String,
): PendingAttachment? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = fallbackName
    var declaredSize = -1L
    runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { name = it }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
    }
    if (declaredSize > MAX_INPUT_ATTACHMENT_BYTES) {
        throw IllegalStateException("Datoteka je prevelika (max 8 MB).")
    }
    val mime = resolver.getType(uri) ?: mimeFromName(name)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Datoteku nije moguće pročitati.")
    if (bytes.isEmpty()) throw IllegalStateException("Datoteka je prazna.")
    if (bytes.size > MAX_INPUT_ATTACHMENT_BYTES) {
        throw IllegalStateException("Datoteka je prevelika (max 8 MB).")
    }
    when {
        mime.startsWith("image/") -> processImage(bytes, name, mime)
        mime == "application/pdf" -> PendingAttachment(
            name = name,
            mimeType = mime,
            bytes = bytes,
            kind = AttachmentKind.PDF,
        )
        else -> PendingAttachment(
            name = name,
            mimeType = mime,
            bytes = bytes,
            kind = AttachmentKind.FILE,
        )
    }
}

private fun processImage(bytes: ByteArray, name: String, mime: String): PendingAttachment {
    if (bytes.size <= 1_500_000) {
        return PendingAttachment(name = name, mimeType = mime, bytes = bytes, kind = AttachmentKind.IMAGE)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return PendingAttachment(name = name, mimeType = mime, bytes = bytes, kind = AttachmentKind.IMAGE)
    }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > IMAGE_MAX_DIMENSION) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return PendingAttachment(name = name, mimeType = mime, bytes = bytes, kind = AttachmentKind.IMAGE)

    val scale = min(1f, IMAGE_MAX_DIMENSION.toFloat() / max(decoded.width, decoded.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        decoded
    }
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    val jpeg = output.toByteArray()
    val newName = name.substringBeforeLast('.', name) + ".jpg"
    return PendingAttachment(name = newName, mimeType = "image/jpeg", bytes = jpeg, kind = AttachmentKind.IMAGE)
}

private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic", "heif" -> "image/heic"
    "txt" -> "text/plain"
    "json" -> "application/json"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

@Composable
fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (attachment.kind == AttachmentKind.IMAGE) {
                        val bitmap = remember(attachment.id) {
                            runCatching {
                                BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size)
                            }.getOrNull()
                        }
                        if (bitmap != null) {
                            ComposeImage(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = attachment.name,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(9.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(Icons.Filled.Image, contentDescription = null)
                        }
                    } else {
                        Icon(
                            if (attachment.kind == AttachmentKind.PDF) {
                                Icons.Filled.PictureAsPdf
                            } else {
                                Icons.Filled.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .width(130.dp)
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            attachment.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            formatSize(attachment.bytes.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(attachment.id) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Ukloni prilog",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
