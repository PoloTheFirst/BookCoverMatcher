// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.runtime.Composable

@Composable
internal fun EditionHelp() {
    InfoLine("OCR", "Choose PaddleOCR to read cover text on device or Chrome Lens to send the cover to Google for text recognition.")
    InfoLine("Online search", "Choose catalogue groups in Plugins, then select their platforms in Search.")
    InfoLine("Shortcuts", "Shortcut groups open the selected platform in your browser. Doujinshi.info is part of Explicit Database Shortcuts.")
    InfoLine("Reverse-image search", "With Reverse Image Discovery enabled, SauceNAO uses your API key. IQDB opens in your browser and accepts pasted results.")
    InfoLine("Prices / stock", "Only a DLsite listing's own current price is shown. No provider sends stock, so none is invented.")
}
