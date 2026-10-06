package com.bookcovermatcher.core.autoscan

import com.bookcovermatcher.core.vision.ImageOps

/**
 * Auto Scan tuning. Defaults are the v1.0.0 values from `js/config.js`.
 * The only addition is [graceFrames].
 */
data class AutoScanConfig(
    val intervalMs: Long = 400,
    val stableFrames: Int = 3,
    val stableDistance: Int = 4,
    val duplicateDistance: Int = 8,
    val cooldownMs: Long = 1500,
    val blurThreshold: Float = 40f,
    val darkThreshold: Float = 45f,
    val brightThreshold: Float = 215f,
    val resetBadFrames: Int = 5,
    /**
     * v2: a single bad frame (a shaky moment, a flicker) no longer wipes the stability streak;
     * it is only reset when more than this many bad frames arrive in a row. 0 = v1 behaviour.
     */
    val graceFrames: Int = 1,
) {
    companion object {
        const val ANALYSIS_SIZE = 64
        const val SIGNATURE_SIZE = 32
    }
}

class FrameQuality(val blur: Float, val meanLum: Float)

/** Lightweight sharpness / brightness metrics. Port of `analyzeQuality` from `js/auto-scan.js`. */
object FrameMetrics {

    /** [px] is a `size x size` ARGB image (the guide crop squashed to 64x64 in v1). */
    fun analyze(px: IntArray, size: Int = AutoScanConfig.ANALYSIS_SIZE): FrameQuality {
        require(px.size >= size * size) { "pixel buffer too small" }
        val s = size
        val gray = FloatArray(s * s)
        var sum = 0.0
        for (i in 0 until s * s) {
            val g = ImageOps.luma(px[i])
            gray[i] = g.toFloat()
            sum += g
        }
        val meanLum = sum / (s * s)

        var lapSum = 0.0
        var lapSumSq = 0.0
        var count = 0
        for (y in 1 until s - 1) {
            val row = y * s
            for (x in 1 until s - 1) {
                val i = row + x
                val lap = (gray[i - s] + gray[i + s] + gray[i - 1] + gray[i + 1]).toDouble() - 4.0 * gray[i]
                lapSum += lap
                lapSumSq += lap * lap
                count++
            }
        }
        val lapMean = lapSum / count
        val variance = lapSumSq / count - lapMean * lapMean
        return FrameQuality(variance.toFloat(), meanLum.toFloat())
    }
}

/**
 * 64-bit perceptual signature (32x32 -> blur -> equalise -> DCT -> 8x8 -> median).
 * Port of the pure-JS path of `js/phash.js`.
 *
 * **Only used by Auto Scan** to tell "the frame is steady" and "this is the cover I just captured"
 * apart. Matching against the spreadsheet uses OpenCLIP embeddings, not this signature.
 */
object FrameSignature {
    private const val N = 32

    private val DCT_COS = DoubleArray(N * N).also { t ->
        for (u in 0 until N) for (x in 0 until N) {
            t[u * N + x] = Math.cos(((2 * x + 1) * u * Math.PI) / 64)
        }
    }

    /** [px] is a 32x32 ARGB image. */
    fun compute(px: IntArray): Long {
        require(px.size >= N * N) { "need a ${N}x$N image" }
        val gray = DoubleArray(N * N) { ImageOps.luma(px[it]) }

        // 3x3 box blur, separable, edge-clamped
        val tmp = DoubleArray(N * N)
        val out = DoubleArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            val x0 = if (x > 0) x - 1 else 0
            val x1 = if (x < N - 1) x + 1 else N - 1
            tmp[y * N + x] = (gray[y * N + x0] + gray[y * N + x] + gray[y * N + x1]) / 3
        }
        for (x in 0 until N) for (y in 0 until N) {
            val y0 = if (y > 0) y - 1 else 0
            val y1 = if (y < N - 1) y + 1 else N - 1
            out[y * N + x] = (tmp[y0 * N + x] + tmp[y * N + x] + tmp[y1 * N + x]) / 3
        }

        equalizeInPlace(out)

        val dct = DoubleArray(N * N)
        for (y in 0 until N) {
            val rowOff = y * N
            for (u in 0 until N) {
                val cosOff = u * N
                var s = 0.0
                for (x in 0 until N) s += out[rowOff + x] * DCT_COS[cosOff + x]
                tmp[rowOff + u] = s
            }
        }
        for (x in 0 until N) for (v in 0 until N) {
            val cosOff = v * N
            var s = 0.0
            for (y in 0 until N) s += tmp[y * N + x] * DCT_COS[cosOff + y]
            dct[v * N + x] = s
        }

        val coeffs = DoubleArray(64)
        for (r in 0 until 8) for (c in 0 until 8) coeffs[r * 8 + c] = dct[r * N + c]

        val ac = coeffs.copyOfRange(1, 64).also { it.sort() }
        val mid = ac.size / 2
        val median = if (ac.size % 2 == 1) ac[mid] else (ac[mid - 1] + ac[mid]) / 2
        var bits = 0L
        for (i in 0 until 64) if (coeffs[i] > median) bits = bits or (1L shl i)
        return bits
    }

    private fun equalizeInPlace(gray: DoubleArray) {
        val n = gray.size
        val hist = IntArray(256)
        for (g in gray) hist[g.toInt().coerceIn(0, 255)]++
        val cdf = IntArray(256)
        var acc = 0
        for (i in 0 until 256) { acc += hist[i]; cdf[i] = acc }
        var cdfMin = 0
        for (i in 0 until 256) if (cdf[i] > 0) { cdfMin = cdf[i]; break }
        val scale = 255.0 / maxOf(1, n - cdfMin)
        val lut = IntArray(256) { i ->
            Math.floor((cdf[i] - cdfMin) * scale + 0.5).toInt().coerceIn(0, 255)
        }
        for (i in 0 until n) gray[i] = lut[gray[i].toInt().coerceIn(0, 255)].toDouble()
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}

