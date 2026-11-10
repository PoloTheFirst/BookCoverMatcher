// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.bookcovermatcher.core.remote.GoogleAuthorizationErrors
import com.bookcovermatcher.ui.AppScreen
import com.bookcovermatcher.ui.theme.BookCoverTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var googleAuthorizationGeneration = 0L
    private var pendingGoogleGeneration = 0L

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (pendingGoogleGeneration != googleAuthorizationGeneration) return@registerForActivityResult
        // A non-OK Activity result can still carry Google's actual failure status.
        // Parse that payload before deciding what happened; cancellation alone is not proof of denial.
        Log.i("GoogleDriveAuth", "consentResult=${result.resultCode} hasData=${result.data != null}")
        if (result.data == null) {
            viewModel.googleDriveAuthorizationFailed(
                "Google sign-in closed without returning a result (Android code ${result.resultCode}). " +
                    "Access was not confirmed. Try again; if it repeats, report this code and your Google Play services version.",
                pendingGoogleGeneration,
            )
        } else try {
            viewModel.googleDriveAuthorized(
                Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken,
                pendingGoogleGeneration,
            )
        } catch (e: ApiException) { googleAuthorizationError(e, pendingGoogleGeneration) }
        catch (e: Exception) { googleAuthorizationError(e, pendingGoogleGeneration) }
    }

    fun authorizeGoogleDrive() {
        val generation = viewModel.googleDriveAuthorizationAttempt()
        googleAuthorizationGeneration = generation
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.readonly")))
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (generation != googleAuthorizationGeneration) return@addOnSuccessListener
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) viewModel.googleDriveAuthorizationFailed("Google authorization returned no consent screen. Try again.", generation)
                    else {
                        pendingGoogleGeneration = generation
                        googleConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    }
                } else viewModel.googleDriveAuthorized(result.accessToken, generation)
            }
            .addOnFailureListener { if (generation == googleAuthorizationGeneration) googleAuthorizationError(it, generation) }
    }

    private fun googleAuthorizationError(error: Exception, attempt: Long) {
        val code = (error as? ApiException)?.statusCode
        // Status codes are sufficient for support. Never log the Intent, exception text or token.
        Log.w("GoogleDriveAuth", "authorizationFailure status=${code ?: "unknown"}")
        val message = GoogleAuthorizationErrors.message(code)
        viewModel.googleDriveAuthorizationFailed(message, attempt)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("googleAuthGeneration", googleAuthorizationGeneration)
        outState.putLong("googlePendingGeneration", pendingGoogleGeneration)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        googleAuthorizationGeneration = savedInstanceState?.getLong("googleAuthGeneration") ?: 0L
        pendingGoogleGeneration = savedInstanceState?.getLong("googlePendingGeneration") ?: 0L
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            BookCoverTheme {
                AppScreen(viewModel, lifecycleOwner = this)
            }
        }
    }
}
