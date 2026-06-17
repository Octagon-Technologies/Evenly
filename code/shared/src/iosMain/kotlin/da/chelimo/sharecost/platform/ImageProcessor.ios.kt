@file:OptIn(ExperimentalForeignApi::class)

package da.chelimo.sharecost.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * iOS receipt compression: re-encode the image to JPEG at the requested quality. (Resizing the longest
 * edge is a worthwhile refinement but needs CoreGraphics context interop; JPEG re-encoding alone already
 * turns a multi-MB HEIC/PNG into a modest upload, which is the goal here.) Non-images pass through.
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
        val jpeg = UIImageJPEGRepresentation(image, quality.coerceIn(1, 100) / 100.0)
            ?: return ProcessedImage(bytes, mimeType, extensionForMime(mimeType))
        return ProcessedImage(jpeg.toByteArray(), "image/jpeg", "jpg")
    }
}
