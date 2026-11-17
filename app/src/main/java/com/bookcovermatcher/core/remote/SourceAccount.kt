// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core.remote

/** Provider-issued identity; labels are display-only and never used for matching. */
data class SourceAccount(val key: String, val label: String) {
    override fun toString() = "SourceAccount(***)"
}
