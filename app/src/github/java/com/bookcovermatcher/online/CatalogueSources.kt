// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.ProductSearchDataSource

internal object CatalogueSources {
    fun create(): List<ProductSearchDataSource> = listOf(OpenLibraryBooksSource(), GoogleBooksSource())
}
