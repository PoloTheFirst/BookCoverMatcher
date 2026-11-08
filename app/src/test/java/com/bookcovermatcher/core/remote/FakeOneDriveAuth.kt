// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import android.app.Activity
import com.bookcovermatcher.data.onedrive.OneDriveAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scripted [OneDriveAuth] for deterministic tests (v2.6.0-dev.1 Phase 3).
 *
 * It is the seam that lets "signed out", "expired" and "cannot be configured" be *values* a test holds
 * rather than situations it hopes for — and it is what the Graph layer is faked with, because the code
 * under test only ever consumes [accessToken].
 *
 * [signIn] takes an `android.app.Activity` it never touches: the fake records that a sign-in was asked
 * for and moves to [signInState]. The type is only there because the interface must match MSAL's need
 * for a real Activity.
 */
class FakeOneDriveAuth(
    initialState: AuthState = AuthState.SignedOut,
) : OneDriveAuth {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** What [accessToken] answers. Default: nobody is signed in. */
    var tokenResult: TokenResult = TokenResult.Failure(AuthFailure.INTERACTION_REQUIRED)

    /** What [signIn] ends with. Default: a signed-in personal account. */
    var signInState: AuthState = AuthState.SignedIn("someone@example.com")

    var tokenRequests: Int = 0
        private set
    var signInRequests: Int = 0
        private set
    var signOutRequests: Int = 0
        private set

    override suspend fun signIn(activity: Activity?): AuthState {
        signInRequests++
        _state.value = signInState
        return signInState
    }

    override suspend fun signOut() {
        signOutRequests++
        _state.value = AuthState.SignedOut
    }

    override suspend fun refreshState() = Unit

    override suspend fun accessToken(): TokenResult {
        tokenRequests++
        return tokenResult
    }
}
