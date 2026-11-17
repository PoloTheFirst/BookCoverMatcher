// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.BookmarkForm
import org.junit.Assert.*
import org.junit.Test

class GithubBookmarkExamplesTest {
    @Test fun theFolderAndBookmarkPlaceholdersAreTheExactSamples() {
        assertEquals("e.g. ブルアカ", BookmarkForm.FOLDER_NAME_PLACEHOLDER)
        assertEquals("みつ", BookmarkForm.TITLE_PLACEHOLDER)
        assertEquals(
            "https://www.dlsite.com/maniax-touch/work/=/product_id/RJ01266914.html",
            BookmarkForm.URL_PLACEHOLDER,
        )
    }

    @Test fun theArtistCircleAndPricePlaceholdersAreTheExactSamples() {
        assertEquals("eno", BookmarkForm.ARTIST_PLACEHOLDER)
        assertEquals("榎屋", BookmarkForm.CIRCLE_PLACEHOLDER)
        assertEquals("770円", BookmarkForm.PRICE_PLACEHOLDER)
        assertEquals("先生がナギサ&サクラコとセックスします。", BookmarkForm.NOTES_PLACEHOLDER)
    }

}
