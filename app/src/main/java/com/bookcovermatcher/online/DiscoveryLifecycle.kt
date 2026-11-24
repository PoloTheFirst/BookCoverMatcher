// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

internal interface DiscoveryLifecycle {
    fun reset(note: String? = null)
    fun close()
}
