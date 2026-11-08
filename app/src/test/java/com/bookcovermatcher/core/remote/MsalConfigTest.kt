// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged MSAL configuration, as the app validates it before MSAL ever parses it (Phase 3).
 *
 * The personal-accounts rule is the important one: the intended authority is
 * `https://login.microsoftonline.com/consumers`, which MSAL derives from the
 * `PersonalMicrosoftAccount` audience. A tenant id beside that audience would aim sign-in at an
 * organisation, so it is rejected rather than ignored.
 */
class MsalConfigTest {

    private val clientId = "11111111-2222-3333-4444-555555555555"

    private fun androidForm() = """
        {"client_id":"$clientId","redirect_uri":"msauth://com.bookcovermatcher/AbCd%2BEf%3D",
         "account_mode":"MULTIPLE","broker_redirect_uri_registered":false,
         "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}
    """.trimIndent()

    @Test fun aPersonalAccountsPublicClientConfigurationIsValid() {
        val result = MsalConfigParser.parse(androidForm())

        assertTrue(result is MsalConfigResult.Valid)
        val config = (result as MsalConfigResult.Valid).config
        assertEquals(clientId, config.clientId)
        assertEquals("msauth://com.bookcovermatcher/AbCd%2BEf%3D", config.redirectUri)
        assertEquals("AAD", config.authorityType)
        assertEquals("PersonalMicrosoftAccount", config.audienceType)
        assertTrue("personal accounts only", config.isPersonalAccountsOnly)
        assertEquals("MULTIPLE", config.accountMode)
        assertFalse("no broker redirect is registered for this app", config.brokerRedirectRegistered)
    }

    @Test fun theMsalDerivedRedirectIsAlsoAccepted() {
        val json = """
            {"client_id":"$clientId","redirect_uri":"msal$clientId://auth",
             "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}
        """.trimIndent()

        assertTrue(MsalConfigParser.parse(json) is MsalConfigResult.Valid)
    }

    @Test fun aTenantIdBesideThePersonalAudienceIsRejected() {
        // Exactly the mistake the prompt warns about: a tenant id would point sign-in at an
        // organisation instead of the personal-accounts endpoint.
        val json = """
            {"client_id":"$clientId","redirect_uri":"msauth://com.bookcovermatcher/AbCd",
             "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount","tenant_id":"$clientId"}}]}
        """.trimIndent()

        val result = MsalConfigParser.parse(json)
        assertTrue(result is MsalConfigResult.Invalid)
        assertTrue((result as MsalConfigResult.Invalid).reason.contains("tenant_id"))
    }

    @Test fun aClientSecretIsRefusedOutright() {
        val json = """
            {"client_id":"$clientId","redirect_uri":"msauth://com.bookcovermatcher/AbCd","client_secret":"hunter2",
             "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}
        """.trimIndent()

        val result = MsalConfigParser.parse(json)
        assertTrue(result is MsalConfigResult.Invalid)
        assertTrue((result as MsalConfigResult.Invalid).reason.contains("credential"))
    }

    @Test fun missingRequiredFieldsAreNamed() {
        fun reasonFor(json: String): String = (MsalConfigParser.parse(json) as MsalConfigResult.Invalid).reason

        assertTrue(reasonFor("""{"redirect_uri":"msauth://com.bookcovermatcher/A"}""").contains("client_id"))
        assertTrue(
            reasonFor(
                """{"client_id":"$clientId","authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}""",
            ).contains("redirect_uri"),
        )
        assertTrue(reasonFor("""{"client_id":"$clientId","redirect_uri":"msauth://p/A"}""").contains("authorities"))
        assertTrue(
            reasonFor("""{"client_id":"$clientId","redirect_uri":"msauth://p/A","authorities":[{"type":"B2C"}]}""")
                .contains("authority type"),
        )
        assertTrue(
            reasonFor("""{"client_id":"$clientId","redirect_uri":"msauth://p/A","authorities":[{"type":"AAD"}]}""")
                .contains("audience"),
        )
    }

    @Test fun anUnusableRedirectIsRejectedWithoutPrintingIt() {
        val json = """
            {"client_id":"$clientId","redirect_uri":"https://example.com/callback",
             "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}
        """.trimIndent()

        val result = MsalConfigParser.parse(json)
        assertTrue(result is MsalConfigResult.Invalid)
        val reason = (result as MsalConfigResult.Invalid).reason
        assertTrue(reason.contains("redirect_uri"))
        assertFalse("the reason must not echo the value", reason.contains("example.com"))
    }

    @Test fun aDocumentThatIsNotJsonIsInvalidNotACrash() {
        assertTrue(MsalConfigParser.parse("<html>") is MsalConfigResult.Invalid)
        assertTrue(MsalConfigParser.parse("") is MsalConfigResult.Invalid)
        assertTrue(MsalConfigParser.parse("[]") is MsalConfigResult.Invalid)
    }

    @Test fun accountModeDefaultsToMultipleAndRejectsNonsense() {
        val base = """{"client_id":"$clientId","redirect_uri":"msauth://p/A","account_mode":"%s",
            "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}""".trimIndent()

        assertEquals(
            "MULTIPLE",
            (MsalConfigParser.parse("""{"client_id":"$clientId","redirect_uri":"msauth://p/A",
                "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}""".trimIndent())
                as MsalConfigResult.Valid).config.accountMode,
        )
        assertEquals(
            "SINGLE",
            (MsalConfigParser.parse(base.format("single")) as MsalConfigResult.Valid).config.accountMode,
        )
        assertTrue(MsalConfigParser.parse(base.format("MANY")) is MsalConfigResult.Invalid)
    }
}

