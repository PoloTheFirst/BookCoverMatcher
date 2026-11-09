// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.author

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import com.bookcovermatcher.core.author.AuthorImageFormat
import com.bookcovermatcher.core.author.SmilAnimation
import com.bookcovermatcher.core.author.SvgCanvas
import com.bookcovermatcher.core.author.SvgEmbeddedImages
import com.caverock.androidsvg.SVG

/** A decoded picture and the shape it must be drawn at, so the layout can reserve its space up front. */
data class DecodedImage(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    /**
     * The animation the source declares, or null for a still picture (v2.6.0 crash-fix 1).
     *
     * [bitmap] is always the first frame, so a caller that does not animate still has something to draw.
     */
    val animation: SmilAnimation? = null,
) {
    /** width / height, safe for any decoded picture. */
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 1f
}

/**
 * Turns downloaded bytes into a drawable bitmap (v2.6.0 amendment).
 *
 * Two decoders, chosen by the bytes and not by the URL: `BitmapFactory` for PNG/JPEG/WebP/GIF, and AndroidSVG for
 * SVG (the Shields.io badge and the contribution graph are both SVG). AndroidSVG parses and rasterises; it does
 * not run scripts, fetch external references or execute anything, which is why it is safe here.
 *
 * Rasters are downsampled while decoding, not after: the banner is a 3840×2160 PNG, and decoding it at full size
 * would cost ~33 MB for an image that is never wider than the screen.
 */
object AuthorImageDecoder {

    /** Log tag for a frame that could not be drawn; a real device's logcat then answers "why frozen". */
    private const val TAG = "AuthorPage"

    /** The largest enlargement a vector is rasterised at, whatever the display claims. */
    private const val MAX_VECTOR_SCALE = 4f

    /** Detects a root element that already carries a viewBox. */
    private val VIEW_BOX = Regex("<svg\\b[^>]*viewBox", RegexOption.IGNORE_CASE)

    /**
     * Decode [bytes] to at most [maxWidth] px wide (and [maxHeight] tall for SVGs).
     *
     * Rasters are downsampled while decoding, not after: the banner is a 3840×2160 PNG, and decoding it at full size
     * would cost ~33 MB for an image that is never wider than the screen.
     *
     * [density] is the display density, and it applies to **vector** sources only. A Shields.io badge is 53×20
     * *units*; drawn at its 20 dp height on a 2.625× screen it occupies 139×52 px, so rasterising it at its intrinsic
     * 53×20 px and letting the UI enlarge it 2.6× is what made the icons look soft (v2.6.0 crash-fix 3). Rendering
     * the vector at the pixels it will actually occupy keeps it crisp; a raster never gets enlarged this way, because
     * it is only ever subsampled.
     */
    fun decode(bytes: ByteArray, maxWidth: Int = 1000, maxHeight: Int = 1280, density: Float = 1f): DecodedImage? {
        if (bytes.isEmpty()) return null
        return when (AuthorImageFormat.of(bytes)) {
            AuthorImageFormat.SVG -> decodeSvg(bytes, maxWidth, maxHeight, density)
            AuthorImageFormat.PNG, AuthorImageFormat.JPEG, AuthorImageFormat.WEBP, AuthorImageFormat.GIF ->
                decodeRaster(bytes, maxWidth)
            AuthorImageFormat.UNKNOWN -> null
        }
    }

