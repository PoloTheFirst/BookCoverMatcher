// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/** Fixed at build time; restored preferences cannot change the distribution's capabilities. */
object DistributionEdition {
    const val ocrPreferenceKey = "play_ocr_engine"
    const val defaultOcrEngine = "rust"
    const val catalogueEnabled = false
    const val label = "Play"
}
