// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * The packaged MSAL configuration, parsed and checked before MSAL ever sees it.
 *
 * MSAL reads the same JSON (`res/raw/msal_config_default.json`) itself; this parser exists so a broken
 * or placeholder configuration produces one clear sentence ("OneDrive sign-in is not configured in
 * this build") instead of an opaque `MsalConfigurationError`, and so the properties that matter for this
 * release are pinned by tests: personal accounts only, a public client, and the redirect this app can
 * actually answer.
 *
 * Nothing here is a secret. A **rejection of any `client_secret` key** is deliberate: a public client
 * must never carry one, so a configuration that has one is a mistake worth failing on.
 */
data class MsalConfig(
    val clientId: String,
    val redirectUri: String,
    val authorityType: String,
    val audienceType: String,
    val accountMode: String,
    val brokerRedirectRegistered: Boolean,
) {
    /** True when the authority is the personal-accounts endpoint and nothing else. */
    val isPersonalAccountsOnly: Boolean get() = audienceType == AUDIENCE_PERSONAL

    companion object {
        const val AUDIENCE_PERSONAL = "PersonalMicrosoftAccount"
        const val AUTHORITY_AAD = "AAD"
    }
}

sealed interface MsalConfigResult {
    data class Valid(val config: MsalConfig) : MsalConfigResult
    data class Invalid(val reason: String) : MsalConfigResult
}

object MsalConfigParser {

    /** Names that would turn this public client into a confidential one — or leak a real secret. */
    private val FORBIDDEN_KEYS = listOf("client_secret", "clientSecret", "client_assertion")

    fun parse(text: String): MsalConfigResult {
        val root = try {
            MiniJson.parse(text) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return MsalConfigResult.Invalid("the configuration is not a JSON object")

        FORBIDDEN_KEYS.firstOrNull { root.containsKey(it) }?.let {
            return MsalConfigResult.Invalid("a public client must not carry a credential ($it)")
        }

        val clientId = (root["client_id"] as? String)?.trim().orEmpty()
        if (clientId.isEmpty()) return MsalConfigResult.Invalid("client_id is missing")

        val redirect = (root["redirect_uri"] as? String)?.trim().orEmpty()
        if (redirect.isEmpty()) return MsalConfigResult.Invalid("redirect_uri is missing")

        val authorities = root["authorities"] as? List<*>
        val authority = authorities?.firstOrNull() as? Map<*, *>
            ?: return MsalConfigResult.Invalid("authorities is missing or empty")
        val authorityType = (authority["type"] as? String)?.trim().orEmpty()
        if (authorityType != MsalConfig.AUTHORITY_AAD) {
            return MsalConfigResult.Invalid("the authority type must be ${MsalConfig.AUTHORITY_AAD}")
        }
        val audience = authority["audience"] as? Map<*, *>
            ?: return MsalConfigResult.Invalid("the authority has no audience")
        val audienceType = (audience["type"] as? String)?.trim().orEmpty()
        if (audienceType.isEmpty()) return MsalConfigResult.Invalid("the audience type is missing")

        // A tenant id beside a personal-accounts audience is the mistake this release must not make:
        // it would point sign-in at an organisation instead of the consumers endpoint.
        if (audienceType == MsalConfig.AUDIENCE_PERSONAL && audience["tenant_id"] != null) {
            return MsalConfigResult.Invalid(
                "a tenant_id must not be combined with the personal-accounts audience",
            )
        }

        val accountMode = (root["account_mode"] as? String)?.trim()?.uppercase() ?: "MULTIPLE"
        if (accountMode != "MULTIPLE" && accountMode != "SINGLE") {
            return MsalConfigResult.Invalid("account_mode must be MULTIPLE or SINGLE")
        }

        val redirectFailure = redirectProblem(clientId, redirect)
        if (redirectFailure != null) return MsalConfigResult.Invalid(redirectFailure)

        return MsalConfigResult.Valid(
            MsalConfig(
                clientId = clientId,
                redirectUri = redirect,
                authorityType = authorityType,
                audienceType = audienceType,
                accountMode = accountMode,
                brokerRedirectRegistered = root["broker_redirect_uri_registered"] == true,
            ),
        )
    }

    /**
     * The two redirect shapes this app can answer, checked without ever printing the value:
     * MSAL's derived `msal<client-id>://auth`, or the Android-platform
     * `msauth://<package>/<signature-hash>` the Azure portal generates.
     */
    private fun redirectProblem(clientId: String, redirect: String): String? {
        if (redirect == "msal$clientId://auth") return null
        val androidForm = Regex("^msauth://([^/]+)/(?:[A-Za-z0-9+/=_.-]|%[0-9A-Fa-f]{2})+$").find(redirect)
        if (androidForm != null && androidForm.groupValues[1].isNotBlank()) return null
        return "redirect_uri is neither msal<client-id>://auth nor msauth://<package>/<signature-hash>"
    }
}
