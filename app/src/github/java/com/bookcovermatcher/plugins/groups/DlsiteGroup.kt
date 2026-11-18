// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins.groups

import com.bookcovermatcher.core.online.*
import com.bookcovermatcher.plugins.PlatformGroup
import com.bookcovermatcher.online.DlsiteSource

internal class DlsiteGroup : PlatformGroup(
    id = "bcm.dlsite",
    index = 1,
    name = "DLsite",
    description = "A search sends your query to the DLsite books catalogue. Results may include mature listings.",
    entries = listOf(
        ProviderSpec(
            id = DlsiteResponseParser.PROVIDER_ID,
            name = DlsiteResponseParser.SOURCE_NAME,
            host = "dlsite.com",
            access = ProviderAccess.AUTOMATED,
            note = "Doujinshi and digital works. Public books search page, first page only.",
            searchTemplate = "https://www.dlsite.com/books/fsr/=/keyword/{q}",
            landingUrl = "https://www.dlsite.com/books/",
            // Measured 2026-10-23: `keyword/Blue%20Archive` -> 403, `keyword/Blue+Archive` -> 200 (30 works).
            queryForm = QueryForm.PLUS,
        ),
    ),
    createSources = { listOf(DlsiteSource()) },
)
