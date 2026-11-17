// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.bookcovermatcher.BuildConfig
import com.bookcovermatcher.core.remote.*
import com.bookcovermatcher.data.onedrive.OneDriveAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.*
import net.openid.appauth.AuthState as OAuthState
import java.util.UUID

/** Local prototype: credentials live only in this process, never in preferences or backups. */
class BrowserAuthSession internal constructor(val provider: String) : OneDriveAuth {
    private val mutableState = MutableStateFlow<com.bookcovermatcher.core.remote.AuthState>(com.bookcovermatcher.core.remote.AuthState.SignedOut)
    override val state = mutableState.asStateFlow()
    private var oauth = OAuthState()
    private var service: AuthorizationService? = null
    private var pending: CompletableDeferred<com.bookcovermatcher.core.remote.AuthState>? = null
    internal var attempt: String? = null
        private set
    private val refreshLock = Mutex()
    private var epoch = 0L
    val generation: Long get() = epoch
    var account: SourceAccount? = null
        private set
    private val identityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal fun request(): AuthorizationRequest? {
        val google = provider == "google"
        val client = if (google) BuildConfig.GOOGLE_OAUTH_CLIENT_ID else BuildConfig.MICROSOFT_OAUTH_CLIENT_ID
        if (client.isBlank() || client.startsWith("00000000")) return null
        val redirect = if (google) BuildConfig.GOOGLE_OAUTH_REDIRECT else BuildConfig.MICROSOFT_OAUTH_REDIRECT
        val config = if (google) AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"), Uri.parse("https://oauth2.googleapis.com/token"),
        ) else AuthorizationServiceConfiguration(
            Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),
            Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"),
        )
        return AuthorizationRequest.Builder(config, client, ResponseTypeValues.CODE, Uri.parse(redirect))
            .setScope(if (google) "https://www.googleapis.com/auth/drive.readonly" else "https://graph.microsoft.com/Files.Read https://graph.microsoft.com/User.Read offline_access")
            .setPrompt(if (google) "consent select_account" else "select_account")
            .apply { if (google) setAdditionalParameters(mapOf("access_type" to "offline")) }
            .build() // AppAuth generates a random state and S256 PKCE verifier/challenge.
    }

    override suspend fun signIn(activity: Activity?): com.bookcovermatcher.core.remote.AuthState = withContext(Dispatchers.Main.immediate) {
        if (activity == null || request() == null) return@withContext com.bookcovermatcher.core.remote.AuthState.Failed(AuthFailure.CONFIGURATION.toRemoteFailure())
        if (pending != null) return@withContext com.bookcovermatcher.core.remote.AuthState.Failed(RemoteFailure.SignInNotCompleted)
        val ticket = UUID.randomUUID().toString()
        val result = CompletableDeferred<com.bookcovermatcher.core.remote.AuthState>()
        pending = result
        attempt = ticket
        try {
            activity.startActivity(Intent(activity, BrowserAuthActivity::class.java).putExtra("provider", provider).putExtra("attempt", ticket))
            withTimeoutOrNull(5 * 60 * 1000L) { result.await() }
                ?: com.bookcovermatcher.core.remote.AuthState.Failed(RemoteFailure.SignInNotCompleted)
        } finally {
            if (attempt == ticket) { attempt = null; pending = null }
        }
    }

    internal fun complete(ticket: String, response: AuthorizationResponse?, error: AuthorizationException?, context: android.content.Context) {
        if (ticket != attempt) return
        if (response == null || error != null) {
            pending?.complete(if (error == null || error.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code)
                com.bookcovermatcher.core.remote.AuthState.SignedOut
                else com.bookcovermatcher.core.remote.AuthState.Failed(AuthFailure.CONSENT_DECLINED.toRemoteFailure()))
            return
        }
        val candidate = OAuthState(response, null)
        val authService = AuthorizationService(context.applicationContext)
        authService.performTokenRequest(response.createTokenExchangeRequest()) { token, failure ->
            if (ticket != attempt) { authService.dispose(); return@performTokenRequest }
            candidate.update(token, failure)
            if (failure == null && candidate.isAuthorized) {
                oauth = candidate
                service?.dispose()
                service = authService
                epoch++
                account = null
                mutableState.value = com.bookcovermatcher.core.remote.AuthState.SignedIn(null)
                val generation = epoch
                identityScope.launch {
                    val identity = try { readAccountIdentity(provider, token!!.accessToken!!) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    if (generation != epoch || ticket != attempt) return@launch
                    account = identity
                    mutableState.value = com.bookcovermatcher.core.remote.AuthState.SignedIn(identity?.label)
                    pending?.complete(mutableState.value)
                }
            } else {
                authService.dispose()
                pending?.complete(com.bookcovermatcher.core.remote.AuthState.Failed(AuthFailure.SERVICE_ERROR.toRemoteFailure()))
            }
        }
    }

    fun cancelPending() {
        attempt = null
        pending?.complete(com.bookcovermatcher.core.remote.AuthState.SignedOut)
        pending = null
    }

    override suspend fun accessToken(): TokenResult = withContext(Dispatchers.Main.immediate) {
        refreshLock.withLock {
            val authService = service ?: return@withLock TokenResult.Failure(AuthFailure.INTERACTION_REQUIRED)
            val generation = epoch
            val fresh = CompletableDeferred<TokenResult>()
            oauth.performActionWithFreshTokens(authService) { token, _, error ->
                if (generation != epoch) fresh.complete(TokenResult.Failure(AuthFailure.INTERACTION_REQUIRED))
                else if (error == null && !token.isNullOrBlank()) fresh.complete(TokenResult.Success(AccessToken.of(token)))
                else {
                    mutableState.value = com.bookcovermatcher.core.remote.AuthState.Expired(account?.label)
                    fresh.complete(TokenResult.Failure(AuthFailure.INTERACTION_REQUIRED))
                }
            }
            withTimeoutOrNull(60_000) { fresh.await() } ?: TokenResult.Failure(AuthFailure.NETWORK)
        }
    }

    fun clear() {
        account = null
        epoch++
        attempt = null
        pending?.complete(com.bookcovermatcher.core.remote.AuthState.SignedOut)
        pending = null
        oauth = OAuthState()
        service?.dispose()
        service = null
        mutableState.value = com.bookcovermatcher.core.remote.AuthState.SignedOut
    }
    override suspend fun signOut() = withContext(Dispatchers.Main.immediate) { clear() }
    override suspend fun refreshState() = Unit
}

object BrowserAuthSessions {
    val google = BrowserAuthSession("google")
    val microsoft = BrowserAuthSession("microsoft")
    fun find(provider: String?) = when (provider) { "google" -> google; "microsoft" -> microsoft; else -> null }
}
