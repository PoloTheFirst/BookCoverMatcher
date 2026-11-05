package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.BoundedImageCache
import com.bookcovermatcher.core.secret.AesGcmSecretBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — the bounded cover cache and the encryption envelope behind the SauceNAO key.
 *
 * Both are policies rather than plumbing: the cache must be bounded and predictable (LRU, byte budget, refusals),
 * and the secret envelope must really encrypt — a round trip, a tamper and a wrong key, with real bytes.
 */
class CoverCacheTest {

    private fun bytes(size: Int) = ByteArray(size) { it.toByte() }

    @Test
    fun `a hit is a hit and a blank key is a miss, not an error`() {
        val cache = BoundedImageCache(maxBytes = 1_000, maxEntryBytes = 500)
        assertTrue(cache.put("https://example.test/a.jpg", bytes(100)))
        assertArrayEquals(bytes(100), cache.get("https://example.test/a.jpg"))
        assertEquals(1, cache.hits)
        assertEquals(0, cache.misses)
        assertNull(cache.get(""))
        assertNull(cache.get(null))
        assertEquals(2, cache.misses)
    }

    @Test
    fun `the byte budget evicts the least recently used entry`() {
        val cache = BoundedImageCache(maxBytes = 300, maxEntryBytes = 200)
        cache.put("a", bytes(120))
        cache.put("b", bytes(120))
        // Touch "a" so "b" is now the least recently used.
        assertNotNull(cache.get("a"))
        cache.put("c", bytes(120))
        assertNotNull("the recently used entry survives", cache.get("a"))
        assertNull("the least recently used entry was evicted", cache.get("b"))
        assertNotNull(cache.get("c"))
        assertEquals(1, cache.evictions)
        assertTrue(cache.sizeBytes <= 300)
    }

    @Test
    fun `an oversized entry is refused instead of flushing the cache`() {
        val cache = BoundedImageCache(maxBytes = 1_000, maxEntryBytes = 200)
        cache.put("keep", bytes(150))
        assertFalse(cache.put("huge", bytes(500)))
        assertEquals(1, cache.refusals)
        assertNotNull("the existing entry is untouched", cache.get("keep"))
        assertFalse(cache.put("", bytes(10)))
        assertFalse(cache.put("empty", ByteArray(0)))
        assertEquals(3, cache.refusals)
    }

    @Test
    fun `invalidation drops one entry and clear drops all of them`() {
        val cache = BoundedImageCache(maxBytes = 1_000, maxEntryBytes = 500)
        cache.put("a", bytes(50))
        cache.put("b", bytes(50))
        assertTrue(cache.invalidate("a"))
        assertFalse(cache.invalidate("a"))
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0, cache.sizeBytes)
        assertNull(cache.get("b"))
    }

    @Test
    fun `replacing a value keeps the byte count honest`() {
        val cache = BoundedImageCache(maxBytes = 1_000, maxEntryBytes = 500)
        cache.put("a", bytes(100))
        cache.put("a", bytes(300))
        assertEquals(300, cache.sizeBytes)
        assertEquals(1, cache.size)
        assertTrue(cache.describe().contains("entries"))
    }
}

/** The AES-GCM envelope: real crypto, real failure modes. */
class SecretBoxTest {

    @Test
    fun `a sealed value can be opened only with its own key`() {
        val key = AesGcmSecretBox.newKey()
        val sealed = AesGcmSecretBox.seal(key, "saucenao-key-value".toByteArray())
        val opened = AesGcmSecretBox.open(key, sealed)
        assertEquals("saucenao-key-value", String(opened, Charsets.UTF_8))

        val other = AesGcmSecretBox.newKey()
        val failed = runCatching { AesGcmSecretBox.open(other, sealed) }
        assertTrue("another key must not open it", failed.isFailure)
        assertTrue(failed.exceptionOrNull() is AesGcmSecretBox.TamperedSecretException)
    }

    @Test
    fun `a modified value fails authentication instead of decrypting`() {
        val key = AesGcmSecretBox.newKey()
        val sealed = AesGcmSecretBox.seal(key, "saucenao-key-value".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        val failed = runCatching { AesGcmSecretBox.open(key, sealed) }
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull() is AesGcmSecretBox.TamperedSecretException)
    }

    @Test
    fun `each seal uses a fresh iv, so the same key never yields the same bytes`() {
        val key = AesGcmSecretBox.newKey()
        val first = AesGcmSecretBox.seal(key, "same".toByteArray())
        val second = AesGcmSecretBox.seal(key, "same".toByteArray())
        assertFalse(first.contentEquals(second))
        assertEquals(AesGcmSecretBox.IV_BYTES + 4 + 16, first.size)
    }

    @Test
    fun `the stored form round-trips through base64 and rejects nonsense`() {
        val key = AesGcmSecretBox.newKey()
        val sealed = AesGcmSecretBox.seal(key, "abcdefgh".toByteArray())
        val text = AesGcmSecretBox.encode(sealed)
        assertArrayEquals(sealed, AesGcmSecretBox.decode(text))
        assertTrue(runCatching { AesGcmSecretBox.decode("not base64 !!") }.isFailure)
        assertTrue(runCatching { AesGcmSecretBox.open(key, ByteArray(4)) }.isFailure)
    }

    // ---- the v2.5.0-dev.3 defect: who chooses the IV ---------------------------------------------

    @Test
    fun `seal never supplies an IV to the cipher, so a Keystore key can be used`() {
        // The regression this pins: v2.5.0-dev.2 passed its own GCMParameterSpec to Cipher.init, which an
        // AndroidKeyStore AES/GCM key rejects with CALLER_NONCE_PROHIBITED, so no SauceNAO key could ever be
        // stored. Here the IV must come from the cipher and be written into the envelope.
        val key = AesGcmSecretBox.newKey()
        val sealed = AesGcmSecretBox.seal(key, "x".toByteArray())
        val iv = sealed.copyOfRange(0, AesGcmSecretBox.IV_BYTES)
        assertEquals("the envelope starts with the cipher's own IV", AesGcmSecretBox.IV_BYTES, iv.size)
        assertFalse("an all-zero IV would mean nothing was generated", iv.all { it == 0.toByte() })
        assertEquals("the key still opens its own envelope", "x", String(AesGcmSecretBox.open(key, sealed)))
    }

    @Test
    fun `every seal uses a fresh IV, even for identical plaintext`() {
        val key = AesGcmSecretBox.newKey()
        val ivs = (1..8).map { AesGcmSecretBox.seal(key, "same".toByteArray()).copyOfRange(0, AesGcmSecretBox.IV_BYTES) }
        assertEquals("no IV may repeat", ivs.size, ivs.map { it.toList() }.distinct().size)
    }
}
