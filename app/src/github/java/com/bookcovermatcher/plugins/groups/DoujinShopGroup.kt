// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins.groups

import com.bookcovermatcher.core.online.*
import com.bookcovermatcher.plugins.PlatformGroup

internal class DoujinShopGroup : PlatformGroup(
    id = "bcm.doujin-shop",
    index = 2,
    name = "Doujin Shop Shortcuts",
    description = "Open a selected shop in your browser. Some listings are intended for adults.",
    entries = listOf(
        ProviderSpec(
            id = "mb",
            name = "Melonbooks",
            host = "melonbooks.co.jp",
            access = ProviderAccess.BROWSER_ONLY,
            note = "Doujinshi shop. Browser only: opens the shop's own search page, never queried in-app.",
            searchTemplate = "https://www.melonbooks.co.jp/search/search.php?mode=search&name={q}",
            landingUrl = "https://www.melonbooks.co.jp/",
        ),
        ProviderSpec(
            id = "sy",
            name = "Suruga-ya",
            host = "suruga-ya.jp",
            access = ProviderAccess.BROWSER_ONLY,
            note = "Secondhand shop. Its robots.txt disallows /search/, so results open in your browser.",
            searchTemplate = "https://www.suruga-ya.jp/search?category=&search_word={q}",
            landingUrl = "https://www.suruga-ya.jp/",
        ),
        ProviderSpec(
            id = "tr",
            name = "Toranoana",
            host = "ec.toranoana.jp",
            access = ProviderAccess.BROWSER_ONLY,
            note = "Doujinshi shop, 18+ catalogue. Browser only: no documented public API, so its search opens in your browser.",
            searchTemplate = "https://ec.toranoana.jp/tora_r/ec/app/catalog/list?searchWord={q}",
            landingUrl = "https://ec.toranoana.jp/tora_r/ec/",
        ),
    ),
)
