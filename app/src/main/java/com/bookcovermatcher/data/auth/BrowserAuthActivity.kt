// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService

/** Non-exported bridge; AppAuth validates OAuth state before delivering the browser response. */
class BrowserAuthActivity : ComponentActivity() {
    private lateinit var service: AuthorizationService
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val session = BrowserAuthSessions.find(intent.getStringExtra("provider"))
        val ticket = intent.getStringExtra("attempt")
        if (session != null && ticket != null) {
            session.complete(ticket, result.data?.let(AuthorizationResponse::fromIntent),
                result.data?.let(AuthorizationException::fromIntent), applicationContext)
        }
        // The session owns the separate service used for the token exchange.
        finish()
    }
    override fun onDestroy() {
        service.dispose()
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = AuthorizationService(applicationContext)
        val session = BrowserAuthSessions.find(intent.getStringExtra("provider"))
        if (session == null || session.attempt != intent.getStringExtra("attempt")) { finish(); return }
        if (savedInstanceState == null) {
            val request = session.request()
            if (request == null) { finish(); return }
            try { launcher.launch(service.getAuthorizationRequestIntent(request)) }
            catch (_: Exception) {
                session.complete(session.attempt!!, null, null, applicationContext)
                finish()
            }
        }
    }
}
