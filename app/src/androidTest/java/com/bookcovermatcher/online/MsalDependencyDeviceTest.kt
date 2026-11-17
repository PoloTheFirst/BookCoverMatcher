// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import android.content.Intent
import com.bookcovermatcher.data.auth.BrowserAuthSessions
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime dependency regression guard for the local browser-auth replacement. */
@RunWith(AndroidJUnit4::class)
class MsalDependencyDeviceTest {
    @Test fun browserRequestsUseUniqueStateAndS256AndResolveToAppAuth() {
        val session = BrowserAuthSessions.microsoft
        val first = requireNotNull(session.request())
        val second = requireNotNull(session.request())
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertEquals("S256", first.codeVerifierChallengeMethod)
        assertTrue(first.scope!!.contains("offline_access"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, first.redirectUri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(handlers.any { it.activityInfo.name == "net.openid.appauth.RedirectUriReceiverActivity" })
    }

    @Test fun proprietaryAuthClassesAreAbsent() {
        val loader = InstrumentationRegistry.getInstrumentation().targetContext.classLoader
        listOf("com.microsoft.identity.client.PublicClientApplication",
            "com.google.android.gms.auth.api.identity.Identity",
            "com.microsoft.device.display.DisplayMask").forEach { name ->
            assertTrue(name, runCatching { Class.forName(name, false, loader) }.exceptionOrNull() is ClassNotFoundException)
        }
        Class.forName("net.openid.appauth.AuthorizationService", false, loader)
    }
}
