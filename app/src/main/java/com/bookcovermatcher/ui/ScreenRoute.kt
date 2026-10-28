package com.bookcovermatcher.ui

/**
 * Which full-screen surface the app is showing — a pure function of the navigation flags, so the
 * precedence is testable without Compose.
 *
 * Order matters: a screen opened *from* the library must win over the library, otherwise the library
 * keeps rendering and the destination only appears once Back clears it. The library therefore stays
 * "open" underneath (Back returns to it) while [COMMENTS] is above it.
 */
enum class AppRoute { COMPARISON, COMMENTS, LIBRARY, MAIN }

fun routeFor(comparisonOpen: Boolean, commentsOpen: Boolean, libraryOpen: Boolean): AppRoute = when {
    comparisonOpen -> AppRoute.COMPARISON
    commentsOpen -> AppRoute.COMMENTS
    libraryOpen -> AppRoute.LIBRARY
    else -> AppRoute.MAIN
}
