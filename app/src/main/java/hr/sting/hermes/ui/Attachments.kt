package hr.sting.hermes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import hr.sting.hermes.R
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
private val THUMBNAIL_SIZE = 42.dp

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
        throw IllegalStateException(context.getString(R.string.attachment_too_large))
    }
    val mime = resolver.getType(uri) ?: mimeFromName(name)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException(context.getString(R.string.attachment_unreadable))
    if (bytes.isEmpty()) throw IllegalStateException(context.getString(R.string.attachment_empty))
    if (bytes.size > MAX_INPUT_ATTACHMENT_BYTES) {
        throw IllegalStateException(context.getString(R.string.attachment_too_large))
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
            key(attachment.id) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (attachment.kind == AttachmentKind.IMAGE) {
                            AttachmentThumbnail(attachment)
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
                                contentDescription = stringResource(R.string.remove_attachment),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(attachment: PendingAttachment) {
    val targetPx = LocalDensity.current.run { THUMBNAIL_SIZE.roundToPx() }
    // No manual recycle(): Compose may still draw the previous frame's bitmap after the state
    // changes, and recycling it there crashes with "trying to use a recycled bitmap". The
    // downsample below is what keeps memory small; the rest is left to the GC.
    val bitmap by produceState<Bitmap?>(initialValue = null, attachment.id, targetPx) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeThumbnail(attachment.bytes, targetPx) }.getOrNull()
        }
    }
    val thumbnail = bitmap
    if (thumbnail != null) {
        ComposeImage(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = attachment.name,
            modifier = Modifier
                .size(THUMBNAIL_SIZE)
                .clip(RoundedCornerShape(9.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(THUMBNAIL_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Image, contentDescription = null)
        }
    }
}

/**
 * Decodes [bytes] downsampled until the short side is within [targetPx, 2 * targetPx) pixels —
 * the square crop only needs the short side, so this keeps the thumbnail sharp while never
 * materialising the full-size bitmap in memory.
 */
private fun decodeThumbnail(bytes: ByteArray, targetPx: Int): Bitmap? {
    if (bytes.isEmpty() || targetPx <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val shortSide = min(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (shortSide / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun formatSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
