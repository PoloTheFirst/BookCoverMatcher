// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher

import android.graphics.Color
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import com.bookcovermatcher.ui.AppScreen
import com.bookcovermatcher.ui.theme.BookCoverTheme
import com.bookcovermatcher.ui.theme.LocalDisplaySettings
import com.bookcovermatcher.ui.theme.isDark
import com.bookcovermatcher.ui.theme.rememberDisplaySettings

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    fun authorizeGoogleDrive() {
        val generation = viewModel.googleDriveAuthorizationAttempt()
        lifecycleScope.launch {
            val session = com.bookcovermatcher.data.auth.BrowserAuthSessions.google
            val outcome = session.signIn(this@MainActivity)
            val token = if (outcome is com.bookcovermatcher.core.remote.AuthState.SignedIn)
                session.accessToken() as? com.bookcovermatcher.core.remote.TokenResult.Success else null
            if (token != null) viewModel.googleDriveAuthorized(token.token.reveal(), generation)
            else viewModel.googleDriveAuthorizationFailed(
                if (BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isBlank())
                    "Google browser sign-in is not configured. Follow APP_AUTH_PROTOTYPE.md to set the Android OAuth client ID."
                else "Google browser authorization did not complete. Check the client redirect configuration and consent, then try again.", generation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val display = rememberDisplaySettings()
            val darkTheme = display.mode.isDark()
            SideEffect {
                val style = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BookCoverTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalDisplaySettings provides display) {
                    AppScreen(viewModel, lifecycleOwner = this)
                }
            }
        }
    }
}
