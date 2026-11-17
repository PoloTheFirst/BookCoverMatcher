// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.BookmarkForm
import org.junit.Assert.*
import org.junit.Test

class PlayBookmarkExamplesTest {
    @Test fun sampleLinkDoesNotPromoteADiscoveryProvider() {
        assertEquals("https://example.com/book", BookmarkForm.URL_PLACEHOLDER)
        assertTrue(BookmarkForm.validate(BookmarkForm.TITLE_PLACEHOLDER, BookmarkForm.URL_PLACEHOLDER).isEmpty())
    }

    @Test fun displayExamplesDescribeAPersonalReadingList() {
        assertEquals("e.g. Reading list", BookmarkForm.FOLDER_NAME_PLACEHOLDER)
        assertEquals("Personal reading notes", BookmarkForm.NOTES_PLACEHOLDER)
    }
}
