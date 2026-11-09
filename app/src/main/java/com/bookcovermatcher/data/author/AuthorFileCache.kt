// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.author

import com.bookcovermatcher.core.author.AuthorContentValidator
import java.io.File
import java.security.MessageDigest

/**
 * The on-device cache for the Author page (v2.6.0 amendment).
 *
 * Plain files under `files/author/`: the README, one `.bin` per image keyed by a hash of its URL, and a small
 * `.meta` beside each holding the HTTP validators. Every write goes to a `.part` first and is renamed into place
 * only after it has been validated, so an interrupted refresh can never truncate the copy the user already has —
 * the same rule the workbook library follows.
 *
 * No Android type appears here, so the whole thing is unit-tested on the JVM against a temporary directory.
 */
class AuthorFileCache(private val dir: File) {

    /** What a server told us last time, so the next request can be conditional. */
    data class Meta(val etag: String?, val lastModified: String?) {
        val isEmpty: Boolean get() = etag.isNullOrBlank() && lastModified.isNullOrBlank()
    }

    fun readReadme(): String? = runCatching {
        val file = readmeFile()
        if (!file.isFile) return null
        val text = file.readText()
        if (AuthorContentValidator.acceptsReadme(text.toByteArray())) text else null
    }.getOrNull()

    /** Stores [text] only if it passes validation; returns true when it was committed. */
    fun writeReadme(text: String): Boolean =
        AuthorContentValidator.acceptsReadme(text.toByteArray()) && atomicWrite(readmeFile(), text.toByteArray())

    fun readReadmeMeta(): Meta = readMeta(key(".readme-meta"))

    fun writeReadmeMeta(meta: Meta) = writeMeta(key(".readme-meta"), meta)

    /** The cached bytes for [url], or null when there is none or it no longer looks like an image. */
    fun readImage(url: String): ByteArray? = runCatching {
        val file = imageFile(url)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (AuthorContentValidator.acceptsImage(bytes)) bytes else null
    }.getOrNull()

    fun writeImage(url: String, bytes: ByteArray): Boolean =
        AuthorContentValidator.acceptsImage(bytes) && atomicWrite(imageFile(url), bytes)

    fun readImageMeta(url: String): Meta = readMeta(key("$url.meta"))

    fun writeImageMeta(url: String, meta: Meta) = writeMeta(key("$url.meta"), meta)

    /** Removes cached images whose URL is no longer referenced by the README. */
    fun retainOnly(urls: Collection<String>) {
        val keep = urls.mapTo(HashSet()) { imageFile(it).name }
        runCatching {
            dir.listFiles()?.forEach { file ->
                val name = file.name
                if (name.endsWith(".bin") && name !in keep) file.delete()
            }
        }
    }

    private fun readmeFile() = File(dir, "README.md")

    private fun imageFile(url: String) = File(dir, "img-${key(url)}.bin")

    /** A stable file name for a URL: SHA-256 hex, so no URL character can escape the directory. */
    fun key(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(20).joinToString("") { "%02x".format(it) }
    }

    private fun readMeta(key: String): Meta = runCatching {
        val file = File(dir, "$key.meta")
        if (!file.isFile) return Meta(null, null)
        val lines = file.readLines()
        Meta(lines.getOrNull(0)?.takeIf { it.isNotBlank() }, lines.getOrNull(1)?.takeIf { it.isNotBlank() })
    }.getOrDefault(Meta(null, null))

    private fun writeMeta(key: String, meta: Meta) {
        atomicWrite(File(dir, "$key.meta"), "${meta.etag.orEmpty()}\n${meta.lastModified.orEmpty()}\n".toByteArray())
    }

    /** Write to `.part`, then rename: a reader either sees the old file or the complete new one. */
    private fun atomicWrite(target: File, bytes: ByteArray): Boolean = runCatching {
        dir.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        part.writeBytes(bytes)
        if (part.length() != bytes.size.toLong()) {
            part.delete()
            return false
        }
        if (target.exists() && !target.delete()) {
            part.delete()
            return false
        }
        part.renameTo(target)
    }.getOrDefault(false)
}
