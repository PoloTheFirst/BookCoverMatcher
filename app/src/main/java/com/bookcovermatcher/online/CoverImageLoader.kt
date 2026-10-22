package com.bookcovermatcher.online

import android.util.LruCache
import com.bookcovermatcher.core.online.ProductSourceException

/**
 * Downloads the small cover thumbnails the catalogue links to, with an in-memory cache.
 *
 * Deliberately not a general image-loading library: the online panel shows at most ten 200-pixel
 * JPEGs per search, they come from one host we already trust, and they are thrown away when the
 * search is replaced. A failed cover is not an error — the result card simply shows its title.
 *
 * Nothing is written to disk: covers are transient online data and the app's storage stays private.
 */
class CoverImageLoader(
    private val http: HttpJsonClient = HttpJsonClient(readTimeoutMs = 15_000, maxBytes = MAX_COVER_BYTES),
    private val cache: LruCache<String, ByteArray> = defaultCache(),
) {
    /** @return the JPEG/PNG bytes, or null when there is no cover or the fetch failed. */
    fun load(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        return try {
            val bytes = http.getBytes(url, MAX_COVER_BYTES)
            // A 1x1 placeholder or an error page is not worth decoding.
            if (bytes.size < MIN_IMAGE_BYTES) return null
            cache.put(url, bytes)
            bytes
        } catch (e: ProductSourceException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val MAX_COVER_BYTES = 400 * 1024
        const val MIN_IMAGE_BYTES = 512

        /** Roughly 24 covers at ~32 KB. Small enough that it never competes with the model. */
        const val CACHE_SIZE_KB = 800

        fun defaultCache(): LruCache<String, ByteArray> =
            object : LruCache<String, ByteArray>(CACHE_SIZE_KB) {
                override fun sizeOf(key: String, value: ByteArray): Int = (value.size + 1023) / 1024
            }
    }
}
