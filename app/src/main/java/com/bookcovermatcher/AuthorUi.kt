// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher

import androidx.compose.ui.graphics.ImageBitmap
import com.bookcovermatcher.core.author.AuthorSource
import com.bookcovermatcher.core.author.SmilAnimation

/**
 * One picture the Author page can draw, with the shape it must reserve so the layout does not jump.
 *
 * [animation] is non-null only when the source declares SMIL animation this app can play (the Pacman contribution
 * graph); [bitmap] is then its first frame, so a caller that does not animate still draws something truthful.
 */
data class AuthorImageUi(
    val bitmap: ImageBitmap,
    val aspectRatio: Float,
    val animation: SmilAnimation? = null,
)

/**
 * The Author page's state (v2.6.0 amendment).
 *
 * [markdown] is the README currently on screen — the cached copy, the bundled copy, or a freshly fetched one —
 * and [images] holds the pictures decoded for the URLs that README references. [source] says which of those it is,
 * so the panel can tell the user truthfully where the text came from without pretending a refresh succeeded.
 */
data class AuthorUi(
    val markdown: String = "",
    val source: AuthorSource = AuthorSource.BUNDLED,
    val images: Map<String, AuthorImageUi> = emptyMap(),
    val refreshing: Boolean = false,
    val refreshedAt: Long? = null,
    val lastFailure: String? = null,
) {

    /** True when there is something to draw, so the panel never shows an empty card. */
    val hasContent: Boolean get() = markdown.isNotBlank()

    /** How many of the referenced pictures are ready. Used by tests and the status line. */
    val imageCount: Int get() = images.size
}
