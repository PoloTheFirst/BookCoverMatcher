// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online.reverse

/**
 * What a user-entered SauceNAO API key may look like, and what the app may say about it.
 *
 * Kept in `core` as a pure rule because the *storage* half (`SauceNaoKeyStore`) cannot be unit-tested on the
 * JVM — it needs the Android Keystore. The distinction this type encodes matters because v2.5.0-dev.2 collapsed
 * two very different failures into one message: "That does not look like a SauceNAO key; nothing was saved" was
 * shown both for a malformed key **and** for a Keystore encryption failure (which really happened — see
 * `AesGcmSecretBox`). The user could not tell that their key was fine and the device refused to store it.
 */
enum class SauceNaoKeyRejection {
    /** The value is not a plausible key: blank, whitespace, too short or absurdly long. */
    MALFORMED,

    /** The value looks right, but this device could not store it securely. Nothing was written. */
    STORAGE_FAILED,
}

/** The outcome of asking the key store to save a value. */
sealed interface SauceNaoKeySaveResult {
    data object Saved : SauceNaoKeySaveResult

    data class Rejected(val reason: SauceNaoKeyRejection) : SauceNaoKeySaveResult
}

/**
 * The shape rules for a SauceNAO API key, kept deliberately loose: the app must not reject a valid key because it
 * guessed a format. Only obvious nonsense is refused before storage.
 */
object SauceNaoKeyRules {

    const val MIN_LENGTH = 8

    /** SauceNAO keys are ~40 hex characters; the bound only rejects obvious pastes. */
    const val MAX_LENGTH = 128

    /** True when [raw] could be a key at all (trimmed length in range, no internal whitespace). */
    fun isPlausible(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.length !in MIN_LENGTH..MAX_LENGTH) return false
        return clean.none { it.isWhitespace() }
    }

    /** A safe, key-free explanation for the UI. */
    fun describe(rejection: SauceNaoKeyRejection): String = when (rejection) {
        SauceNaoKeyRejection.MALFORMED ->
            "That does not look like a SauceNAO API key (it must be a single word of ${MIN_LENGTH}\u2013" +
                "$MAX_LENGTH characters). Nothing was saved."
        SauceNaoKeyRejection.STORAGE_FAILED ->
            "This device could not store the key securely, so it was not saved. The key itself was not kept; " +
                "try again, and check that this device has a working secure keystore."
    }
}
