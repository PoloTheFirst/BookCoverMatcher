// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

/**
 * Joins neighbouring badge images so they share one line (v2.6.0 crash-fix 1).
 *
 * The profile README lists thirteen Shields.io badges inside one `<div align="center">`. The block parser turns
 * each `<img>` into its own image block, and a reader that draws one block per row therefore stacks them down the
 * page — which is the other half of "the badge is far too large": even a correctly sized badge looks wrong when it
 * owns a row.
 *
 * This grouping is presentation-neutral and pure: it returns the same blocks, in the same order, but with runs of
 * consecutive badges collected so the caller can lay each run out as a wrapping row. Everything that is not a
 * badge is returned as a group of one, so the caller draws it exactly as before.
 */
object MdImageGrouping {

    /**
     * Groups [blocks] into runs of consecutive images for which [isBadge] is true.
     *
     * A group of more than one block is a badge run; every other block is a group of exactly one, in document
     * order, so no content is dropped or reordered.
     */
    fun group(blocks: List<MdBlock>, isBadge: (String) -> Boolean): List<List<MdBlock>> {
        val out = mutableListOf<List<MdBlock>>()
        var run = mutableListOf<MdBlock>()
        fun flushRun() {
            if (run.isNotEmpty()) {
                out += run.toList()
                run = mutableListOf()
            }
        }
        for (block in blocks) {
            val badge = block is MdBlock.Image && isBadge(block.url)
            if (badge) {
                run += block
            } else {
                flushRun()
                out += listOf(block)
            }
        }
        flushRun()
        return out
    }
}
