// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.runtime.Composable

@Composable
internal fun EditionHelp() {
    InfoLine("OCR", "PaddleOCR reads cover text on device. No catalogue search is performed.")
    InfoLine("Reverse-image search", "SauceNAO uses your API key and selected cover. IQDB opens in your browser; results can be pasted back.")
    InfoLine("Saved library", "Existing catalogue results are retained in storage but unavailable in this edition. Workbook matches and reverse-image results remain available.")
}
