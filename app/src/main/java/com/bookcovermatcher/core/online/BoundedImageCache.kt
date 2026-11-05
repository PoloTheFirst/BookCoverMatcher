package com.bookcovermatcher.core.online

/**
 * A bounded, predictable in-memory cache for cover images.
 *
 * The existing `CoverImageLoader` uses `android.util.LruCache`, which is bounded but cannot be exercised in a
 * unit test and cannot be reasoned about from `core`. This is the policy itself, in plain Kotlin:
 *
 *  * **bytes budget, not entry count** — one 300 KB cover must not cost the same as a 3 KB thumbnail;
 *  * **least-recently-used eviction** (`LinkedHashMap` in access order), so a cover the user keeps scrolling
 *    past survives and a one-off does not;
 *  * **a per-entry ceiling** — a single oversized image is refused instead of flushing the whole cache;
 *  * **invalidation and clear**, so a removed search drops its images and saved data is never touched (this
 *    cache only ever holds transient online data);
 *  * **statistics** (`hits`, `misses`, `evictions`), because "the cache works" is otherwise an opinion.
 *
 * Plain Kotlin/JVM; unit-tested with a fake clock-free policy (no threads, no Android).
 */
class BoundedImageCache(
    /** Upper bound on the total bytes held. */
    val maxBytes: Int = DEFAULT_MAX_BYTES,
    /** A single entry larger than this is refused rather than evicting everything else. */
    val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
) {
    private val entries = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {}

    var hits: Int = 0
        private set
    var misses: Int = 0
        private set
    var evictions: Int = 0
        private set
    var refusals: Int = 0
        private set

    /** Total bytes currently held. */
    var sizeBytes: Int = 0
        private set

    val size: Int get() = entries.size

    fun keys(): Set<String> = entries.keys.toSet()

    /** @return the cached bytes, or null. A null/blank [key] is always a miss and never an error. */
    fun get(key: String?): ByteArray? {
        if (key.isNullOrBlank()) {
            misses++
            return null
        }
        val found = entries[key]
        if (found == null) {
            misses++
            return null
        }
        hits++
        return found
    }

    /**
     * Stores [value] under [key].
     *
     * @return true when it was stored. An oversized, empty or blank-keyed value is refused and counted,
     *         because silently dropping it would make the cache look bigger than it is.
     */
    fun put(key: String?, value: ByteArray?): Boolean {
        if (key.isNullOrBlank() || value == null || value.isEmpty()) {
            refusals++
            return false
        }
        if (value.size > maxEntryBytes || value.size > maxBytes) {
            refusals++
            return false
        }
        entries[key]?.let { old -> sizeBytes -= old.size }
        entries[key] = value
        sizeBytes += value.size
        while (sizeBytes > maxBytes && entries.isNotEmpty()) {
            val eldest = entries.keys.firstOrNull() ?: break
            val removed = entries.remove(eldest) ?: break
            sizeBytes -= removed.size
            evictions++
        }
        return entries.containsKey(key)
    }

    /** Drops one entry, e.g. when a URL stopped being valid. */
    fun invalidate(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val removed = entries.remove(key) ?: return false
        sizeBytes -= removed.size
        return true
    }

    /** Drops everything and both byte and entry counts. Saved data is untouched by definition. */
    fun clear() {
        entries.clear()
        sizeBytes = 0
    }

    /** Combined counters, for the diagnostics panel and for tests. */
    fun describe(): String = "covers ${entries.size} entries, ${sizeBytes / 1024} KB / ${maxBytes / 1024} KB " +
        "(hits $hits, misses $misses, evictions $evictions)"

    companion object {
        /** ~1 MB: about thirty 32 KB thumbnails, small enough never to compete with the ONNX model. */
        const val DEFAULT_MAX_BYTES = 1024 * 1024

        /** A single cover above 512 KB is refused: no catalogue thumbnail is that large. */
        const val DEFAULT_MAX_ENTRY_BYTES = 512 * 1024
    }
}
