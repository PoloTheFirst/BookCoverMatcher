// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

/**
 * The one thing the Graph layer needs from authentication: a token, or the reason there is none.
 *
 * Narrower than the full sign-in interface on purpose — list, probe and download never start a
 * sign-in, never read an account name and never sign anything out, so they cannot. A fake is three
 * lines; a real implementation is [TokenResult.Failure] on every path that would need the user.
 */
fun interface AccessTokenSource {
    suspend fun accessToken(): TokenResult
}
