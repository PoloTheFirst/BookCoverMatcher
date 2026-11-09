// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The content redirect (Phase 3).
 *
 * `/content` answers 302 to a pre-authenticated storage URL. The rule under test is that the only
 * thing this app will do with that URL is fetch it **without credentials** — so a Graph token can never
 * be handed to a second host.
 */
class GraphRedirectTest {

    @Test fun aPreAuthenticatedStorageUrlIsFollowedWithoutCredentials() {
        val plan = GraphRedirect.plan(
            "https://public.dm.files.1drv.com/y4mABCDEF/file.xlsx?download=1",
        )

        assertTrue(plan is GraphRedirect.Plan.FollowWithoutCredentials)
        assertEquals(
            "https://public.dm.files.1drv.com/y4mABCDEF/file.xlsx?download=1",
            (plan as GraphRedirect.Plan.FollowWithoutCredentials).url,
        )
    }

    @Test fun aRedirectBackToTheApiIsRejectedRatherThanFollowedInALoop() {
        val plan = GraphRedirect.plan("https://graph.microsoft.com/v1.0/me/drive/items/x/content")

        assertEquals(RemoteFailure.UnexpectedResponse, (plan as GraphRedirect.Plan.Reject).failure)
    }

    @Test fun aPlaintextRedirectIsRejected() {
        val plan = GraphRedirect.plan("http://public.dm.files.1drv.com/file.xlsx")

        assertEquals(RemoteFailure.BadRequest, (plan as GraphRedirect.Plan.Reject).failure)
    }

    @Test fun aMissingOrUnusableLocationIsRejected() {
        // No `Location` on a 3xx is the service breaking its own protocol, not a lost connection.
        assertEquals(
            RemoteFailure.UnexpectedResponse,
            (GraphRedirect.plan(null) as GraphRedirect.Plan.Reject).failure,
        )
        assertEquals(
            RemoteFailure.UnexpectedResponse,
            (GraphRedirect.plan("   ") as GraphRedirect.Plan.Reject).failure,
        )
        assertEquals(
            RemoteFailure.BadRequest,
            (GraphRedirect.plan("javascript:alert(1)") as GraphRedirect.Plan.Reject).failure,
        )
        assertEquals(
            RemoteFailure.BadRequest,
            (GraphRedirect.plan("file:///etc/passwd") as GraphRedirect.Plan.Reject).failure,
        )
    }

    @Test fun anUrlWithUserInfoIsRejected() {
        // `https://user@host/` would put a credential-shaped string in the request line.
        val plan = GraphRedirect.plan("https://someone@public.dm.files.1drv.com/file.xlsx")

        assertEquals(RemoteFailure.BadRequest, (plan as GraphRedirect.Plan.Reject).failure)
    }
}
