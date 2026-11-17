// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/** A presentation boundary only: never deletes or rewrites the user's saved library. */
object DistributionPolicy {
    fun showSavedItem(kind: String, provider: String?): Boolean =
        DistributionEdition.catalogueEnabled || kind != "ONLINE_RESULT" ||
            !provider.isNullOrBlank() && provider.split(',', '+', '·').all {
                it.trim().equals("SauceNAO", ignoreCase = true) || it.trim().equals("IQDB", ignoreCase = true)
            }
}
