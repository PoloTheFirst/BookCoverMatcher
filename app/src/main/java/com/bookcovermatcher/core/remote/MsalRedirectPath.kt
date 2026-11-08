// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

/**
 * The one place that knows how an `msauth://<package>/<signature-hash>` redirect turns into the
 * `android:path` an intent filter has to declare (v2.6.0-dev.1 crash-fix-3).
 *
 * **Why this exists.** Android resolves an implicit `VIEW` intent by comparing the filter's literal
 * `android:path` string with `Uri.getPath()` — the **decoded** path. The Azure portal hands out the
 * signature hash percent-encoded (`AbCd%2BEf%3D`), so a filter that copies it verbatim can never match
 * the URI the browser actually receives: the browser finds no activity to hand off to, shows a blank
 * page, and the app never resumes even though the sign-in itself succeeded. The filter therefore needs
 * the decoded hash (`AbCd+Ef=`).
 *
 * The redirect given to MSAL must **not** be rewritten: MSAL compares the string it receives from the
 * browser with `redirect_uri` from the packaged configuration, so that value stays exactly as Azure
 * registered it. Only the intent filter uses the decoded form.
 *
 * `app/build.gradle.kts` (the `azureRedirectPath` manifest placeholder) implements the same rule; the
 * tests in `MsalRedirectPathTest` pin it so the two cannot drift apart silently.
 */
object MsalRedirectPath {

    /** `msauth://<package>/<hash>` — the Android-platform redirect Azure generates. */
    private val ANDROID_FORM = Regex("^msauth://([^/]+)/(.+)$")

    /** MSAL's own derived redirect `msal<client-id>://auth`, which needs no signature hash. */
    private const val DERIVED_FORM_PREFIX = "msal"

    /**
     * The percent-decoded signature hash of an `msauth://` redirect, or null when [redirect] is not
     * that shape (MSAL's derived `msal<client-id>://auth` included).
     */
    fun signatureHash(redirect: String): String? {
        val encoded = ANDROID_FORM.find(redirect.trim())?.groupValues?.get(2) ?: return null
        return decodePercentEscapes(encoded)
    }

    /**
     * The `android:path` value an intent filter must declare for [redirect], including the leading
     * slash, or null when [redirect] is not an `msauth://` redirect this app has to answer.
     */
    fun intentFilterPath(redirect: String): String? {
        val hash = signatureHash(redirect) ?: return null
        if (hash.isEmpty() || hash.all { it == '/' }) return null
        return "/" + hash
    }

    /** True for the `msal<client-id>://auth` form, which MSAL answers without an intent filter. */
    fun isDerivedForm(redirect: String): Boolean {
        val trimmed = redirect.trim()
        return trimmed.startsWith(DERIVED_FORM_PREFIX) && trimmed.endsWith("://auth")
    }

    /**
     * Decodes `%XX` escapes as UTF-8 **without** treating `+` as a space.
     *
     * `java.net.URLDecoder` is the wrong tool here: it decodes the `application/x-www-form-urlencoded`
     * alphabet, where `+` means a space. A base64 signature hash contains real `+` characters — decoding
     * one to a space would corrupt exactly the value this class exists to get right.
     */
    fun decodePercentEscapes(value: String): String {
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.write(code)
                    index += 3
                    continue
                }
            }
            // A lone '%' or a malformed escape is kept literally rather than dropped.
            bytes.write(char.toString().toByteArray(Charsets.UTF_8))
            index += 1
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
