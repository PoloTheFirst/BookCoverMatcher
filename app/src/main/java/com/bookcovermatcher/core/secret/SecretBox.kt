package com.bookcovermatcher.core.secret

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The sealing primitive behind the SauceNAO API-key store: AES-256-GCM.
 *
 * Why it lives in `core`: the crypto is plain JVM (`javax.crypto`), so the exact envelope that protects the
 * credential is unit-tested with a real key, while the *Android* half that owns the key (the Keystore) stays
 * in `data/`. A test that mocks encryption proves nothing; this one encrypts and decrypts real bytes.
 *
 * Envelope: `[12-byte IV][ciphertext || 128-bit tag]`. GCM is authenticated, so a modified preference value
 * fails to open instead of decrypting into a corrupted key.
 *
 * **The IV is chosen by the cipher, never supplied by the caller.** This is not a style choice: an
 * `AndroidKeyStore` AES/GCM key is created with `setRandomizedEncryptionRequired(true)` (the platform default),
 * which means the Keystore *forbids* a caller-supplied nonce. Passing a `GCMParameterSpec` to
 * `Cipher.init(ENCRYPT_MODE, …)` then fails with `CALLER_NONCE_PROHIBITED` — measured on an API 37 emulator
 * (keystore2 `enforcements.rs`: "NONCE is present, although CALLER_NONCE is not present"). v2.5.0-dev.2 did
 * exactly that, so `seal` threw for every Keystore key, the key store reported "not stored", and SauceNAO stayed
 * `NOT_CONFIGURED` no matter what the user typed. Encrypting with no IV parameter and reading `cipher.iv` works
 * for both a Keystore key and a plain JVM key; decrypting *with* the stored IV is allowed (the restriction only
 * protects against nonce reuse on encryption).
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

    /** A random IV, for callers that must supply one themselves. The Keystore path must not. */
    fun newIv(): ByteArray = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Seals [plain] under [key], letting the cipher generate the IV.
     *
     * The IV is read back from `cipher.iv` and prefixed to the ciphertext, which is the only form that works
     * with an `AndroidKeyStore` key.
     */
    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IllegalStateException("the cipher returned no IV")
        if (iv.size != IV_BYTES) {
            throw IllegalStateException("unexpected GCM IV length ${iv.size}")
        }
        return iv + cipher.doFinal(plain)
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
