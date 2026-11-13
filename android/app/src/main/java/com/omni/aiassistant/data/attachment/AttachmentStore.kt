package com.omni.aiassistant.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.security.Hashing
import com.omni.aiassistant.core.util.Ids
import com.omni.aiassistant.domain.model.PreparedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class AttachmentStore(private val context: Context) {
    private val root = File(context.filesDir, "attachments").apply { mkdirs() }

    suspend fun importImage(uri: Uri, quality: Int, maxDimension: Int = 1600, maxBytes: Int = 5 * 1024 * 1024): PreparedAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw AppError.Validation("Unable to open selected image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AppError.Validation("Unsupported or corrupted image")
        val sample = calculateSample(bounds.outWidth, bounds.outHeight, maxDimension * 2)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw AppError.Validation("Unable to decode selected image")
        val orientation = resolver.openInputStream(uri)?.use { input ->
            runCatching { ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        bitmap = rotate(bitmap, orientation)
        bitmap = scale(bitmap, maxDimension)

        var q = quality.coerceIn(40, 95)
        var bytes: ByteArray
        do {
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, buffer)
            bytes = buffer.toByteArray()
            q -= 8
        } while (bytes.size > maxBytes && q >= 40)
        if (bytes.size > maxBytes) {
            bitmap.recycle()
            throw AppError.Validation("Image is too large after compression")
        }
        val id = Ids.newId()
        val file = File(root, "$id.jpg")
        val size = bytes.size.toLong()
        val sha256 = Hashing.sha256Hex(bytes)
        FileOutputStream(file).use { it.write(bytes) }
        bytes.fill(0)
        val result = PreparedAttachment(
            id = id,
            filePath = file.absolutePath,
            mimeType = "image/jpeg",
            sizeBytes = size,
            width = bitmap.width,
            height = bitmap.height,
            sha256 = sha256,
        )
        bitmap.recycle()
        result
    }

    fun safeFile(path: String): File {
        val file = File(path).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (!file.path.startsWith(canonicalRoot.path + File.separator)) throw AppError.Validation("Attachment path is outside app storage")
        return file
    }

    fun deletePath(path: String) {
        runCatching { safeFile(path).delete() }
    }

    fun clearAll() {
        root.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun rootDirectory(): File = root

    private fun calculateSample(width: Int, height: Int, desired: Int): Int {
        var sample = 1
        while (width / sample > desired || height / sample > desired) sample *= 2
        return sample
    }

    private fun scale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}
