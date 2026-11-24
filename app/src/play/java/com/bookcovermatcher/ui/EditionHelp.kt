// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.runtime.Composable

@Composable
internal fun EditionHelp() {
    InfoLine("OCR", "PaddleOCR reads cover text on device. Select Chrome Lens to send the cover to Google for OCR.")
    InfoLine("Saved library", "Existing catalogue results are retained in storage but unavailable in this edition. Workbook matches and reverse-image results remain available.")
}
