// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.runtime.Composable

@Composable
internal fun EditionHelp() {
            InfoLine("OCR", "Google Lens through chrome-lens-py, inside the app. Text only.")
            InfoLine("Online search", "Import and enable a catalogue plugin in Plugins, then choose its platforms in Search.")
            InfoLine("Shortcuts", "Shortcut groups open the selected platform in your browser. Doujinshi.info is always available after the active groups.")
            InfoLine("Reverse-image search", "SauceNAO uses your API key; IQDB is a browser handoff with optional pasted results.")
            InfoLine("Prices / stock", "Only a DLsite listing's own current price is shown. No provider sends stock, so none is invented.")
}
