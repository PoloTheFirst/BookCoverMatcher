// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.reverse.SauceNaoKeyRejection
import com.bookcovermatcher.core.online.reverse.SauceNaoKeyRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.3 — the API-key shape rules and the two *different* failure messages.
 *
 * The rules are pure so they can be tested on the JVM; the storage half lives in `SauceNaoKeyStore` and needs the
 * Android Keystore. What matters here is that a Keystore failure and a mistyped key are never reported with the
 * same sentence again — in v2.5.0-dev.2 a real encryption failure was shown as "that does not look like a key".
 */
class SauceNaoKeyRulesTest {

    @Test
    fun `a normal 40-character hex key is plausible`() {
        assertTrue(SauceNaoKeyRules.isPlausible("0123456789abcdef0123456789abcdef01234567"))
        assertTrue("surrounding whitespace is trimmed", SauceNaoKeyRules.isPlausible("  padded-key-value  "))
        assertTrue("9 characters is the low end", SauceNaoKeyRules.isPlausible("AbCdEf123"))
    }

    @Test
    fun `blank, too short, too long and internally spaced values are rejected`() {
        assertFalse(SauceNaoKeyRules.isPlausible(""))
        assertFalse(SauceNaoKeyRules.isPlausible("   "))
        assertFalse(SauceNaoKeyRules.isPlausible("short"))
        assertFalse(SauceNaoKeyRules.isPlausible("a".repeat(SauceNaoKeyRules.MAX_LENGTH + 1)))
        assertFalse(
            "an accidental copy with a space inside is not a key",
            SauceNaoKeyRules.isPlausible("0123 4567 89ab cdef"),
        )
    }

    @Test
    fun `the two rejection reasons produce different, key-free messages`() {
        val malformed = SauceNaoKeyRules.describe(SauceNaoKeyRejection.MALFORMED)
        val storage = SauceNaoKeyRules.describe(SauceNaoKeyRejection.STORAGE_FAILED)

        assertTrue(malformed.contains("does not look like"))
        assertTrue(storage.contains("could not store the key securely"))
        assertFalse("the messages must not be interchangeable", malformed == storage)
        // Neither may echo a key, a length-derived guess or a stack trace.
        for (message in listOf(malformed, storage)) {
            assertFalse(message.contains("hex"))
            assertFalse(message.contains("Exception"))
            assertFalse(message.contains("at com."))
        }
    }
}
