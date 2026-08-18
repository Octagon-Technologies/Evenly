package app.splitevenly.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Android receipt compression: decode → apply EXIF orientation → downscale the longest edge → JPEG. */
actual class ImageProcessor actual constructor() {

    actual suspend fun compress(
        bytes: ByteArray,
        mimeType: String,
        maxDimension: Int,
        quality: Int,
    ): ProcessedImage {
        // Non-images (PDF) and anything Android can't decode pass through untouched.
        if (!mimeType.startsWith("image", ignoreCase = true)) {
            return ProcessedImage(bytes, mimeType, extensionForMime(mimeType))
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return ProcessedImage(bytes, mimeType, extensionForMime(mimeType))

        // BitmapFactory hands back raw sensor pixels and drops the EXIF orientation tag, so a portrait
        // camera photo decodes sideways. Bake the rotation in before re-encoding: JPEG output would carry
        // no orientation tag to correct it later, and the thumbnails crop-scale whatever they are given.
        val upright = decoded.applyExifOrientation(bytes)

        val longest = max(upright.width, upright.height)
        val scaled = if (longest > maxDimension && longest > 0) {
            val ratio = maxDimension.toFloat() / longest
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * ratio).roundToInt().coerceAtLeast(1),
                (upright.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            upright
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return ProcessedImage(out.toByteArray(), "image/jpeg", "jpg")
    }
}

/** Rotate/flip [this] to match the EXIF orientation recorded in [source]; returns it unchanged if upright. */
private fun Bitmap.applyExifOrientation(source: ByteArray): Bitmap {
    val orientation = try {
        ByteArrayInputStream(source).use { ExifInterface(it) }
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Throwable) {
        // A file with no readable EXIF block (PNG, a re-encoded share) is normal, not an error.
        ExifInterface.ORIENTATION_NORMAL
    }
    val matrix = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        else -> return this
    }
    return try {
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    } catch (e: OutOfMemoryError) {
        this // a sideways receipt still beats no receipt
    }
}
