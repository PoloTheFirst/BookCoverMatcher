package com.bookcovermatcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Navigation precedence: a screen opened from the library must win over the library (Bug 1). */
class ScreenRouteTest {

    @Test fun openingFromTheLibraryShowsTheWorkbookImmediately() {
        // Both flags are true right after "Open": the preview must be on top, not the library.
        assertEquals(AppRoute.COMMENTS, routeFor(comparisonOpen = false, commentsOpen = true, libraryOpen = true))
    }

    @Test fun closingThePreviewReturnsToTheLibrary() {
        assertEquals(AppRoute.LIBRARY, routeFor(comparisonOpen = false, commentsOpen = false, libraryOpen = true))
    }

    @Test fun theComparisonWinsOverTheLibraryItWasOpenedFrom() {
        assertEquals(AppRoute.COMPARISON, routeFor(comparisonOpen = true, commentsOpen = false, libraryOpen = true))
    }

    @Test fun nothingOpenFallsBackToTheMainTabs() {
        assertEquals(AppRoute.MAIN, routeFor(comparisonOpen = false, commentsOpen = false, libraryOpen = false))
    }

    @Test fun thePreviewOpenedFromMoreReturnsToTheMainTabs() {
        assertEquals(AppRoute.COMMENTS, routeFor(comparisonOpen = false, commentsOpen = true, libraryOpen = false))
        assertEquals(AppRoute.MAIN, routeFor(comparisonOpen = false, commentsOpen = false, libraryOpen = false))
    }
}
