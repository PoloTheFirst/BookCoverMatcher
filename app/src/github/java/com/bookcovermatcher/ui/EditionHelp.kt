// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.runtime.Composable

@Composable
internal fun EditionHelp() {
            InfoLine("OCR", "Google Lens through chrome-lens-py, inside the app. Text only.")
            InfoLine("Online search", "Selected catalogues receive the search text. Import and enable DLsite in Plugins to include it.")
            InfoLine("Providers", "Eight cards are browser-only shortcuts. Every card has its own browser link and never changes the selection.")
            InfoLine("Providers not queried", "Melonbooks (product decision), Suruga-ya (robots.txt disallows /search/), Doujin no Mori (robots.txt disallows /comics/free-search), E-Hentai and nhentai (adult sites; robots/Cloudflare block automated access), Doujinshi.info (answers HTTP 403 to automated requests), MyAnimeList (no unauthenticated API) and Toranoana (no documented public API; 18+ catalogue). Their cards open the site's own search page and can never be selected.")
            InfoLine("Reverse-image search", "SauceNAO uses your API key; IQDB is a browser handoff with optional pasted results.")
            InfoLine("Prices / stock", "Only a DLsite listing's own current price is shown. No provider sends stock, so none is invented.")
}
