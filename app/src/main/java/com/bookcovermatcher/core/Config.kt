package com.bookcovermatcher.core

/**
 * Constants ported from `js/config.js` (v1.0.0). Values are unchanged unless noted.
 *
 * Everything in the `core` package is plain Kotlin/JVM (no `android.*` imports) so it can be
 * unit-tested on a desktop JVM.
 */
object Config {
    /** Guide rectangle as a fraction of the camera viewport (must match the on-screen guide). */
    const val GUIDE_W = 0.70f
    const val GUIDE_H = 0.78f

    const val THUMB_SCAN_W = 112
    const val THUMB_SCAN_H = 152
    const val THUMB_MATCH_W = 88
    const val THUMB_MATCH_H = 118

    /** v2: thumbnails are rendered at this multiple so they stay sharp on dense screens. */
    const val THUMB_PIXEL_SCALE = 2

    /** Max long edge when decoding a user-supplied image. */
    const val MAX_IMAGE_DECODE = 800

    /** Max long edge when decoding an image from the workbook. */
    const val MAX_BUFFER_DECODE = 640

    /** Hard cap on extracted floating (anchored) images per run. Same meaning as v1. */
    const val MAX_EXTRACTED_IMAGES = 300

    const val TOP_MATCHES = 5

    const val WISHLIST_PROMPT_MS = 5000L

    const val DEFAULT_RANGE = "A1:B20"

    /**
     * v1 read both cell-note ("comment-fill") pictures and anchored/floating pictures of the first
     * sheet. The port keeps that behaviour byte-for-byte. Set to `false` to read cell-note
     * pictures only.
     */
    const val EXTRACT_FLOATING_IMAGES = true
}
