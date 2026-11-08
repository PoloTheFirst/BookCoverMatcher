// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule added by v2.6.0-dev.1 crash-fix-3: an `msauth://` redirect must be turned into the
 * **percent-decoded** `android:path`, because Android matches the filter's literal path against
 * `Uri.getPath()`. The shipped filter used Azure's encoded hash verbatim, matched nothing, and the
 * browser showed a blank page instead of handing the response back to the app.
 *
 * These tests are the reason the rule is written down twice (here and in `app/build.gradle.kts`'s
 * `decodeManifestPath`): the build script cannot call into the app's source set, so the pure rule lives
 * here with tests and the build implements the same three lines.
 */
class MsalRedirectPathTest {

    /**
     * The shape Azure's portal produces: `+` and `=` percent-encoded inside the base64 hash. The value is
     * synthetic on purpose — the real hash belongs to the machine's signing certificate and to the
     * git-ignored `.env`, not to a test fixture.
     */
    private val azureEncoded = "msauth://com.bookcovermatcher/AbCd%2BEf%3D"

    private val decodedHash = "AbCd+Ef="

    @Test
    fun theEncodedSignatureHashIsDecodedForTheIntentFilter() {
        assertEquals("/$decodedHash", MsalRedirectPath.intentFilterPath(azureEncoded))
    }

    @Test
    fun anAlreadyDecodedHashIsUnchanged() {
        assertEquals("/$decodedHash", MsalRedirectPath.intentFilterPath("msauth://com.bookcovermatcher/$decodedHash"))
    }

    @Test
    fun aPlusInTheHashIsNotTurnedIntoASpace() {
        // java.net.URLDecoder would produce "7y wU0pt…" and the filter would match nothing.
        val path = MsalRedirectPath.intentFilterPath(azureEncoded)
        assertTrue("the base64 '+' must survive decoding: $path", path!!.contains('+'))
        assertFalse("no space may appear in the decoded hash: $path", path.contains(' '))
    }

    @Test
    fun theSignatureHashIsReturnedWithoutTheLeadingSlashOrEscapes() {
        assertEquals(decodedHash, MsalRedirectPath.signatureHash(azureEncoded))
    }

    @Test
    fun msalsOwnDerivedRedirectNeedsNoSignatureHash() {
        val derived = "msal11111111-2222-3333-4444-555555555555://auth"
        assertNull(MsalRedirectPath.signatureHash(derived))
        assertNull(MsalRedirectPath.intentFilterPath(derived))
        assertTrue(MsalRedirectPath.isDerivedForm(derived))
    }

    @Test
    fun aRedirectThisAppCannotAnswerHasNoFilterPath() {
        assertNull(MsalRedirectPath.intentFilterPath("https://example.com/callback"))
        assertNull(MsalRedirectPath.intentFilterPath("msauth://com.bookcovermatcher/"))
        assertNull(MsalRedirectPath.intentFilterPath(""))
        assertFalse(MsalRedirectPath.isDerivedForm("https://example.com/callback"))
    }

    @Test
    fun aMalformedEscapeIsKeptLiterallyRatherThanDropped() {
        assertEquals("/abc%zz", MsalRedirectPath.intentFilterPath("msauth://p/abc%zz"))
        assertEquals("/100%", MsalRedirectPath.intentFilterPath("msauth://p/100%"))
    }

    @Test
    fun multiByteEscapesAreDecodedAsUtf8() {
        // %E3%81%82 is the Hiragana "あ". Nothing in a real signature hash needs this, but a decoder that
        // drops bytes silently is how the redirect bug happened in the first place.
        assertEquals("/あ", MsalRedirectPath.decodePercentEscapes("/%E3%81%82"))
    }

    @Test
    fun thePackagedRedirectIsAcceptedAndPlacedInTheFilterDecoded() {
        // The same shape the packaged configuration uses (see MsalConfigTest), end to end.
        val json = """
            {"client_id":"11111111-2222-3333-4444-555555555555",
            "redirect_uri":"$azureEncoded","account_mode":"MULTIPLE",
            "broker_redirect_uri_registered":false,
            "authorities":[{"type":"AAD","audience":{"type":"PersonalMicrosoftAccount"}}]}
        """.trimIndent()

        val result = MsalConfigParser.parse(json)

        assertTrue("the packaged redirect must stay valid: $result", result is MsalConfigResult.Valid)
        val config = (result as MsalConfigResult.Valid).config
        assertEquals("MSAL must be given the redirect exactly as Azure registered it", azureEncoded, config.redirectUri)
        assertEquals("/$decodedHash", MsalRedirectPath.intentFilterPath(config.redirectUri))
    }
}
