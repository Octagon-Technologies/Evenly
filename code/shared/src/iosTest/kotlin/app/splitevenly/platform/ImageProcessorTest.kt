@file:OptIn(ExperimentalForeignApi::class)

package app.splitevenly.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [ImageProcessor.compress] against the two ways a receipt photo can come back wrong: cropped to a
 * corner, or re-encoded at the wrong pixel size.
 *
 * The fixture is a tall image painted in four solid quadrants, which makes both failures assertable
 * without a golden file. A corner crop loses three of the four colors; a scale-factor mistake shows up
 * in the decoded pixel dimensions. Both were live bugs: the renderer inherited the screen's 3x contents
 * scale, so a "1600px" target produced a 4800px canvas.
 */
class ImageProcessorTest {

    private val processor = ImageProcessor()

    @Test
    fun compress_downscalesToTheRequestedLongestEdge() = runTest {
        val source = solidQuadrantsPng(width = 1000, height = 2000)

        val processed = processor.compress(source, "image/png", maxDimension = 800, quality = 90)

        val (w, h) = processed.bytes.decodedPixelSize()
        assertEquals(800, h, "longest edge should be scaled to maxDimension, got ${w}x$h")
        assertEquals(400, w, "aspect ratio should be preserved, got ${w}x$h")
    }

    @Test
    fun compress_keepsTheWholeImage_notACorner() = runTest {
        val source = solidQuadrantsPng(width = 1000, height = 2000)

        val processed = processor.compress(source, "image/png", maxDimension = 800, quality = 90)

        // Sampling a 2x2 grid collapses each quadrant to one pixel. All four fixture colors must survive;
        // a corner crop would repeat a single one.
        val corners = processed.bytes.sampleQuadrants()
        assertEquals(
            4, corners.toSet().size,
            "expected the four quadrant colors to survive compression, got $corners (a corner crop repeats one)",
        )
        assertTrue(RED in corners, "top-left quadrant missing, got $corners")
        assertTrue(GREEN in corners, "top-right quadrant missing, got $corners")
        assertTrue(BLUE in corners, "bottom-left quadrant missing, got $corners")
        assertTrue(YELLOW in corners, "bottom-right quadrant missing, got $corners")
    }

    @Test
    fun compress_leavesSmallImagesAlone() = runTest {
        val source = solidQuadrantsPng(width = 400, height = 600)

        val processed = processor.compress(source, "image/png", maxDimension = 1600, quality = 90)

        val (w, h) = processed.bytes.decodedPixelSize()
        assertEquals(400 to 600, w to h, "an image under maxDimension should not be resized, got ${w}x$h")
    }

    @Test
    fun compress_passesPdfBytesThrough() = runTest {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46) // "%PDF"

        val processed = processor.compress(pdf, "application/pdf")

        assertEquals("application/pdf", processed.mimeType)
        assertEquals("pdf", processed.extension)
        assertTrue(pdf.contentEquals(processed.bytes), "PDF bytes must not be re-encoded")
    }
}

// ── fixture + pixel helpers ───────────────────────────────────────────────────

/** Quadrant colors, as the 0xRRGGBB the samplers quantize to. */
private const val RED = 0xFF0000
private const val GREEN = 0x00FF00
private const val BLUE = 0x0000FF
private const val YELLOW = 0xFFFF00

/** A [width]x[height] PNG painted in four solid quadrants. */
private fun solidQuadrantsPng(width: Int, height: Int): ByteArray = memScoped {
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    val context = CGBitmapContextCreate(
        data = null,
        width = width.toULong(),
        height = height.toULong(),
        bitsPerComponent = 8u,
        bytesPerRow = 0u,
        space = colorSpace,
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
    ) ?: error("could not create the fixture bitmap context")

    val halfW = width / 2.0
    val halfH = height / 2.0
    // CGBitmapContext's origin is bottom-left, so the top row of the image is drawn last in y.
    CGContextSetRGBFillColor(context, 1.0, 0.0, 0.0, 1.0) // red
    CGContextFillRect(context, CGRectMake(0.0, halfH, halfW, halfH))
    CGContextSetRGBFillColor(context, 0.0, 1.0, 0.0, 1.0) // green
    CGContextFillRect(context, CGRectMake(halfW, halfH, halfW, halfH))
    CGContextSetRGBFillColor(context, 0.0, 0.0, 1.0, 1.0) // blue
    CGContextFillRect(context, CGRectMake(0.0, 0.0, halfW, halfH))
    CGContextSetRGBFillColor(context, 1.0, 1.0, 0.0, 1.0) // yellow
    CGContextFillRect(context, CGRectMake(halfW, 0.0, halfW, halfH))

    val cgImage = CGBitmapContextCreateImage(context) ?: error("could not snapshot the fixture bitmap")
    val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage)) ?: error("could not encode the fixture")
    png.toByteArray()
}

/** Decoded pixel dimensions of an encoded image, ignoring any UIImage points/scale indirection. */
private fun ByteArray.decodedPixelSize(): Pair<Int, Int> {
    val cgImage = decodeCgImage()
    return CGImageGetWidth(cgImage).toInt() to CGImageGetHeight(cgImage).toInt()
}

/** Collapse the image to a 2x2 grid and return the four quantized 0xRRGGBB samples. */
private fun ByteArray.sampleQuadrants(): List<Int> = memScoped {
    val cgImage = decodeCgImage()
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    val bytesPerRow = 2 * 4
    val buffer = allocArray<UByteVar>(2 * bytesPerRow)
    val context = CGBitmapContextCreate(
        data = buffer,
        width = 2u,
        height = 2u,
        bitsPerComponent = 8u,
        bytesPerRow = bytesPerRow.toULong(),
        space = colorSpace,
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
    ) ?: error("could not create the sampling context")
    CGContextDrawImage(context, CGRectMake(0.0, 0.0, 2.0, 2.0), cgImage)

    (0 until 4).map { i ->
        val offset = i * 4
        // Quantize: JPEG re-encoding shifts solid colors by a few units, and a 2x2 downsample of a solid
        // quadrant blends a little at the seams. Rounding each channel to 0 or 255 keeps the fixture exact.
        val r = if (buffer[offset].toInt() >= 128) 0xFF else 0x00
        val g = if (buffer[offset + 1].toInt() >= 128) 0xFF else 0x00
        val b = if (buffer[offset + 2].toInt() >= 128) 0xFF else 0x00
        (r shl 16) or (g shl 8) or b
    }
}

private fun ByteArray.decodeCgImage(): CGImageRef {
    val image = UIImage.imageWithData(toNSData()) ?: error("could not decode the processed bytes")
    // A UIImage carrying a non-1 scale reports size in points; CGImage is the pixel truth.
    image.size.useContents { require(width > 0.0 && height > 0.0) { "decoded image has no size" } }
    return image.CGImage ?: error("decoded image has no backing CGImage")
}
