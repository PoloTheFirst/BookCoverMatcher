package com.bookcovermatcher.online

import android.graphics.BitmapFactory
import com.bookcovermatcher.core.online.BoundedImageCache
import com.bookcovermatcher.core.online.reverse.CoverImageExtractor
import com.bookcovermatcher.core.online.reverse.DiscoveryImageSource
import com.bookcovermatcher.core.online.reverse.ExtractedCoverImage
import com.bookcovermatcher.core.online.reverse.SafeImageUrl

/**
 * Gets the picture for one reverse-image candidate, in the order the release specifies:
 *
 *  1. the image the **provider** returned with the result (SauceNAO's own thumbnail);
 *  2. otherwise the image the result's **own page** declares (`og:image`, then Twitter cards, then
 *     `<link rel="image_src">` / `itemprop="image"`);
 *  3. otherwise nothing — the card shows its placeholder and stays a perfectly valid result.
 *
 * Everything is guarded: the page URL and every image URL (and every redirect hop inside the fetcher) must pass
 * [SafeImageUrl], the fetched bytes must be a decodable image of sane dimensions, and every failure is reported as
 * "no image" rather than as a provider error. The HTML is never executed and no browser is involved.
 *
 * **Caching.** The heavy part — the image bytes — goes into the existing [BoundedImageCache] (byte-budgeted LRU),
 * keyed by image URL. The only new state is a small index from a result page to the image it declared, so a second
 * card from the same page does not refetch the HTML. Dismissing a card never evicts anything: the cache belongs to
 * the loader, not to the card.
 */
class DiscoveryCoverLoader(
    private val fetcher: DiscoveryWebFetcher = DiscoveryWebFetcher(),
    private val images: BoundedImageCache = BoundedImageCache(),
    private val maxPages: Int = MAX_PAGES,
) {

    /** One usable picture, with the provenance the UI shows. */
    class LoadedCover(val bytes: ByteArray, val imageUrl: String, val source: DiscoveryImageSource)

    /**
     * The extracted image for each page already inspected. `null` value = "this page declares nothing usable", which
     * is remembered so the page is not fetched again for another card.
     */
    private val pageIndex = object : LinkedHashMap<String, ExtractedCoverImage?>(16, 0.75f, true) {}

    /** @return a usable picture, or null when there is none (never throws) */
    fun load(providerImageUrl: String?, pageUrl: String?): LoadedCover? {
        providerImage(providerImageUrl)?.let { return it }
        val page = pageUrl?.trim()?.takeIf { SafeImageUrl.isFetchable(it) }
        if (page == null) {
            android.util.Log.d(TAG, "no fetchable page for a candidate (${pageUrl?.take(60)})")
            return null
        }
        val extracted = extractFor(page) ?: return null
        val bytes = imageBytes(extracted.url) ?: return null
        return LoadedCover(bytes, extracted.url, extracted.source)
    }

    fun clear() {
        images.clear()
        pageIndex.clear()
    }

    private fun providerImage(url: String?): LoadedCover? {
        val clean = url?.trim()?.takeIf { SafeImageUrl.isFetchable(it) } ?: return null
        val bytes = imageBytes(clean) ?: return null
        return LoadedCover(bytes, clean, DiscoveryImageSource.PROVIDER)
    }

    private fun extractFor(page: String): ExtractedCoverImage? {
        if (pageIndex.containsKey(page)) return pageIndex[page]
        val extracted = try {
            val fetched = fetcher.getPage(page)
            android.util.Log.d(TAG, "page ${SafeImageUrl.host(page)} ${fetched.bytes.size}B ${fetched.contentType}")
            CoverImageExtractor.fromHtml(String(fetched.bytes, Charsets.UTF_8), page)
        } catch (e: ItemUnavailable) {
            android.util.Log.d(TAG, "page ${SafeImageUrl.host(page)} failed: ${e.message}")
            null
        } catch (e: Exception) {
            android.util.Log.d(TAG, "page ${SafeImageUrl.host(page)} crashed: ${e.message}")
            null
        }
        remember(page, extracted)
        return extracted
    }

    private fun remember(page: String, extracted: ExtractedCoverImage?) {
        pageIndex[page] = extracted
        while (pageIndex.size > maxPages) {
            val eldest = pageIndex.keys.firstOrNull() ?: break
            pageIndex.remove(eldest)
        }
    }

    private fun imageBytes(url: String): ByteArray? {
        images.get(url)?.let { return it }
        val bytes = try {
            val fetched = fetcher.getImage(url)
            android.util.Log.d(TAG, "image ${SafeImageUrl.host(url)} ${fetched.bytes.size}B ${fetched.contentType}")
            if (isUsableImage(fetched.bytes)) fetched.bytes else null
        } catch (e: ItemUnavailable) {
            android.util.Log.d(TAG, "image ${SafeImageUrl.host(url)} failed: ${e.message}")
            null
        } catch (e: Exception) {
            android.util.Log.d(TAG, "image ${SafeImageUrl.host(url)} crashed: ${e.message}")
            null
        } ?: return null
        images.put(url, bytes)
        return bytes
    }

    /**
     * True only for bytes that actually decode to a sane raster.
     *
     * `BitmapFactory` with `inJustDecodeBounds` reads the header without allocating the pixels, so a hostile or
     * broken payload cannot exhaust memory here; a declared 400 KB file that expands to a 20000×20000 bitmap is
     * refused before a single row is allocated.
     */
    private fun isUsableImage(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_IMAGE_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            bounds.outWidth in 1..MAX_IMAGE_EDGE && bounds.outHeight in 1..MAX_IMAGE_EDGE
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "DiscoveryCover"
        const val MAX_PAGES = 32
        const val MIN_IMAGE_BYTES = 512
        const val MAX_IMAGE_EDGE = 4096
    }
}
