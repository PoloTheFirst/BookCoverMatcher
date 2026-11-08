// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.data.onedrive.GraphFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTTP status → failure mapping (v2.6.0-dev.1 Phase 7).
 *
 * Phase 3 could only *read* this code: it lived in private functions inside `GraphHttp`, and the class is
 * deliberately never exercised over a socket in unit tests. The mapping is now a pure object, so every
 * branch is asserted — which matters because it decides what the user is told: "your file is gone", "you
 * must sign in again", "wait 30 seconds", or "OneDrive is broken".
 */
class GraphFailuresTest {

    // ---- status codes ------------------------------------------------------------------------

    @Test fun aSuccessfulOrRedirectStatusIsNeverAFailure() {
        // The mapping is only consulted for an unsuccessful answer; asserting the default keeps a future
        // caller from treating an unexpected 2xx by accident.
        assertEquals(RemoteFailure.BadRequest, GraphFailures.forStatus(200, null))
        assertEquals(RemoteFailure.BadRequest, GraphFailures.forStatus(302, null))
    }

    @Test fun unauthorizedMeansTheSignInExpired() {
        assertEquals(RemoteFailure.AuthExpired, GraphFailures.forStatus(401, null))
    }

    @Test fun forbiddenMeansAnAccountIsNeeded() {
        assertEquals(RemoteFailure.AuthRequired, GraphFailures.forStatus(403, null))
    }

    @Test fun goneIsTheSameSituationAsNotFound() {
        assertEquals(RemoteFailure.NotFound, GraphFailures.forStatus(404, null))
        assertEquals(RemoteFailure.NotFound, GraphFailures.forStatus(410, null))
    }

    @Test fun tooManyRequestsCarriesTheDelayWhenThereIsOne() {
        assertEquals(RemoteFailure.Throttled(45), GraphFailures.forStatus(429, 45))
        assertEquals(
            "a throttle without a header is still a throttle",
            RemoteFailure.Throttled(null),
            GraphFailures.forStatus(429, null),
        )
    }

    @Test fun aBusyServiceAndABrokenServiceReadDifferently() {
        // Graph uses 503 for both; only one of them comes with a Retry-After.
        assertEquals(RemoteFailure.Throttled(12), GraphFailures.forStatus(503, 12))
        assertEquals(RemoteFailure.ServerError(503), GraphFailures.forStatus(503, null))
    }

    @Test fun otherServerErrorsKeepTheirStatus() {
        assertEquals(RemoteFailure.ServerError(500), GraphFailures.forStatus(500, null))
        assertEquals(RemoteFailure.ServerError(502), GraphFailures.forStatus(502, null))
        assertEquals(RemoteFailure.ServerError(504), GraphFailures.forStatus(504, null))
    }

    @Test fun anythingElseIsARequestWeShouldNotHaveMade() {
        listOf(400, 405, 409, 412, 413, 416).forEach { status ->
            assertEquals("status $status", RemoteFailure.BadRequest, GraphFailures.forStatus(status, null))
        }
    }

    @Test fun theMappingAndTheRetryRuleAgree() {
        // If they did not, the panel would offer a button that cannot work.
        assertTrue(GraphFailures.forStatus(429, null).isRetryable())
        assertTrue(GraphFailures.forStatus(500, null).isRetryable())
        assertEquals(false, GraphFailures.forStatus(401, null).isRetryable())
        assertEquals(false, GraphFailures.forStatus(404, null).isRetryable())
    }

    // ---- Retry-After ---------------------------------------------------------------------------

    @Test fun aWellFormedRetryAfterIsRead() {
        assertEquals(0L, GraphFailures.retryAfterSeconds("0"))
        assertEquals(30L, GraphFailures.retryAfterSeconds("30"))
        assertEquals("surrounding whitespace is tolerated", 7L, GraphFailures.retryAfterSeconds("  7 "))
    }

    @Test fun aMalformedRetryAfterIsTreatedAsAbsent() {
        assertNull(GraphFailures.retryAfterSeconds(null))
        assertNull(GraphFailures.retryAfterSeconds(""))
        assertNull(GraphFailures.retryAfterSeconds("   "))
        assertNull(
            "the HTTP-date form is legal but not used by this API",
            GraphFailures.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"),
        )
        assertNull(GraphFailures.retryAfterSeconds("soon"))
        assertNull("a negative delay is not a delay", GraphFailures.retryAfterSeconds("-5"))
        assertNull(GraphFailures.retryAfterSeconds("1.5"))
        assertNull(GraphFailures.retryAfterSeconds("9999999999999999999999"))
    }

    @Test fun aMalformedHeaderFallsBackToTheNonThrottleReading() {
        // What the user is told when the header is nonsense: 429 stays "busy", 503 becomes "broken".
        assertEquals(
            RemoteFailure.Throttled(null),
            GraphFailures.forStatus(429, GraphFailures.retryAfterSeconds("later")),
        )
        assertEquals(
            RemoteFailure.ServerError(503),
            GraphFailures.forStatus(503, GraphFailures.retryAfterSeconds("")),
        )
    }

    // ---- storage versus transport --------------------------------------------------------------

    @Test fun aFullDeviceIsNotMistakenForABadConnection() {
        assertEquals(
            RemoteFailure.NoStorage,
            GraphFailures.forIoFailure("write failed: ENOSPC (No space left on device)"),
        )
        assertEquals(RemoteFailure.NoStorage, GraphFailures.forIoFailure("No space left on device"))
        assertEquals(RemoteFailure.NoStorage, GraphFailures.forIoFailure("There is not enough space on the disk"))
    }

    @Test fun anOrdinaryIoFailureIsReportedAsUnstable() {
        assertEquals(RemoteFailure.Unstable, GraphFailures.forIoFailure("connection reset by peer"))
        assertEquals(RemoteFailure.Unstable, GraphFailures.forIoFailure("unexpected end of stream"))
        assertEquals("a null message is still a transport failure", RemoteFailure.Unstable, GraphFailures.forIoFailure(null))
        assertEquals(RemoteFailure.Unstable, GraphFailures.forIoFailure(""))
    }

    @Test fun theTwoIoOutcomesNeedDifferentUserActions() {
        assertTrue("a dead connection is worth retrying", GraphFailures.forIoFailure("reset").isRetryable())
        assertEquals("a full disk is not", false, GraphFailures.forIoFailure("ENOSPC").isRetryable())
    }
}

