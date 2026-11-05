// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bookcovermatcher.core.online.reverse.SauceNaoProtocol
import com.bookcovermatcher.core.secret.AesGcmSecretBox
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The SauceNAO API key, encrypted at rest with a key that never leaves the Android Keystore.
 *
 * Why a Keystore key: a key stored next to its ciphertext in plain preferences is obfuscation, not
 * encryption — anyone with the file has both halves. Here the ciphertext lives in a private preference file
 * and the AES-256-GCM key lives in the Keystore, which is hardware-backed on most devices and cannot be
 * exported.
 *
 * Rules this class enforces, and why:
 *
 *  * **The key is never logged, echoed or shown.** [hint] returns stars plus the last four characters, which
 *    is enough to tell two keys apart and useless to an attacker.
 *  * **The key is never put in a URL.** It travels in the multipart request body (see
 *    `SauceNaoProtocol.formFields`), because a URL ends up in `Referer` headers and proxy logs.
 *  * **A broken blob is deleted, not repaired.** If the Keystore key is gone (restore to a new device, app
 *    data partially cleared), the stored ciphertext cannot be opened; the store drops it and reports
 *    "not configured" rather than sending garbage to the provider.
 *  * **It is not part of `OnlineSearchPrefs`.** That file is ordinary, unencrypted, backed-up-adjacent
 *    settings; the credential belongs in its own store with its own lifecycle.
 */
class SauceNaoKeyStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** True when a key is stored *and* can actually be decrypted right now. */
    fun isConfigured(): Boolean = key() != null

    /**
     * The key for a request, or null when none is stored or the stored value cannot be opened.
     *
     * Never throws: a credential problem must degrade to "not configured", not crash a search.
     */
    fun key(): String? {
        val blob = prefs.getString(KEY_SECRET, null) ?: return null
        return try {
            val plain = AesGcmSecretBox.open(secretKey(create = false) ?: return clearAndNull(), AesGcmSecretBox.decode(blob))
            val text = String(plain, Charsets.UTF_8).trim()
            text.ifEmpty { clearAndNull() }
        } catch (e: Exception) {
            clearAndNull()
        }
    }

    /**
     * Stores [apiKey].
     *
     * @return false when the value is not a plausible key (blank, whitespace, absurdly long) — nothing is
     *         written in that case.
     */
    fun save(apiKey: String): Boolean {
        val clean = apiKey.trim()
        if (clean.length !in 8..MAX_KEY_LENGTH) return false
        if (clean.any { it.isWhitespace() }) return false
        return try {
            val sealed = AesGcmSecretBox.seal(secretKey(create = true)!!, clean.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(KEY_SECRET, AesGcmSecretBox.encode(sealed)).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Forgets the key. The Keystore entry is deleted too, so a later key needs a fresh one. */
    fun clear() {
        prefs.edit().remove(KEY_SECRET).apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /** `••••••••ab12`, or null when nothing is stored. Never the key itself. */
    fun hint(): String? = key()?.let { SauceNaoProtocol.maskKey(it) }

    private fun clearAndNull(): String? {
        clear()
        return null
    }

    private fun secretKey(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AesGcmSecretBox.KEY_BITS)
                // No user-authentication requirement: an unattended search must be able to read the key.
                // The trade-off is documented: the protection is device-bound at-rest encryption, not
                // per-use biometric authorisation.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val STORE = "bookcover_online_secrets"
        const val KEY_SECRET = "saucenao_secret_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bookcover_saucenao_key_v1"

        /** SauceNAO keys are ~32 characters; the bound only rejects obvious nonsense. */
        const val MAX_KEY_LENGTH = 128
    }
}
