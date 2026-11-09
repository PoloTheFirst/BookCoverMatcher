// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OneDrive trace added by v2.6.0-dev.1 crash-fix-5.
 *
 * Its whole value is that it can be *pasted* by the user: it says which stage and which HTTP status
 * failed, and it cannot carry a token, a query string, a page token, a storage URL, an account or a
 * file name. These tests are the proof of that last sentence.
 */
class RemoteDiagnosticsTest {

    @Test fun thePathKeepsTheEndpointAndDropsTheQuery() {
        val path = RemoteDiagnostics.sanitizePath(
            "https://graph.microsoft.com/v1.0/me/drive/root/children?\$select=id,name&\$top=200",
        )

        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/children", path)
        assertFalse("the query must never be logged: $path", path.contains("?"))
        assertFalse("the query must never be logged: $path", path.contains("select"))
    }

    @Test fun anItemIdIsReplacedByAPlaceholder() {
        val path = RemoteDiagnostics.sanitizePath(
            "https://graph.microsoft.com/v1.0/me/drive/items/01ABCDEF0123456789!123/children?\$top=200",
        )

        assertEquals("https://graph.microsoft.com/v1.0/me/drive/items/{id}/children", path)
        assertFalse("a driveItem id is an identifier, not a log field: $path", path.contains("01ABCDEF"))
    }

    @Test fun aPreAuthenticatedStorageUrlKeepsNoCredentials() {
        // The 302 target carries a short-lived `tempauth` query; none of it may reach the log.
        val path = RemoteDiagnostics.sanitizePath(
            "https://tenant-my.sharepoint.com/personal/user/_layouts/15/download.aspx" +
                "?UniqueId=abc&tempauth=SECRET-TOKEN-VALUE",
        )

        assertEquals(
            "https://tenant-my.sharepoint.com/personal/user/_layouts/15/download.aspx",
            path,
        )
        assertFalse("a storage token must never be logged: $path", path.contains("SECRET"))
        assertFalse("a storage token must never be logged: $path", path.contains("tempauth"))
    }

    @Test fun anUnusableUrlBecomesAPlaceholderRatherThanAnException() {
        assertEquals("-", RemoteDiagnostics.sanitizePath(null))
        assertEquals("-", RemoteDiagnostics.sanitizePath(""))
        assertEquals("-", RemoteDiagnostics.sanitizePath("not a url"))
        assertEquals("-", RemoteDiagnostics.sanitizePath("mailto:someone@example.com"))
    }

    @Test fun aFailureLineNamesTheStageTheStatusAndTheGraphCode() {
        val line = RemoteDiagnostics.describe(
            stage = "list",
            method = "GET",
            url = "https://graph.microsoft.com/v1.0/me/drive/root/children?\$top=200",
            status = 403,
            graphErrorCode = "accessDenied",
            requestId = "11111111-2222-3333-4444-555555555555",
            failure = RemoteFailure.PermissionDenied,
        )

        assertTrue(line, line.contains("stage=list"))
        assertTrue(line, line.contains("status=403"))
        assertTrue(line, line.contains("code=accessDenied"))
        assertTrue(line, line.contains("requestId=11111111-2222-3333-4444-555555555555"))
        assertTrue(line, line.contains("failure=PermissionDenied"))
        assertFalse("no query, no token: $line", line.contains("?"))
    }

    @Test fun theDetailFieldNamesTheThrowerWithoutAMessage() {
        // crash-fix-6: the `detail` field is what identified MSAL rejecting our silent-token parameters.
        // It carries a type name and frame identifiers only — never the exception message, which can
        // contain account data.
        val line = RemoteDiagnostics.describe(
            stage = "token",
            method = "-",
            graphErrorCode = "illegal_argument_exception",
            failure = RemoteFailure.AuthUnexpected,
            detail = "MsalArgumentException@MsalUtils.validateNonNullArg:138|PublicClientApplication:1844",
        )

        assertTrue(line, line.contains("stage=token"))
        assertTrue(line, line.contains("code=illegal_argument_exception"))
        assertTrue(line, line.contains("failure=AuthUnexpected"))
        assertTrue(line, line.contains("detail=MsalArgumentException@MsalUtils.validateNonNullArg:138"))
    }

    @Test fun anAbsentDetailAddsNothing() {
        val line = RemoteDiagnostics.describe(stage = "list", status = 200)

        assertFalse(line, line.contains("detail="))
    }

    @Test fun aSuccessfulLineCarriesNoFailure() {
        val line = RemoteDiagnostics.describe(
            stage = "list",
            url = "https://graph.microsoft.com/v1.0/me/drive/root/children",
            status = 200,
        )

        assertTrue(line, line.contains("status=200"))
        assertFalse(line, line.contains("failure="))
    }

    @Test fun aGraphSuppliedCodeCannotInjectTextIntoTheLine() {
        val line = RemoteDiagnostics.describe(
            stage = "list",
            status = 400,
            graphErrorCode = "bad\ncode with spaces and a very long tail that should be truncated",
        )

        assertFalse("newlines must be stripped: $line", line.contains("\n"))
        assertTrue(line.length < 200)
    }

    @Test fun theSinkReceivesTheSameLineAndCanNeverBreakTheCaller() {
        val lines = mutableListOf<String>()
        val previous = RemoteDiagnostics.sink
        try {
            RemoteDiagnostics.sink = { lines += it }
            RemoteDiagnostics.log("list", url = "https://graph.microsoft.com/v1.0/me/drive/root/children", status = 200)

            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("stage=list"))

            // A sink that throws must not surface into the OneDrive stack.
            RemoteDiagnostics.sink = { error("logcat is unavailable") }
            RemoteDiagnostics.log("list", status = 200)
        } finally {
            RemoteDiagnostics.sink = previous
        }
    }
}
