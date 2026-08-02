package app.splitevenly.platform

/** Output of [ImageProcessor.compress]: the (possibly re-encoded) bytes plus their mime + file extension. */
class ProcessedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
)

/**
 * Receipt image compression (06 §5.1, D-22). Photos off a modern camera are several MB; a receipt only
 * needs to be legible, so we downscale + re-encode to JPEG before upload. Non-image inputs (PDF) pass
 * through unchanged. **Android** uses `BitmapFactory` + `Bitmap.compress`; **iOS** re-encodes via
 * `UIImageJPEGRepresentation`.
 *
 * Constructor is parameterless on both targets; instances come from `platformModule()`.
 */
expect class ImageProcessor() {
    /**
     * @param maxDimension the longest edge to scale down to (no upscaling).
     * @param quality JPEG quality 0–100.
     */
    suspend fun compress(
        bytes: ByteArray,
        mimeType: String,
        maxDimension: Int = 1600,
        quality: Int = 80,
    ): ProcessedImage
}

/** File extension for a mime type — used to build the Storage object key. */
internal fun extensionForMime(mimeType: String): String = when {
    mimeType.equals("image/png", ignoreCase = true) -> "png"
    mimeType.startsWith("image/jpeg", ignoreCase = true) || mimeType.equals("image/jpg", ignoreCase = true) -> "jpg"
    mimeType.equals("image/webp", ignoreCase = true) -> "webp"
    mimeType.equals("image/heic", ignoreCase = true) || mimeType.equals("image/heif", ignoreCase = true) -> "heic"
    mimeType.contains("pdf", ignoreCase = true) -> "pdf"
    else -> mimeType.substringAfterLast('/', "bin").ifBlank { "bin" }
}
