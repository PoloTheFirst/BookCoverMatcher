// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core.remote

import com.bookcovermatcher.data.onedrive.PersonalDownloadAuth
import org.junit.Assert.*
import org.junit.Test

class PersonalDownloadAuthTest {
    private val base = "https://my.microsoftpersonalcontent.com/file"
    @Test fun decodesOnceAndPreservesLiteralPlus() {
        assertEquals("v1.a+b/c=", PersonalDownloadAuth.retryToken("$base?tempauth=v1.a+b%2Fc%3D")?.reveal())
        assertEquals("a%2Bb", PersonalDownloadAuth.retryToken("$base?tempauth=a%252Bb")?.reveal())
    }
    @Test fun rejectsInjectionAndAmbiguousValues() {
        listOf("", "?tempauth=", "?tempauth=a&tempauth=b", "?tempauth=a%0D%0Ab", "?tempauth=a%20b", "?tempauth=%XX").forEach {
            assertNull(PersonalDownloadAuth.retryToken(base + it))
        }
    }
    @Test fun rejectsWrongOriginPortUserInfoAndFragment() {
        listOf("http://my.microsoftpersonalcontent.com/file", "https://my.microsoftpersonalcontent.com:444/file",
            "https://user@my.microsoftpersonalcontent.com/file", "https://my.microsoftpersonalcontent.com.evil.test/file",
            "https://graph.microsoft.com/v1.0/file").forEach {
            assertNull(PersonalDownloadAuth.retryToken("$it?tempauth=a"))
        }
        assertNull(PersonalDownloadAuth.retryToken("$base?tempauth=a#fragment"))
    }
    @Test fun capabilityWrapperDoesNotPrintTheValue() {
        assertFalse(PersonalDownloadAuth.retryToken("$base?tempauth=secret-fixture").toString().contains("secret-fixture"))
    }
}
