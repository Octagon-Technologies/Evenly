@file:OptIn(ExperimentalForeignApi::class)

package app.splitevenly.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iOS receipt compression: downscale the longest edge via [UIGraphicsImageRenderer], then re-encode to
 * JPEG at the requested quality — mirroring the Android actual (`BitmapFactory` decode → scale →
 * `Bitmap.compress`). Non-images (PDF) pass through unchanged.
 */
actual class ImageProcessor actual constructor() {

    actual suspend fun compress(
        bytes: ByteArray,
        mimeType: String,
        maxDimension: Int,
        quality: Int,
    ): ProcessedImage {
        if (!mimeType.startsWith("image", ignoreCase = true)) {
            return ProcessedImage(bytes, mimeType, extensionForMime(mimeType))
        }
        val image = UIImage.imageWithData(bytes.toNSData())
            ?: return ProcessedImage(bytes, mimeType, extensionForMime(mimeType))

        val width = image.size.useContents { width }
        val height = image.size.useContents { height }
        val longest = max(width, height)
        val scaled = if (longest > maxDimension.toDouble() && longest > 0.0) {
            val ratio = maxDimension.toDouble() / longest
            val targetSize = CGSizeMake(
                (width * ratio).roundToInt().coerceAtLeast(1).toDouble(),
                (height * ratio).roundToInt().coerceAtLeast(1).toDouble(),
            )
            val (targetW, targetH) = targetSize.useContents { width to height }
            UIGraphicsImageRenderer(size = targetSize).imageWithActions { _ ->
                image.drawInRect(CGRectMake(0.0, 0.0, targetW, targetH))
            }
        } else {
            image
        }

        val jpeg = UIImageJPEGRepresentation(scaled, quality.coerceIn(1, 100) / 100.0)
            ?: return ProcessedImage(bytes, mimeType, extensionForMime(mimeType))
        return ProcessedImage(jpeg.toByteArray(), "image/jpeg", "jpg")
    }
}
