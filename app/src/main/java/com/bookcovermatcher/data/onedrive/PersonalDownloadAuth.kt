// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.remote.AccessToken
import java.net.URI
import java.net.URLDecoder

/** Narrow compatibility retry for a personal-content URL which has already returned HTTP 401.
 * This is the URL's own temporary capability, never the account's Microsoft Graph token.
 */
internal object PersonalDownloadAuth {
    fun retryToken(url: String): AccessToken? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) ||
            !uri.host.equals("my.microsoftpersonalcontent.com", true) ||
            uri.rawUserInfo != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port != 443)) return null
        val values = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "tempauth" }
        if (values.size != 1 || !values.single().contains('=')) return null
        // Preserve literal '+' in a signed URL. Decode escapes once; never rewrite the URL itself.
        val value = runCatching {
            URLDecoder.decode(values.single().substringAfter('=').replace("+", "%2B"), "UTF-8")
        }.getOrNull() ?: return null
        if (value.isEmpty() || value.length > 16384 || value.any { it.code !in 0x21..0x7e }) return null
        return AccessToken.of(value)
    }
}
