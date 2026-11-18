// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.online

import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCatalogueSourcesTest {
    @Test fun buildRegistersNoCatalogueNetworkAdapters() {
        assertTrue(com.bookcovermatcher.plugins.editionGroups.isEmpty())
    }
}
