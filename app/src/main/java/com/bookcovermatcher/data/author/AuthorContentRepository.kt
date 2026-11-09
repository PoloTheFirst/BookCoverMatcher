// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.author

import com.bookcovermatcher.core.author.AuthorContentValidator
import com.bookcovermatcher.core.author.AuthorImageRefs
import com.bookcovermatcher.core.author.AuthorProfile
import com.bookcovermatcher.core.author.AuthorSource
import com.bookcovermatcher.core.author.AuthorUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the Author page is showing right now.
 *
 * [images] is keyed by the URL *as the README writes it*, so the renderer can look a picture up without knowing
 * anything about GitHub's blob pages or about the cache. A URL missing from the map is drawn as a labelled link,
 * never as a broken-image icon.
 *
 * [P] is the picture type: `DecodedImage` in the app, a cheap stand-in in tests, so the refresh logic can be
 * exercised on the JVM without an Android bitmap.
 */
data class AuthorState<P>(
    val markdown: String = "",
    val source: AuthorSource = AuthorSource.BUNDLED,
    val images: Map<String, P> = emptyMap(),
    val refreshing: Boolean = false,
    val refreshedAt: Long? = null,
    /** A short, safe label for why the last refresh did not take effect; never a response body. */
    val lastFailure: String? = null,
)

/**
 * Keeps the Author page current (v2.6.0 amendment).
 *
 * Three rules shape it:
 *
 *  * **Show something immediately, refresh afterwards.** [loadCached] publishes the cached README and pictures (or
 *    the copy bundled in the APK) with no network at all; [refresh] then updates them in the background.
 *  * **One refresh per launch.** [refresh] is guarded by a single-flight flag, so a second caller (a
 *    recomposition, a second ViewModel, a fast restart) joins the work already running.
 *  * **Never lose a good copy.** A response is committed to the cache only after it passes
 *    [AuthorContentValidator]; if anything fails, the previous README and pictures stay exactly as they were.
 *
 * No Android type appears beyond the already-decoded [DecodedImage], and the network, the cache and the decoder are
 * injected, so the refresh logic is unit-tested with fakes.
 */
class AuthorContentRepository<P>(
    private val cache: AuthorFileCache,
    private val remote: AuthorRemote,
    private val bundledReadme: () -> String,
    private val decode: (ByteArray) -> P?,
    private val readmeUrl: String = AuthorProfile.README_RAW_URL,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(AuthorState<P>(markdown = "", refreshing = false))
    val state: StateFlow<AuthorState<P>> = _state.asStateFlow()

    /** True while a refresh owns the network, so a second caller is ignored rather than duplicated. */
    private val inFlight = AtomicBoolean(false)

    /** The cached copy if there is one, otherwise the copy packaged in the APK. Local reads only. */
    fun loadCached() {
        val cached = cache.readReadme()
        val markdown = cached ?: bundledReadme()
        _state.value = _state.value.copy(
            markdown = markdown,
            source = if (cached != null) AuthorSource.CACHED else AuthorSource.BUNDLED,
            images = decodeCachedImages(markdown),
        )
    }

    /**
     * Fetches the README and its pictures once.
     *
     * @return true when something was updated. A `false` result leaves the previous content in place.
     */
    suspend fun refresh(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        _state.value = _state.value.copy(refreshing = true, lastFailure = null)
        var failure: String? = null
        var updated = false
        try {
            var markdown = _state.value.markdown.ifBlank { cache.readReadme() ?: bundledReadme() }

            val meta = cache.readReadmeMeta()
            when (val result = remote.get(readmeUrl, meta.etag, meta.lastModified, AuthorContentValidator.MAX_README_BYTES)) {
                is FetchResult.Ok -> {
                    if (AuthorContentValidator.acceptsReadme(result.bytes, result.contentType)) {
                        val text = String(result.bytes, Charsets.UTF_8)
                        if (cache.writeReadme(text)) {
                            cache.writeReadmeMeta(AuthorFileCache.Meta(result.etag, result.lastModified))
                            markdown = text
                            updated = true
                        }
                    } else {
                        failure = "readme-rejected"
                    }
                }
                FetchResult.NotModified -> Unit
                is FetchResult.Failed -> failure = result.reason
            }

            val images = refreshImages(markdown)
            _state.value = _state.value.copy(
                markdown = markdown,
                source = if (updated) AuthorSource.REFRESHED else _state.value.source,
                images = images,
                refreshedAt = now(),
                lastFailure = failure,
            )
            return updated
        } finally {
            _state.value = _state.value.copy(refreshing = false)
            inFlight.set(false)
        }
    }

    /**
     * Downloads every picture the README references, reusing the cache when the server says it is unchanged.
     *
     * Pictures are revalidated on every launch even when the README itself is unchanged, because a Shields.io badge
     * and the contribution graph are generated: the README says the same thing while the picture behind it moves.
     */
    private suspend fun refreshImages(markdown: String): Map<String, P> {
        val current = _state.value.images.toMutableMap()
        val refs = AuthorImageRefs.of(markdown)
        refs.forEach { original ->
            val resolved = AuthorUrls.resolve(original) ?: return@forEach
            val meta = cache.readImageMeta(resolved)
            val result = remote.get(resolved, meta.etag, meta.lastModified, AuthorContentValidator.MAX_IMAGE_BYTES)
            if (result is FetchResult.Ok) {
                if (AuthorContentValidator.acceptsImage(result.bytes) && cache.writeImage(resolved, result.bytes)) {
                    cache.writeImageMeta(resolved, AuthorFileCache.Meta(result.etag, result.lastModified))
                    decode(result.bytes)?.let { current[original] = it }
                }
            } else if (current[original] == null) {
                cache.readImage(resolved)?.let { bytes -> decode(bytes)?.let { current[original] = it } }
            }
        }
        cache.retainOnly(refs.mapNotNull { AuthorUrls.resolve(it) })
        // Pictures the README no longer mentions must leave the screen as well as the disk.
        return current.filterKeys { key -> key in refs }
    }

    /** Decodes whatever is already cached, so the first frame has the pictures it had last time. */
    private fun decodeCachedImages(markdown: String): Map<String, P> {
        val out = mutableMapOf<String, P>()
        AuthorImageRefs.of(markdown).forEach { original ->
            val resolved = AuthorUrls.resolve(original) ?: return@forEach
            cache.readImage(resolved)?.let { bytes -> decode(bytes)?.let { out[original] = it } }
        }
        return out
    }
}

