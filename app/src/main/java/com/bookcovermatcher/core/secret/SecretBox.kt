// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.secret

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The sealing primitive behind the SauceNAO API-key store: AES-256-GCM with a random IV per record.
 *
 * Why it lives in `core`: the crypto is plain JVM (`javax.crypto`), so the exact envelope that protects the
 * credential is unit-tested with a real key, while the *Android* half that owns the key (the Keystore) stays
 * in `data/`. A test that mocks encryption proves nothing; this one encrypts and decrypts real bytes.
 *
 * Envelope: `[12-byte IV][ciphertext || 128-bit tag]`. GCM is authenticated, so a modified preference value
 * fails to open instead of decrypting into a corrupted key.
 *
 * Plain Kotlin/JVM; unit-tested.
 */
object AesGcmSecretBox {

    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256

    /** Thrown when a sealed value was modified, truncated, or sealed with another key. */
    class TamperedSecretException(message: String) : RuntimeException(message)

    /** A fresh 256-bit AES key. On Android the app uses a Keystore-held key instead; this is for tests. */
    fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()

    /** A random IV. Never reused: one IV per seal call. */
    fun newIv(): ByteArray = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

    fun seal(key: SecretKey, plain: ByteArray, iv: ByteArray = newIv()): ByteArray {
        require(iv.size == IV_BYTES) { "a GCM IV must be $IV_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain)
        return iv + body
    }

    /** @throws TamperedSecretException when the envelope cannot be authenticated with [key]. */
    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) {
            throw TamperedSecretException("The stored value is too short to be a sealed secret.")
        }
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val body = sealed.copyOfRange(IV_BYTES, sealed.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        } catch (e: Exception) {
            // Deliberately terse: the exception must never carry the plaintext or a partial key.
            throw TamperedSecretException("The stored secret could not be opened; it may have been altered.")
        }
    }

    /** Base64 (URL-safe, unpadded) for storage in a preference value. */
    fun encode(sealed: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)

    fun decode(text: String): ByteArray =
        try {
            java.util.Base64.getUrlDecoder().decode(text.trim())
        } catch (e: IllegalArgumentException) {
            throw TamperedSecretException("The stored secret is not readable.")
        }
}
