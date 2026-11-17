// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data

import com.bookcovermatcher.core.remote.*

data class SourceObservation(val text: String, val checkedAt: Long? = null, val verified: Boolean = false, val path: String? = null)

object WorkbookSourceStatus {
    fun failure(failure: RemoteFailure): String = when (failure) {
        RemoteFailure.AuthRequired -> "Sign in to check source"
        RemoteFailure.AuthExpired -> "Session expired"
        RemoteFailure.PermissionDenied -> "Permission denied"
        RemoteFailure.NotFound -> "File unavailable (missing or inaccessible)"
        RemoteFailure.Offline -> "Unable to check · offline"
        RemoteFailure.Timeout -> "Unable to check · timed out"
        is RemoteFailure.Throttled -> "Unable to check · try later"
        else -> "Unable to check · service unavailable"
    }

    fun accountProblem(entry: SavedWorkbook, account: SourceAccount?, signedIn: Boolean): String? {
        if (entry.origin == WorkbookOrigin.LOCAL) return null
        if (entry.remote == null) return "Unlinked"
        if (entry.remote.accountKey == null) return "Account unverified · confirm source account"
        if (!signedIn) return "Sign in to check source"
        if (account == null) return "Account identity unavailable"
        if (entry.remote.accountKey != account.key) return "Different account signed in"
        return null
    }
}
