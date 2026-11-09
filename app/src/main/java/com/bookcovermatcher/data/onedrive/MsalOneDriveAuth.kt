// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import android.app.Activity
import android.content.Context
import com.bookcovermatcher.R
import com.bookcovermatcher.core.remote.AccessToken
import com.bookcovermatcher.core.remote.AuthFailure
import com.bookcovermatcher.core.remote.AuthState
import com.bookcovermatcher.core.remote.MsalConfigParser
import com.bookcovermatcher.core.remote.MsalConfigResult
import com.bookcovermatcher.core.remote.MsalFailureKind
import com.bookcovermatcher.core.remote.MsalFailureRules
import com.bookcovermatcher.core.remote.TokenResult
import com.bookcovermatcher.core.remote.toRemoteFailure
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * MSAL implementation of [OneDriveAuth] (v2.6.0-dev.1 Phase 3).
 *
 * Design points that matter more than the code:
 *
 *  * **Public client, personal accounts only.** The configuration is the generated
 *    `res/raw/msal_config_default.json`; its `PersonalMicrosoftAccount` audience is what makes MSAL
 *    talk to `https://login.microsoftonline.com/consumers`. No tenant id and no client secret appears
 *    anywhere.
 *  * **The token cache is MSAL's.** Nothing here writes a token to a file, a preference or a log;
 *    [AuthState] carries an account name at most, and [AccessToken]'s `toString` is redacted.
 *  * **The configuration is validated before MSAL sees it**, so a build without a real `.env` fails
 *    with "sign-in is not configured" instead of an opaque MSAL parse error.
 *  * **Cancellation is a state, not an error.** Closing the browser page leaves the app signed out and
 *    silent.
 *  * **Expiry is distinguished from absence**: a silent acquisition that needs interaction marks the
 *    account [AuthState.Expired], so Phase 5 can say "sign in again" rather than "sign in".
 */
