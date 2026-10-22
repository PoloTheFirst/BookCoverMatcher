package com.bookcovermatcher.core.online

import java.security.MessageDigest

/**
 * The key of the online-result cache: the SHA-256 of the captured cover's JPEG bytes.
 *
 * It must be a **content** fingerprint, not a file name: the same photo picked twice can arrive with
 * two different names, and two different covers can share one. Hashing is the same kind of identity
 * [com.bookcovermatcher.core.search.IndexCache] uses for workbooks, so the app keeps one idea of
 * "which image is this".
 */
object ImageFingerprint {
    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Per-capture cache of normalised results, in memory only.
 *
 * Temporary search state must never mix with saved data: this class knows nothing about the wishlist
 * and every clear/invalidate path here drops *results*, never items, memberships or thumbnails.
 *
 * [generation] is the stale-response guard: a search remembers the token it started with
 * ([token]) and drops its own callback when [isCurrent] says another capture has taken over. That
 * works even for the one thing this app cannot cancel — a Lens request already inside Python.
 */
class SessionCache<T : Any>(private val maxEntries: Int = 4) {
    private val entries = LinkedHashMap<String, T>()

    /** The capture the current results belong to, or null after [invalidate]. */
    var fingerprint: String? = null
        private set

    /** Bumped whenever the selected capture changes or the cache is cleared. */
    var generation: Long = 0L
        private set

    /**
     * Selects [fingerprint] as the current capture.
     *
     * @return the cached value for the same cover (reuse), or null after invalidating the previous
     *         capture's results, because they cannot belong to this cover.
     */
    fun select(fingerprint: String): T? {
        if (fingerprint != this.fingerprint) {
            this.fingerprint = fingerprint
            entries.clear()
            generation++
        }
        return entries[fingerprint]
    }

    /** Stores the normalised results for [fingerprint], selecting it first if needed. */
    fun store(fingerprint: String, value: T) {
        if (fingerprint != this.fingerprint) select(fingerprint)
        entries[fingerprint] = value
        while (entries.size > maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }

    fun cached(fingerprint: String): T? = entries[fingerprint]

    /** The token a search must carry: a callback holding anything else is stale and is dropped. */
    fun token(): Long = generation

    fun isCurrent(token: Long): Boolean = token == generation

    /** Drops every cached result and the selected capture. Saved data is untouched by definition. */
    fun invalidate() {
        fingerprint = null
        entries.clear()
        generation++
    }
}
