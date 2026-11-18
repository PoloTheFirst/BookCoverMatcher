// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins.groups

import com.bookcovermatcher.core.online.*
import com.bookcovermatcher.plugins.PlatformGroup

internal class ExplicitDatabaseGroup : PlatformGroup(
    id = "bcm.explicit-database",
    index = 3,
    name = "Database shortcuts",
    description = "Open a selected database in your browser. Some sites contain explicit material.",
    entries = listOf(
        ProviderSpec(
            id = "nh",
            name = "nhentai",
            host = "nhentai.net",
            access = ProviderAccess.BROWSER_ONLY,
            note = "Adult gallery. Browser only: automated requests are refused (HTTP 403), age gate handled there.",
            searchTemplate = "https://nhentai.net/search/?q={q}",
            landingUrl = "https://nhentai.net/",
        ),
        ProviderSpec(
            id = "eh",
            name = "E-Hentai",
            host = "e-hentai.org",
            access = ProviderAccess.BROWSER_ONLY,
            note = "Adult doujin gallery. Browser only: opens the site's search page, age gate handled there.",
            searchTemplate = "https://e-hentai.org/?f_search={q}",
            landingUrl = "https://e-hentai.org/",
        ),
        ProviderSpec(
            id = "dm",
            name = "Doujin no Mori",
            host = "doujinnomori.com",
            access = ProviderAccess.BROWSER_ONLY,
            note = "Doujinshi shop. Browser only: its robots.txt disallows the /comics/free-search route.",
            searchTemplate = "https://doujinnomori.com/comics/free-search?keyword={q}",
            landingUrl = "https://doujinnomori.com/",
        ),
        ProviderSpec(
            id = "mal",
            name = "MyAnimeList",
            host = "myanimelist.net",
            access = ProviderAccess.BROWSER_ONLY,
            note = "Manga catalogue. Browser only: no unauthenticated API; its manga search opens in your browser.",
            searchTemplate = "https://myanimelist.net/manga.php?q={q}&cat=manga",
            landingUrl = "https://myanimelist.net/manga.php",
        ),
    ),
)
