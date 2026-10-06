package com.bookcovermatcher.core.vision

import kotlin.math.ceil
import kotlin.math.max

/**
 * Image -> model-input tensor for OpenCLIP / CLIP image encoders.
 *
 *  1. Transparent pixels are flattened onto white.
 *  2. The whole image is resized to `size x size` with an anti-aliased **bicubic** filter
 *     (same algorithm as Pillow's `Image.resize(..., Image.BICUBIC)`, so the Python tools and the
 *     app produce the same tensor). The image is stretched, not centre-cropped: a cover's title and
 *     author sit at the top and bottom, which a square crop would cut off. v1's pHash also resized
 *     the whole image to a square.
 *  3. RGB is scaled to 0..1 and normalised with the CLIP mean/std.
 *
 * Output layout is `[3, size, size]` (CHW) as a flat `FloatArray`.
 */
object ClipPreprocessor {
    val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    const val DEFAULT_SIZE = 224

    fun toChw(argb: IntArray, width: Int, height: Int, size: Int = DEFAULT_SIZE): FloatArray {
        val planes = resizeBicubicRgb(argb, width, height, size, size)
        val n = size * size
        val out = FloatArray(3 * n)
        for (c in 0 until 3) {
            val plane = planes[c]
            val mean = MEAN[c]
            val std = STD[c]
            val base = c * n
            for (i in 0 until n) out[base + i] = (plane[i] / 255f - mean) / std
        }
        return out
    }

    /** Returns three 8-bit planes (R, G, B), each `outW * outH`, after PIL-style bicubic resampling. */
    fun resizeBicubicRgb(argb: IntArray, w: Int, h: Int, outW: Int, outH: Int): Array<IntArray> {
        require(w > 0 && h > 0 && outW > 0 && outH > 0) { "bad size" }
        require(argb.size >= w * h) { "pixel buffer too small" }

        val rIn = IntArray(w * h)
        val gIn = IntArray(w * h)
        val bIn = IntArray(w * h)
        for (i in 0 until w * h) {
            val p = ImageOps.flattenOnWhite(argb[i])
            rIn[i] = (p shr 16) and 0xFF
            gIn[i] = (p shr 8) and 0xFF
            bIn[i] = p and 0xFF
        }

        val kx = coefficients(w, outW)
        val ky = coefficients(h, outH)
        return arrayOf(
            resizePlane(rIn, w, h, outW, outH, kx, ky),
            resizePlane(gIn, w, h, outW, outH, kx, ky),
            resizePlane(bIn, w, h, outW, outH, kx, ky),
        )
    }

    // ---- Pillow "Resample.c" bicubic (a = -0.5) with support scaled for down-sampling ----

    private class Coeffs(val bounds: IntArray, val counts: IntArray, val ksize: Int, val k: FloatArray)

    private fun bicubic(xIn: Double): Double {
        val a = -0.5
        val x = if (xIn < 0.0) -xIn else xIn
        if (x < 1.0) return ((a + 2.0) * x - (a + 3.0)) * x * x + 1
        if (x < 2.0) return (((x - 5) * x + 8) * x - 4) * a
        return 0.0
    }

    private fun coefficients(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 2.0 * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize)
        val counts = IntArray(outSize)
        val k = FloatArray(outSize * ksize)
        val ss = 1.0 / filterScale
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var ww = 0.0
            val tmp = DoubleArray(xmax)
            for (x in 0 until xmax) {
                val w = bicubic((x + xmin - center + 0.5) * ss)
                tmp[x] = w
                ww += w
            }
            for (x in 0 until xmax) k[xx * ksize + x] = (if (ww != 0.0) tmp[x] / ww else tmp[x]).toFloat()
            bounds[xx] = xmin
            counts[xx] = xmax
        }
        return Coeffs(bounds, counts, ksize, k)
    }

    private fun clip8(v: Float): Int {
        val i = Math.floor((v + 0.5f).toDouble()).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    private fun resizePlane(src: IntArray, w: Int, h: Int, outW: Int, outH: Int, kx: Coeffs, ky: Coeffs): IntArray {
        // horizontal pass -> 8-bit intermediate (Pillow also rounds between passes)
        val mid = IntArray(outW * h)
        for (y in 0 until h) {
            val row = y * w
            for (ox in 0 until outW) {
                val xmin = kx.bounds[ox]
                val n = kx.counts[ox]
                val kb = ox * kx.ksize
                var acc = 0f
                for (i in 0 until n) acc += src[row + xmin + i] * kx.k[kb + i]
                mid[y * outW + ox] = clip8(acc)
            }
        }
        // vertical pass
        val out = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val ymin = ky.bounds[oy]
            val n = ky.counts[oy]
            val kb = oy * ky.ksize
            for (x in 0 until outW) {
                var acc = 0f
                for (i in 0 until n) acc += mid[(ymin + i) * outW + x] * ky.k[kb + i]
                out[oy * outW + x] = clip8(acc)
            }
        }
        return out
    }
}
