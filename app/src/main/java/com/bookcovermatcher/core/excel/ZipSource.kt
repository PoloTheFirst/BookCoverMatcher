package com.bookcovermatcher.core.excel

import java.io.Closeable
import java.util.zip.ZipFile

/**
 * Minimal read-only view of an OOXML package (.xlsx / .xlsm).
 *
 * [entryNames] must be in central-directory order, because the v1 workflow resolves duplicate cell
 * addresses by "first part wins" and the parts are visited in that order.
 */
interface ZipSource {
    val entryNames: List<String>
    fun contains(name: String): Boolean
    fun read(name: String): ByteArray?
    fun readText(name: String): String? = read(name)?.toString(Charsets.UTF_8)
}

/** [ZipSource] backed by `java.util.zip.ZipFile` (random access, works on Android too). */
class JavaZipSource(
    private val zip: ZipFile,
    private val maxEntryBytes: Long = 64L * 1024 * 1024,
) : ZipSource, Closeable {

    override val entryNames: List<String> = zip.entries().asSequence().map { it.name }.toList()

    private val nameSet: Set<String> = entryNames.toHashSet()

    override fun contains(name: String): Boolean = name in nameSet

    override fun read(name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        if (entry.size > maxEntryBytes) return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() = zip.close()
}

/** In-memory [ZipSource] (insertion-ordered) used by tests. */
class MapZipSource(private val entries: LinkedHashMap<String, ByteArray>) : ZipSource {
    override val entryNames: List<String> get() = entries.keys.toList()
    override fun contains(name: String) = entries.containsKey(name)
    override fun read(name: String): ByteArray? = entries[name]
}
