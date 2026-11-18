// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins.groups

import com.bookcovermatcher.core.online.*
import com.bookcovermatcher.plugins.PlatformGroup
import com.bookcovermatcher.online.OpenLibraryBooksSource
import com.bookcovermatcher.online.GoogleBooksSource

internal class OrdinaryLibraryGroup : PlatformGroup(
    id = "bcm.ordinary-library",
    index = 0,
    name = "Ordinary Library Fetch",
    description = "Search Open Library and Google Books using your catalogue query.",
    entries = listOf(
        ProviderSpec(
            id = OpenLibraryResponseParser.PROVIDER_ID,
            name = OpenLibraryResponseParser.SOURCE_NAME,
            host = "openlibrary.org",
            access = ProviderAccess.AUTOMATED,
            note = "Library catalogue. Public Books API, no key.",
            searchTemplate = "https://openlibrary.org/search?q={q}",
            landingUrl = "https://openlibrary.org/search",
        ),
        ProviderSpec(
            id = GoogleBooksResponseParser.PROVIDER_ID,
            name = GoogleBooksResponseParser.SOURCE_NAME,
            host = "google.com",
            access = ProviderAccess.AUTOMATED,
            note = "Book catalogue. Public Volumes API, no key; can answer 429.",
            searchTemplate = "https://www.google.com/search?tbm=bks&q={q}",
            landingUrl = "https://books.google.com/",
        ),
    ),
    createSources = { listOf(OpenLibraryBooksSource(), GoogleBooksSource()) },
)