enum class ScanPhase { HOLD_STILL, TOO_DARK, TOO_BRIGHT, STABILIZING, SAME_COVER, CAPTURED }

/**
 * What one Auto Scan step decided. [capture] is true on the single step that accepts a cover.
 * The remaining fields only drive the on-screen feedback (badge, progress pips, quality chips).
 */
data class ScanTick(
    val phase: ScanPhase,
    val stableCount: Int,
    val stableTarget: Int,
    val sharp: Boolean,
    val lit: Boolean,
    val steady: Boolean,
    val capture: Boolean,
    /** 0..1 sharpness meter (1.0 == twice the blur threshold). */
    val sharpness: Float,
    /** 0..1 mean brightness. */
    val brightness: Float,
    val signature: Long? = null,
)

/**
 * The Auto Scan state machine. Port of `tick()` from `js/auto-scan.js`; all thresholds and the
 * order of checks are unchanged (see [AutoScanConfig]). Pure and clock-injected, so it is unit-tested.
 */
class AutoScanEngine(val config: AutoScanConfig = AutoScanConfig()) {

    private var prevHash: Long? = null
    private var stableCount = 0
    private var cooldownUntil = 0L
    private var lastAcceptedHash: Long? = null
    private var badFrameCount = 0
    private var badStreak = 0
    private var lastOutcome = ScanPhase.CAPTURED
    private var lastSharp = false
    private var lastLit = false
    private var lastSteady = false
    private var lastSharpness = 0f
    private var lastBrightness = 0f

    fun reset() {
        resetStability()
        resetAccepted()
        cooldownUntil = 0L
        badStreak = 0
        lastOutcome = ScanPhase.CAPTURED
    }

    /** Forgets the stability streak and the last accepted cover but keeps the cooldown running. */
    fun forgetCapture() {
        resetStability()
        resetAccepted()
        badStreak = 0
    }

    fun isCoolingDown(now: Long): Boolean = now < cooldownUntil

    /** Result to show while the cooldown runs (no frame has to be analysed). */
    fun cooldownTick(): ScanTick = ScanTick(
        phase = lastOutcome, stableCount = 0, stableTarget = config.stableFrames,
        sharp = lastSharp, lit = lastLit, steady = lastSteady, capture = false,
        sharpness = lastSharpness, brightness = lastBrightness,
    )

    private fun resetStability() { prevHash = null; stableCount = 0 }
    private fun resetAccepted() { lastAcceptedHash = null; badFrameCount = 0 }

    /**
     * Processes one analysed frame. [px64] is the guide crop squashed to 64x64,
     * [px32] the same crop at 32x32 (both ARGB).
     */
    fun step(now: Long, px64: IntArray, px32: IntArray): ScanTick {
        val q = FrameMetrics.analyze(px64, AutoScanConfig.ANALYSIS_SIZE)
        val sharp = q.blur >= config.blurThreshold
        val tooDark = q.meanLum < config.darkThreshold
        val tooBright = q.meanLum > config.brightThreshold
        val lit = !tooDark && !tooBright
        val sharpness = (q.blur / (config.blurThreshold * 2f)).coerceIn(0f, 1f)
        val brightness = (q.meanLum / 255f).coerceIn(0f, 1f)
        lastSharp = sharp; lastLit = lit; lastSharpness = sharpness; lastBrightness = brightness

        if (!sharp || !lit) {
            badStreak++
            if (badStreak > config.graceFrames) resetStability()
            badFrameCount++
            if (badFrameCount >= config.resetBadFrames) resetAccepted()
            lastSteady = false
            val phase = when {
                !sharp -> ScanPhase.HOLD_STILL
                tooDark -> ScanPhase.TOO_DARK
                else -> ScanPhase.TOO_BRIGHT
            }
            return ScanTick(phase, stableCount, config.stableFrames, sharp, lit, false, false, sharpness, brightness)
        }

        badStreak = 0
        badFrameCount = 0

        val hash = FrameSignature.compute(px32)

        var steady = false
        val prev = prevHash
        if (prev != null) {
            steady = FrameSignature.distance(prev, hash) <= config.stableDistance
            stableCount = if (steady) stableCount + 1 else 0
        } else {
            stableCount = 0
        }
        prevHash = hash
        lastSteady = steady

        if (stableCount < config.stableFrames) {
            return ScanTick(ScanPhase.STABILIZING, stableCount, config.stableFrames, true, true, steady, false,
                sharpness, brightness, hash)
        }

        val accepted = lastAcceptedHash
        if (accepted != null && FrameSignature.distance(accepted, hash) <= config.duplicateDistance) {
            resetStability()
            cooldownUntil = now + config.cooldownMs
            lastOutcome = ScanPhase.SAME_COVER
            return ScanTick(ScanPhase.SAME_COVER, 0, config.stableFrames, true, true, true, false,
                sharpness, brightness, hash)
        }

        lastAcceptedHash = hash
        resetStability()
        cooldownUntil = now + config.cooldownMs
        lastOutcome = ScanPhase.CAPTURED
        return ScanTick(ScanPhase.CAPTURED, config.stableFrames, config.stableFrames, true, true, true, true,
            sharpness, brightness, hash)
    }
}
