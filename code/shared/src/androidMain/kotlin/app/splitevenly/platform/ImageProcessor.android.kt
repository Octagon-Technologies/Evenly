package app.splitevenly.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Android receipt compression: decode → downscale the longest edge → JPEG re-encode. */
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

        val longest = max(decoded.width, decoded.height)
        val scaled = if (longest > maxDimension && longest > 0) {
            val ratio = maxDimension.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).roundToInt().coerceAtLeast(1),
                (decoded.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return ProcessedImage(out.toByteArray(), "image/jpeg", "jpg")
    }
}