    private fun decodeRaster(bytes: ByteArray, maxWidth: Int): DecodedImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        DecodedImage(bitmap, bitmap.width, bitmap.height)
    }.getOrNull()

    /**
     * Renders an SVG document to a bitmap.
     *
     * AndroidSVG rasterises the document's static geometry and ignores SMIL, so an animated document is drawn at
     * the instant [frameDocument] describes rather than frozen in its first frame: [SmilAnimation] rewrites the
     * source's animated attributes for the requested time and AndroidSVG draws the result.
     */
    private fun decodeSvg(bytes: ByteArray, maxWidth: Int, maxHeight: Int, density: Float): DecodedImage? = runCatching {
        val text = String(bytes, Charsets.UTF_8)
        // Shields.io draws its icon as a nested SVG in a `data:` URI, which this renderer cannot resolve; the
        // document is converted once here, so every frame and every cache read benefits (v2.6.0 crash-fix 2).
        // Then the canvas is normalised: these documents declare no viewBox, so without one their percentage-sized
        // background only covers the shrunken viewport — which is why the newest weeks and the bottom strip had no
        // background (v2.6.0 crash-fix 4).
        val prepared = SvgCanvas.normalise(SvgEmbeddedImages.prepare(text))
        val animation = SmilAnimation.detect(prepared)
        val document = animation?.frameDocument(0L) ?: prepared
        val picture = rasteriseSvg(document, maxWidth, maxHeight, density) ?: return null
        DecodedImage(picture, picture.width, picture.height, animation)
    }.getOrNull()

    /**
     * Draws one instant of [animation] at [elapsedMs].
     *
     * Called from a background dispatcher only: it re-parses the rewritten document and rasterises it, which is
     * the same work a first decode does. Returns null when the document cannot be drawn at all, so the caller can
     * leave the last good frame on screen instead of crashing.
     */
    fun renderFrame(
        animation: SmilAnimation,
        elapsedMs: Long,
        maxWidth: Int = 1000,
        maxHeight: Int = 1280,
    ): DecodedImage? {
        val document = animation.frameDocument(elapsedMs)
        val bitmap = rasteriseSvg(document, maxWidth, maxHeight)
        if (bitmap == null) {
            // Never silent: a frame that cannot be drawn is why a graph would look frozen, and the next logcat
            // should answer "which stage failed" rather than leave it to inference.
            Log.w(TAG, "animation frame at ${elapsedMs}ms did not render; keeping the previous frame")
            return null
        }
        return DecodedImage(bitmap, bitmap.width, bitmap.height, animation)
    }

    /** Parses [document] and rasterises it to at most [maxWidth] × [maxHeight]; null when it will not draw. */
    private fun rasteriseSvg(document: String, maxWidth: Int, maxHeight: Int, density: Float = 1f): Bitmap? = runCatching {
        val svg = SVG.getFromString(document)
        val intrinsicWidth = svg.documentWidth.takeIf { it > 0f } ?: 320f
        val intrinsicHeight = svg.documentHeight.takeIf { it > 0f } ?: 120f
        // A vector is rendered at the pixels it will occupy on screen — never below its own size — so a 53×20 badge
        // becomes 139×52 on a 2.625× display instead of being enlarged by the compositor (v2.6.0 crash-fix 3).
        val target = density.coerceIn(1f, MAX_VECTOR_SCALE)
        val scale = minOf(target, maxWidth / intrinsicWidth, maxHeight / intrinsicHeight)
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        svg.setDocumentWidth(width.toFloat())
        svg.setDocumentHeight(height.toFloat())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (VIEW_BOX.containsMatchIn(document)) {
            // A document with a viewBox maps its own coordinates onto the viewport itself (SvgCanvas.normalise adds
            // one to every generated document), so the canvas must NOT be scaled again (v2.6.0 crash-fix 4).
            svg.renderToCanvas(canvas)
        } else {
            // No viewBox: AndroidSVG draws the content 1:1 whatever document size is set, so the canvas is scaled
            // explicitly or the right/bottom of the picture is cropped (v2.6.0 crash-fix 2).
            canvas.scale(width / intrinsicWidth, height / intrinsicHeight)
            svg.renderToCanvas(canvas)
        }
        bitmap
    }.getOrNull()

    /** The power-of-two subsample that keeps the decoded width at or above [maxWidth] but as small as possible. */
    internal fun sampleSizeFor(width: Int, height: Int, maxWidth: Int): Int {
        if (maxWidth <= 0) return 1
        var sample = 1
        var widest = maxOf(width, height)
        while (widest / 2 >= maxWidth) {
            widest /= 2
            sample *= 2
        }
        return sample
    }
}
