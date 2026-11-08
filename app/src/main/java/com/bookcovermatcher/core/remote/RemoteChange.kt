// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

/** Whether the OneDrive copy still matches the one the library saved. */
enum class RemoteChange { UNCHANGED, CHANGED, UNKNOWN }

/**
 * Compares the metadata the library recorded with what a probe just returned (v2.6.0-dev.1 Phase 5).
 *
 * Precedence is deliberate, strongest evidence first:
 *
 *  1. **ETag**, when both sides have one. It is exactly "this content changed", so it decides alone.
 *  2. **cTag**, when both sides have one and no ETag pair exists. A cTag tracks *content* changes
 *     specifically (an ETag also moves for metadata edits), so it is equally authoritative — but it is
 *     only ever compared with another cTag, never with an ETag.
 *  3. **Last-modified time**, when both sides have one. Equal times plus a known, different size still
 *     means changed: identical bytes cannot have two sizes.
 *  4. **Size alone** can only ever prove a *change* — two different sizes cannot be the same bytes — but
 *     two equal sizes prove nothing (a re-save can keep the size).
 *
 * When no pair is comparable the answer is [RemoteChange.UNKNOWN], never `UNCHANGED`: claiming "same" on
 * missing metadata would silently keep a stale workbook. Phase 5 says so plainly ("could not tell")
 * instead of downloading, and never counts it as a pass.
 *
 * Takes primitives rather than a `SavedWorkbook`, so the rule is pure, unit-tested, and reusable by any
 * caller that has the recorded values.
 */
object RemoteChangeDetector {

    fun compare(
        savedETag: String?,
        savedCTag: String?,
        savedLastModified: Long?,
        savedSizeBytes: Long?,
        item: RemoteItem,
    ): RemoteChange {
        val savedTag = savedETag?.takeIf { it.isNotBlank() }
        val remoteTag = item.eTag?.takeIf { it.isNotBlank() }
        if (savedTag != null && remoteTag != null) {
            return if (savedTag == remoteTag) RemoteChange.UNCHANGED else RemoteChange.CHANGED
        }

        val savedContentTag = savedCTag?.takeIf { it.isNotBlank() }
        val remoteContentTag = item.cTag?.takeIf { it.isNotBlank() }
        if (savedContentTag != null && remoteContentTag != null) {
            return if (savedContentTag == remoteContentTag) RemoteChange.UNCHANGED else RemoteChange.CHANGED
        }

        val savedTime = savedLastModified
        val remoteTime = item.lastModified
        if (savedTime != null && remoteTime != null) {
            if (savedTime != remoteTime) return RemoteChange.CHANGED
            val savedSize = savedSizeBytes
            val remoteSize = item.sizeBytes
            // Same clock reading, different size: something changed even though the timestamp says not.
            if (savedSize != null && remoteSize != null && savedSize != remoteSize) return RemoteChange.CHANGED
            return RemoteChange.UNCHANGED
        }

        val savedSize = savedSizeBytes
        val remoteSize = item.sizeBytes
        // Only a difference is evidence; equal sizes are not.
        if (savedSize != null && remoteSize != null && savedSize != remoteSize) return RemoteChange.CHANGED

        return RemoteChange.UNKNOWN
    }
}
