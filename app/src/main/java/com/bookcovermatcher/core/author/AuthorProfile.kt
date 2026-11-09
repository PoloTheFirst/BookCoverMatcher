// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

/**
 * The author shown under the License page (v2.6.0).
 *
 * Everything here is a constant on purpose: the page must render with no network at all. The profile picture is
 * a bundled resource and the README is a bundled snapshot; [README_SOURCE_URL] and [README_RETRIEVED] record
 * where that snapshot came from, so a later checkpoint can refresh it honestly.
 */
object AuthorProfile {

    /** The GitHub account, exactly as GitHub spells it. Never replaced by the nickname. */
    const val USERNAME = "PoloTheFirst"

    /** The display nickname, shown as its own field so it cannot be mistaken for the account name. */
    const val NICKNAME = "Polo01"

    const val GITHUB_URL = "https://github.com/PoloTheFirst"

    /** `PoloTheFirst/PoloTheFirst`, the repository whose README GitHub renders on the profile. */
    const val README_SOURCE_URL = "https://github.com/PoloTheFirst/PoloTheFirst/blob/main/README.md"

    /** The raw URL the bundled snapshot was taken from. */
    const val README_RAW_URL = "https://raw.githubusercontent.com/PoloTheFirst/PoloTheFirst/main/README.md"

    /** When the bundled README snapshot was retrieved (UTC date). */
    const val README_RETRIEVED = "2026-11-09"

    /** Where the snapshot lives inside the APK. */
    const val README_ASSET = "author/README.md"

    /** Where the avatar lives inside the APK (a bundled image, never a network fetch). */
    const val AVATAR_ASSET = "author/avatar.jpg"

    /** The profile picture's public origin, recorded for the report and for a future refresh. */
    const val AVATAR_SOURCE_URL = "https://github.com/PoloTheFirst.png"

    /**
     * The one rule for a link leaving the app: HTTPS only, exactly like the catalogue links.
     *
     * Anything else — `http:`, `javascript:`, `intent:`, `file:` — is refused rather than handed to a browser.
     */
    fun isSafeLink(url: String): Boolean = url.startsWith("https://") && url.length > "https://".length

    /** The link a README link run should open, or `null` when it must not leave the app. */
    fun linkTarget(url: String?): String? = url?.trim()?.takeIf { isSafeLink(it) }

    /** The accessibility label for the avatar. */
    fun avatarDescription(): String = "$USERNAME's GitHub profile picture"
}
