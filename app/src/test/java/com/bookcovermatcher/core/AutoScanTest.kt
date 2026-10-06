package com.bookcovermatcher.core

import com.bookcovermatcher.core.autoscan.AutoScanConfig
import com.bookcovermatcher.core.autoscan.AutoScanEngine
import com.bookcovermatcher.core.autoscan.FrameMetrics
import com.bookcovermatcher.core.autoscan.FrameSignature
import com.bookcovermatcher.core.autoscan.ScanPhase
import com.bookcovermatcher.core.vision.ImageOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AutoScanTest {

    private fun argb(g: Int) = (0xFF shl 24) or (g shl 16) or (g shl 8) or g

    /** A busy, well-lit "cover": random texture at 64x64, plus its 32x32 counterpart. */
    private class Frame(val px64: IntArray, val px32: IntArray)

    private fun texture(seed: Long, mean: Int = 128, amp: Int = 100): Frame {
        val rnd = Random(seed)
        val big = IntArray(128 * 128) { argb((mean + (rnd.nextDouble() - 0.5) * 2 * amp).toInt().coerceIn(0, 255)) }
        return Frame(ImageOps.areaResizeArgb(big, 128, 128, 64, 64), ImageOps.areaResizeArgb(big, 128, 128, 32, 32))
    }

    private fun flat(g: Int) = Frame(IntArray(64 * 64) { argb(g) }, IntArray(32 * 32) { argb(g) })

    // ---- metrics (values cross-checked against the original JS in the parity run) ----

    @Test fun flatFrameHasZeroBlurAndExactMeanLuminance() {
        val q = FrameMetrics.analyze(flat(100).px64, 64)
        assertEquals(0f, q.blur, 1e-6f)
        assertEquals(100f, q.meanLum, 1e-3f)
    }

    @Test fun texturedFrameIsSharp() {
        assertTrue(FrameMetrics.analyze(texture(1).px64, 64).blur > 100f)
    }

    @Test fun signatureIsDeterministicAndDistinguishesCovers() {
        val a = FrameSignature.compute(texture(1).px32)
        assertEquals(a, FrameSignature.compute(texture(1).px32))
        assertTrue(FrameSignature.distance(a, FrameSignature.compute(texture(2).px32)) > 8)
        assertEquals(0, FrameSignature.distance(a, a))
    }

    // ---- engine ----

    private fun run(e: AutoScanEngine, f: Frame, now: Long) = e.step(now, f.px64, f.px32)

    @Test fun capturesOnTheFourthGoodSteadyFrame() {
        val e = AutoScanEngine()
        val f = texture(1)
        val phases = (0..3).map { run(e, f, it * 400L) }
        assertEquals(
            listOf(ScanPhase.STABILIZING, ScanPhase.STABILIZING, ScanPhase.STABILIZING, ScanPhase.CAPTURED),
            phases.map { it.phase },
        )
        assertEquals(listOf(0, 1, 2, 3), phases.map { it.stableCount })
        assertEquals(listOf(false, false, false, true), phases.map { it.capture })
    }

    @Test fun movingFramesNeverStabilise() {
        val e = AutoScanEngine()
        val results = (0..9).map { run(e, texture(it.toLong()), it * 400L) }
        assertTrue(results.none { it.capture })
        assertTrue(results.all { it.stableCount == 0 })
    }

    @Test fun cooldownThenSameCoverIsNotCapturedTwice() {
        val e = AutoScanEngine()
        val f = texture(1)
        var t = 0L
        repeat(4) { run(e, f, t); t += 400 }                       // captured at t = 1200
        assertTrue(e.isCoolingDown(1300))
        assertEquals(ScanPhase.CAPTURED, e.cooldownTick().phase)
        assertFalse(e.isCoolingDown(1200 + 1500))
        t = 1200 + 1500
        val phases = (0..3).map { run(e, f, t + it * 400L) }
        assertEquals(ScanPhase.SAME_COVER, phases.last().phase)
        assertFalse(phases.any { it.capture })
        assertEquals(ScanPhase.SAME_COVER, e.cooldownTick().phase)
    }

    @Test fun aDifferentCoverIsAcceptedAfterTheCooldown() {
        val e = AutoScanEngine()
        var t = 0L
        repeat(4) { run(e, texture(1), t); t += 400 }
        t += 1500
        val phases = (0..3).map { run(e, texture(99), t + it * 400L) }
        assertTrue(phases.last().capture)
    }

    @Test fun leavingAndReturningRearmsTheSameCover() {
        val e = AutoScanEngine()
        var t = 0L
        repeat(4) { run(e, texture(1), t); t += 400 }
        t += 1500
        repeat(5) { assertEquals(ScanPhase.TOO_DARK, run(e, texture(3, mean = 20, amp = 15), t).phase); t += 400 }   // cover removed
        val phases = (0..3).map { run(e, texture(1), t + it * 400L) }
        assertTrue(phases.last().capture)
    }

    @Test fun qualityGatesReportWhyTheyFail() {
        val e = AutoScanEngine()
        // sharp but dark / bright textures report the lighting problem ...
        assertEquals(ScanPhase.TOO_DARK, run(e, texture(3, mean = 20, amp = 15), 0).phase)
        assertEquals(ScanPhase.TOO_BRIGHT, run(e, texture(5, mean = 240, amp = 14), 400).phase)
        // ... a featureless frame fails the blur gate first (same check order as v1)
        assertEquals(ScanPhase.HOLD_STILL, run(e, flat(128), 800).phase)
        assertEquals(ScanPhase.HOLD_STILL, run(e, flat(10), 1200).phase)
    }

    @Test fun oneBadFrameIsForgivenButTwoResetTheStreak() {
        val f = texture(1)
        val forgiving = AutoScanEngine(AutoScanConfig(graceFrames = 1))
        run(forgiving, f, 0); run(forgiving, f, 400); run(forgiving, f, 800)      // stableCount == 2
        run(forgiving, flat(10), 1200)                                            // one bad frame
        assertTrue(run(forgiving, f, 1600).capture)                               // streak survived -> 3 steady -> capture

        val strict = AutoScanEngine(AutoScanConfig(graceFrames = 0))              // v1 behaviour
        run(strict, f, 0); run(strict, f, 400); run(strict, f, 800)
        run(strict, flat(10), 1200)
        assertEquals(0, run(strict, f, 1600).stableCount)

        val twoBad = AutoScanEngine(AutoScanConfig(graceFrames = 1))
        run(twoBad, f, 0); run(twoBad, f, 400); run(twoBad, f, 800)
        run(twoBad, flat(10), 1200); run(twoBad, flat(10), 1600)
        assertEquals(0, run(twoBad, f, 2000).stableCount)
    }

    @Test fun v1ThresholdsAreTheDefaults() {
        val c = AutoScanConfig()
        assertEquals(400L, c.intervalMs); assertEquals(3, c.stableFrames); assertEquals(4, c.stableDistance)
        assertEquals(8, c.duplicateDistance); assertEquals(1500L, c.cooldownMs)
        assertEquals(40f, c.blurThreshold); assertEquals(45f, c.darkThreshold); assertEquals(215f, c.brightThreshold)
        assertEquals(5, c.resetBadFrames)
    }
}
