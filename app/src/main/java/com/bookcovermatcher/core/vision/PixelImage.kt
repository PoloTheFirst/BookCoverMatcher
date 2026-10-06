package com.bookcovermatcher.core.vision

import com.bookcovermatcher.core.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoded picture as packed ARGB pixels (row-major). Platform-neutral so the pipeline can be unit-tested. */
class PixelImage(val argb: IntArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(argb.size >= width * height) { "pixel buffer too small" }
    }
}

/** What the pipeline needs from the platform's image stack (Android: BitmapFactory / Bitmap). */
interface ImageCodec {
    /**
     * Decodes [bytes] and scales the picture down so its long edge is at most [maxEdge].
     * Returns `null` when the format cannot be decoded (the picture is then skipped, as in v1).
     */
    fun decode(bytes: ByteArray, mime: String, maxEdge: Int): PixelImage?

    /** JPEG (quality ~72) of [image] letterboxed on the dark `#001428` background inside a [boxW] x [boxH] box. */
    fun makeThumbJpeg(image: PixelImage, boxW: Int, boxH: Int): ByteArray
}

/** Image -> unit-length embedding vector. */
interface ImageEmbedder {
    val dim: Int

    /** Identifies the model so cached indexes from another model are never reused. */
    val fingerprint: String

    /** Returns the L2-normalised embedding of [image]. */
    fun embed(image: PixelImage): FloatArray
}

class IntRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Geometry of the capture guide. Port of `grabVideoFrame()` from `js/camera.js`:
 * the viewfinder shows the camera frame with `object-fit: cover`, and the guide rectangle
 * (70 % x 78 % of the viewfinder, centred) is what gets captured.
 */
object GuideGeometry {

    /** Part of a [frameW] x [frameH] frame that a cover-fitted box of aspect [boxAspect] (w / h) shows. */
    fun coverRect(frameW: Int, frameH: Int, boxAspect: Float): IntRect {
        require(frameW > 0 && frameH > 0) { "empty frame" }
        val dst = if (boxAspect > 0f && boxAspect.isFinite()) boxAspect else Config.GUIDE_W / Config.GUIDE_H
        val src = frameW.toFloat() / frameH
        return if (src > dst) {
            val sw = (frameH * dst).roundToInt().coerceIn(1, frameW)
            IntRect((frameW - sw) / 2, 0, sw, frameH)
        } else {
            val sh = (frameW / dst).roundToInt().coerceIn(1, frameH)
            IntRect(0, (frameH - sh) / 2, frameW, sh)
        }
    }

    /** The guide rectangle, in frame pixels, for a viewfinder of aspect [boxAspect]. */
    fun guideRect(frameW: Int, frameH: Int, boxAspect: Float): IntRect {
        val c = coverRect(frameW, frameH, boxAspect)
        val gw = max(1, (c.w * Config.GUIDE_W).roundToInt())
        val gh = max(1, (c.h * Config.GUIDE_H).roundToInt())
        val gx = c.x + (c.w - gw) / 2
        val gy = c.y + (c.h - gh) / 2
        return IntRect(gx, gy, min(gw, frameW - gx), min(gh, frameH - gy))
    }

    /** Scale factor that brings a [w] x [h] picture's long edge down to [maxEdge] (never above 1). */
    fun downscale(w: Int, h: Int, maxEdge: Int): Float = min(1f, maxEdge.toFloat() / max(w, h))
}
