@file:OptIn(ExperimentalForeignApi::class)

package app.splitevenly.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
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

        val sourceWidth = image.size.useContents { width }
        val sourceHeight = image.size.useContents { height }
        val longest = max(sourceWidth, sourceHeight)
        val scaled = if (longest > maxDimension.toDouble() && longest > 0.0) {
            val ratio = maxDimension.toDouble() / longest
            // Keep the target dimensions as plain Doubles rather than reading them back out of a CGSize:
            // inside `useContents`, an unqualified `width`/`height` resolves to an enclosing local of that
            // name *before* the CGSize receiver's member, so a read-back silently returned the source size
            // and drawInRect painted the photo at 1:1 into a smaller canvas, keeping only its top-left
            // corner. Never round-trip a dimension through useContents in this file.
            val targetWidth = (sourceWidth * ratio).roundToInt().coerceAtLeast(1).toDouble()
            val targetHeight = (sourceHeight * ratio).roundToInt().coerceAtLeast(1).toDouble()
            // Pin the contents scale to 1: the default format inherits the screen's scale (3x on device),
            // which would make a "1600px" cap produce a 4800px upload.
            val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
                scale = 1.0
                opaque = true
            }
            UIGraphicsImageRenderer(size = CGSizeMake(targetWidth, targetHeight), format = format)
                .imageWithActions { _ ->
                    // drawInRect honours the UIImage's EXIF orientation, so the result is upright.
                    image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
                }
        } else {
            image
        }

        val jpeg = UIImageJPEGRepresentation(scaled, quality.coerceIn(1, 100) / 100.0)
            ?: return ProcessedImage(bytes, mimeType, extensionForMime(mimeType))
        return ProcessedImage(jpeg.toByteArray(), "image/jpeg", "jpg")
    }
}
