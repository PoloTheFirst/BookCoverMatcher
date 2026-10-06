package com.bookcovermatcher.core.autoscan

/** The camera badge texts of v1 (`setBadge(...)` calls in `js/auto-scan.js`), unchanged. */
object ScanBadge {
    const val AUTO_ON = "Auto-scan on"
    const val AUTO_ARMED = "Auto-scan armed"
    const val LIVE = "Live"
    const val TAP_TO_START = "Tap Scan to start"

    fun text(t: ScanTick): String = when (t.phase) {
        ScanPhase.HOLD_STILL -> "Hold still…"
        ScanPhase.TOO_DARK -> "Too dark"
        ScanPhase.TOO_BRIGHT -> "Too bright"
        ScanPhase.STABILIZING -> "Stabilizing… ${t.stableCount}/${t.stableTarget}"
        ScanPhase.SAME_COVER -> "Same cover — waiting"
        ScanPhase.CAPTURED -> "Captured ✓"
    }
}
