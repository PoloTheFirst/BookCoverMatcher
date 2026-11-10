// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core.remote

/** Safe support messages: expose numeric Google status, never exception text or account data. */
object GoogleAuthorizationErrors {
    fun message(code: Int?): String {
        val detail = when (code) {
            10 -> "Google Drive is not configured for this installed build. Check its package and signing SHA-1 in Google Cloud."
            7 -> "Google authorization could not connect. Check your internet connection and try again."
            16, 12501 -> "Google reported that authorization was cancelled. Access was not confirmed."
            17 -> "Google authorization is unavailable on this device. Check Google Play services and try again."
            12500 -> "Google could not complete sign-in. Check Play services and this build's OAuth configuration."
            else -> "Google Drive authorization failed. Check Google Play services, OAuth configuration and the test-user account."
        }
        return "$detail (Google code ${code ?: "unavailable"}.)"
    }
}
