package com.bookcovermatcher.core.vision

/**
 * Small, dependency-free pixel helpers working on packed ARGB `IntArray`s.
 * Android code converts `Bitmap` <-> `IntArray` with `getPixels` / `setPixels`.
 */
object ImageOps {

    /** BT.601 luma of the RGB channels (same weights as v1: 0.299 / 0.587 / 0.114). */
    fun luma(argb: Int): Double =
        0.299 * ((argb shr 16) and 0xFF) + 0.587 * ((argb shr 8) and 0xFF) + 0.114 * (argb and 0xFF)

    /** Composites a pixel over white and returns opaque ARGB (transparent covers become white). */
    fun flattenOnWhite(argb: Int): Int {
        val a = argb ushr 24
        if (a == 0xFF) return argb
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val inv = 255 - a
        val rr = (r * a + 255 * inv + 127) / 255
        val gg = (g * a + 255 * inv + 127) / 255
        val bb = (b * a + 255 * inv + 127) / 255
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private class Axis(val starts: IntArray, val counts: IntArray, val offsets: IntArray, val weights: FloatArray)

    /** Fractional box-filter weights (what an "area" resize averages). */
    private fun boxAxis(inSize: Int, outSize: Int): Axis {
        val scale = inSize.toDouble() / outSize
        val starts = IntArray(outSize)
        val counts = IntArray(outSize)
        val offsets = IntArray(outSize)
        val tmp = ArrayList<Float>()
        for (o in 0 until outSize) {
            val a = o * scale
            val b = (o + 1) * scale
            val i0 = a.toInt().coerceIn(0, inSize - 1)
            val i1 = kotlin.math.ceil(b).toInt().coerceIn(i0 + 1, inSize)
            starts[o] = i0
            counts[o] = i1 - i0
            offsets[o] = tmp.size
            var sum = 0.0
            val ws = DoubleArray(i1 - i0)
            for (i in i0 until i1) {
                val w = (minOf(b, (i + 1).toDouble()) - maxOf(a, i.toDouble())).coerceAtLeast(0.0)
                ws[i - i0] = w
                sum += w
            }
            if (sum <= 0.0) { ws[0] = 1.0; sum = 1.0 }
            for (w in ws) tmp.add((w / sum).toFloat())
        }
        return Axis(starts, counts, offsets, tmp.toFloatArray())
    }

    /**
     * Area-averaging resize (good for strong down-scaling). Output channels are rounded to 8 bits,
     * alpha is forced opaque. Used for the small frames the Auto Scan engine analyses.
     */
    fun areaResizeArgb(src: IntArray, w: Int, h: Int, outW: Int, outH: Int): IntArray {
        require(w > 0 && h > 0 && outW > 0 && outH > 0) { "bad size" }
        require(src.size >= w * h) { "pixel buffer too small" }
        val ax = boxAxis(w, outW)
        val ay = boxAxis(h, outH)

        val tr = FloatArray(h * outW)
        val tg = FloatArray(h * outW)
        val tb = FloatArray(h * outW)
        for (y in 0 until h) {
            val row = y * w
            for (ox in 0 until outW) {
                var r = 0f; var g = 0f; var b = 0f
                val s = ax.starts[ox]; val n = ax.counts[ox]; val off = ax.offsets[ox]
                for (k in 0 until n) {
                    val p = src[row + s + k]
                    val wt = ax.weights[off + k]
                    r += ((p shr 16) and 0xFF) * wt
                    g += ((p shr 8) and 0xFF) * wt
                    b += (p and 0xFF) * wt
                }
                val di = y * outW + ox
                tr[di] = r; tg[di] = g; tb[di] = b
            }
        }

        val out = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val s = ay.starts[oy]; val n = ay.counts[oy]; val off = ay.offsets[oy]
            for (x in 0 until outW) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in 0 until n) {
                    val wt = ay.weights[off + k]
                    val si = (s + k) * outW + x
                    r += tr[si] * wt
                    g += tg[si] * wt
                    b += tb[si] * wt
                }
                out[oy * outW + x] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            }
        }
        return out
    }

    internal fun to8(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }
}
