// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/** Fixed at build time; restored preferences cannot change the distribution's capabilities. */
object DistributionEdition {
    const val ocrPreferenceKey = "online_ocr_engine"
    const val defaultOcrEngine = "python"
    const val catalogueEnabled = true
    const val label = "GitHub"
}