class MsalOneDriveAuth(
    context: Context,
    private val scopes: List<String> = DEFAULT_SCOPES,
) : OneDriveAuth {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Set when the packaged configuration is unusable, so every later call reports the same reason. */
    @Volatile
    private var configurationFailure: AuthFailure? = null

    @Volatile
    private var client: IMultipleAccountPublicClientApplication? = null

    override suspend fun signIn(activity: Activity?): AuthState {
        if (activity == null) return _state.value
        val pca = client() ?: return _state.value

        val settled = CompletableDeferred<Result<IAuthenticationResult>?>()
        val parameters = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes)
            .withCallback(
                object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        settled.complete(Result.success(authenticationResult))
                    }

                    override fun onError(exception: MsalException) {
                        settled.complete(Result.failure(exception))
                    }

                    override fun onCancel() {
                        // The user closed the page: not an error, just "still signed out".
                        settled.complete(null)
                    }
                },
            )
            .build()

        // The interactive flow launches an Activity, so it must start on the main thread.
        withContext(Dispatchers.Main.immediate) { pca.acquireToken(parameters) }

        val outcome = settled.await()
        val failure = outcome?.exceptionOrNull()?.let { failureOf(it) }
        val result = when {
            outcome == null -> AuthState.SignedOut
            outcome.isSuccess -> AuthState.SignedIn(usernameOf(outcome.getOrNull()?.account))
            failure == AuthFailure.INTERACTION_REQUIRED -> AuthState.Expired(null)
            // The user closed the page: still signed out, and deliberately not an error.
            failure == AuthFailure.CANCELLED -> AuthState.SignedOut
            // Everything else is reported with its own reason instead of being flattened to "cancelled"
            // (v2.6.0-dev.1 crash-fix-4).
            failure != null -> AuthState.Failed(failure.toRemoteFailure())
            else -> AuthState.SignedOut
        }
        // `Failed` is a one-shot result, never the stored state: the app really is signed out.
        _state.value = result as? AuthState.SignedIn ?: (result as? AuthState.Expired) ?: AuthState.SignedOut
        return result
    }

    override suspend fun accessToken(): TokenResult = withContext(Dispatchers.IO) {
        val pca = client()
            ?: return@withContext TokenResult.Failure(configurationFailure ?: AuthFailure.CONFIGURATION)
        val account = runCatching { pca.getAccounts().firstOrNull() }.getOrNull()
        if (account == null) {
            // An unreadable cache is the same situation as no account: sign in again.
            _state.value = AuthState.SignedOut
            return@withContext TokenResult.Failure(AuthFailure.INTERACTION_REQUIRED)
        }
        try {
            val result = pca.acquireTokenSilent(
                AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .withScopes(scopes)
                    .withCallback(
                        object : SilentAuthenticationCallback {
                            override fun onSuccess(authenticationResult: IAuthenticationResult) = Unit
                            override fun onError(exception: MsalException) = Unit
                        },
                    )
                    .build(),
            )
            _state.value = AuthState.SignedIn(usernameOf(result.account))
            TokenResult.Success(AccessToken.of(result.accessToken))
        } catch (e: MsalUiRequiredException) {
            // The cache knows the account but the service wants interaction (password change, MFA,
            // consent): "sign in again", not "sign in".
            _state.value = AuthState.Expired(usernameOf(account))
            TokenResult.Failure(AuthFailure.INTERACTION_REQUIRED)
        } catch (e: Exception) {
            TokenResult.Failure(failureOf(e))
        }
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        val pca = client()
        if (pca != null) {
            runCatching { pca.getAccounts().forEach { pca.removeAccount(it) } }
        }
        _state.value = AuthState.SignedOut
    }

    override suspend fun refreshState() = withContext(Dispatchers.IO) {
        val pca = client()
        val account = pca?.let { runCatching { it.getAccounts().firstOrNull() }.getOrNull() }
        _state.value = if (account != null) AuthState.SignedIn(usernameOf(account)) else AuthState.SignedOut
    }

    /**
     * Builds (once) the MSAL client, after checking the packaged configuration.
     *
     * Runs on whatever dispatcher the caller is on — `Dispatchers.IO` for the token paths and the main
     * thread for the interactive one — because MSAL's creation does blocking disk work either way and
     * the call sites already choose.
     */
    private fun client(): IMultipleAccountPublicClientApplication? {
        client?.let { return it }
        val configText = try {
            appContext.resources.openRawResource(R.raw.msal_config_default).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            configurationFailure = AuthFailure.CONFIGURATION
            return null
        }
        if (MsalConfigParser.parse(configText) !is MsalConfigResult.Valid) {
            configurationFailure = AuthFailure.CONFIGURATION
            return null
        }
        return try {
            PublicClientApplication
                .createMultipleAccountPublicClientApplication(appContext, R.raw.msal_config_default)
                .also { client = it }
        } catch (e: Exception) {
            // A configuration MSAL rejects is a configuration problem, never a network one.
            configurationFailure = AuthFailure.CONFIGURATION
            null
        }
    }

    /** The account's username, which is user-facing and never logged. Null when MSAL does not say. */
    private fun usernameOf(account: IAccount?): String? = account?.username?.takeIf { it.isNotBlank() }

    /**
     * MSAL exceptions carry messages that can include identifiers, so they are reduced to a code this
     * app's own vocabulary can describe — the raw message is dropped, not rethrown and not logged.
     *
     * The classification is deliberate (v2.6.0-dev.1 crash-fix-4): before it, everything that was not a
     * `MsalUiRequiredException` or an `IOException` became `UNKNOWN`, and `UNKNOWN` is reported to the
     * user as "the connection failed, try again when you are back online". A declined consent, an
     * unregistered redirect or a rejected client therefore looked exactly like being offline.
     */
    private fun failureOf(error: Throwable?): AuthFailure = when (error) {
        null -> MsalFailureRules.classify(MsalFailureKind.UNKNOWN)
        is MsalUserCancelException -> MsalFailureRules.classify(MsalFailureKind.CANCELLED)
        is MsalDeclinedScopeException -> MsalFailureRules.classify(MsalFailureKind.CONSENT_DECLINED)
        is MsalUiRequiredException -> MsalFailureRules.classify(MsalFailureKind.INTERACTION_REQUIRED)
        is MsalServiceException -> MsalFailureRules.classify(MsalFailureKind.SERVICE)
        is MsalClientException -> MsalFailureRules.classify(MsalFailureKind.CLIENT, error.errorCode)
        is IOException -> MsalFailureRules.classify(MsalFailureKind.NETWORK)
        is MsalException -> MsalFailureRules.classify(MsalFailureKind.UNKNOWN)
        else -> MsalFailureRules.classify(MsalFailureKind.UNKNOWN)
    }

    companion object {
        /**
         * Exactly the delegated Graph permissions this release needs: read the user's profile and read
         * their files. No write scope exists in this app, so none can be requested here.
         */
        val DEFAULT_SCOPES = listOf("Files.Read", "User.Read")
    }
}

