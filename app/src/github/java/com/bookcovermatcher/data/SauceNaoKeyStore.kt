// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bookcovermatcher.core.online.reverse.SauceNaoKeyRejection
import com.bookcovermatcher.core.online.reverse.SauceNaoKeyRules
import com.bookcovermatcher.core.online.reverse.SauceNaoKeySaveResult
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
     * The two failure modes are reported separately, because they need different user action: a malformed value
     * is a typing problem, while a Keystore failure means this device cannot hold the credential. Collapsing them
     * (as v2.5.0-dev.2 did) hid a real encryption failure behind "that does not look like a key".
     */
    fun save(apiKey: String): SauceNaoKeySaveResult {
        val clean = apiKey.trim()
        if (!SauceNaoKeyRules.isPlausible(clean)) {
            return SauceNaoKeySaveResult.Rejected(SauceNaoKeyRejection.MALFORMED)
        }
        return try {
            val key = secretKey(create = true)
                ?: return SauceNaoKeySaveResult.Rejected(SauceNaoKeyRejection.STORAGE_FAILED)
            // The cipher chooses the IV: an AndroidKeyStore AES/GCM key forbids a caller-supplied nonce
            // (CALLER_NONCE_PROHIBITED), which is why the previous implementation could never save anything.
            val sealed = AesGcmSecretBox.seal(key, clean.toByteArray(Charsets.UTF_8))
            val stored = prefs.edit().putString(KEY_SECRET, AesGcmSecretBox.encode(sealed)).commit()
            if (!stored) return SauceNaoKeySaveResult.Rejected(SauceNaoKeyRejection.STORAGE_FAILED)
            // Round-trip check: a save that cannot be read back is not a save. This is the step that would have
            // caught the v2.5.0-dev.2 defect at the moment the user pressed the button.
            if (key() == null) SauceNaoKeySaveResult.Rejected(SauceNaoKeyRejection.STORAGE_FAILED)
            else SauceNaoKeySaveResult.Saved
        } catch (e: Exception) {
            SauceNaoKeySaveResult.Rejected(SauceNaoKeyRejection.STORAGE_FAILED)
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
    }
}
