package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.BookmarkPresentation
import com.bookcovermatcher.core.wishlist.MatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lines a saved bookmark card shows.
 *
 * These are the exact values the Wishlist card and the Search card print, so the tests double as the
 * contract between the two: artists and the circle never merge, a missing value is `—`, and the
 * match chip uses the Search card's wording.
 */
class BookmarkPresentationTest {

    @Test fun realArtistsAreJoinedAndTheCircleStaysOut() {
        val line = BookmarkPresentation.authorLine(listOf("原作者", "第二作者"), circle = "榎屋")

        // The circle is stored separately and must not leak into the artist line.
        assertEquals("原作者, 第二作者", line)
        assertEquals("原作者, 第二作者", BookmarkPresentation.artistLine(listOf("原作者", "第二作者")))
    }

    @Test fun aCircleIsTheDisplayFallbackForAShopListingWithNoParsedArtist() {
        assertEquals("榎屋", BookmarkPresentation.authorLine(emptyList(), circle = "榎屋"))
        // Display fallback only: there is no artist row, so the card cannot show the circle twice.
        assertNull(BookmarkPresentation.artistLine(emptyList()))
    }

    @Test fun aRecordWithoutArtistOrCircleSaysSo() {
        assertEquals(BookmarkPresentation.MISSING, BookmarkPresentation.authorLine(emptyList(), circle = null))
        assertEquals(BookmarkPresentation.MISSING, BookmarkPresentation.authorLine(listOf(" ", ""), circle = "  "))
    }

    @Test fun blankArtistRowsAreDroppedAndDuplicatesCollapse() {
        assertEquals("eno", BookmarkPresentation.authorLine(listOf("eno", "  ", "eno"), circle = null))
    }

    @Test fun theCaptionPrefersThePriceThenTheIdentifier() {
        assertEquals("770円", BookmarkPresentation.metaLine("770円", "RJ01266914"))
        assertEquals("RJ01266914", BookmarkPresentation.metaLine(null, "RJ01266914"))
        assertEquals("RJ01266914", BookmarkPresentation.metaLine("   ", "RJ01266914"))
        assertEquals(BookmarkPresentation.MISSING, BookmarkPresentation.metaLine(null, null))
    }

    @Test fun theArtistRowIsNullUnlessThereIsARealArtist() {
        assertEquals("eno", BookmarkPresentation.artistLine(listOf("eno")))
        assertNull(BookmarkPresentation.artistLine(emptyList()))
    }

    @Test fun theSourceChipFallsBackFromProviderToDomainToKind() {
        assertEquals("DLsite", BookmarkPresentation.sourceLabel("DLsite", "dlsite.com", "Online result"))
        assertEquals("dlsite.com", BookmarkPresentation.sourceLabel(null, "dlsite.com", "Online result"))
        assertEquals("Custom bookmark", BookmarkPresentation.sourceLabel(" ", null, "Custom bookmark"))
    }

    @Test fun theMatchChipUsesTheSearchCardsWording() {
        assertEquals("Text match", BookmarkPresentation.matchTypeLabel(MatchType.TEXT_QUERY, verified = true))
        assertEquals("Workbook match", BookmarkPresentation.matchTypeLabel(MatchType.WORKBOOK, verified = true))
        assertEquals("Manual", BookmarkPresentation.matchTypeLabel(MatchType.MANUAL, verified = false))
        assertEquals("Visual match", BookmarkPresentation.matchTypeLabel(MatchType.VISUAL, verified = true))
    }

    @Test fun anUnverifiedRecordNeverClaimsAMatchItDidNotMake() {
        assertEquals(
            "Unverified match",
            BookmarkPresentation.matchTypeLabel(MatchType.TEXT_QUERY, verified = false),
        )
        assertEquals(
            "Unverified match",
            BookmarkPresentation.matchTypeLabel(MatchType.VISUAL, verified = false),
        )
    }
}
