// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import android.app.Activity
import com.bookcovermatcher.core.remote.AccessTokenSource
import com.bookcovermatcher.core.remote.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Sign-in for the OneDrive import (v2.6.0-dev.1 Phase 3): personal Microsoft accounts, read-only.
 *
 * Extends [AccessTokenSource] so the Graph layer depends on the *token* and nothing else — list, probe
 * and download cannot start an interactive sign-in, cannot read an account name and cannot sign out.
 * Only the UI uses the rest, and it must pass the Activity it is running in.
 *
 * The implementation owns no token storage of its own: MSAL's encrypted cache is the only place a token
 * lives, and [AuthState] deliberately carries no token at all.
 */
interface OneDriveAuth : AccessTokenSource {

    /** What the UI may show. Never contains a token. */
    val state: StateFlow<AuthState>

    /**
     * Starts an interactive sign-in from [activity] (an Activity is what MSAL needs to launch the
     * browser and receive the redirect). Returns the resulting state; a cancelled sign-in is a state,
     * not an exception.
     */
    suspend fun signIn(activity: Activity?): AuthState

    /** Drops the account from MSAL's cache. Saved workbooks are never touched by this. */
    suspend fun signOut()

    /** Re-reads the cached account without any network call, so the UI can show who is signed in. */
    suspend fun refreshState()
}
