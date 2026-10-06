package com.bookcovermatcher.core.search

import java.io.File
import java.io.IOException

/**
 * Keeps the most recently used [CatalogIndex] files on disk, so asking for the same workbook range again
 * (the usual case: scan one cover after another against one catalogue) skips decoding and embedding.
 */
class IndexCache(private val dir: File, private val maxEntries: Int = 8) {

    private fun file(key: String) = File(dir, "$key.bcmi")

    fun get(key: String): CatalogIndex? {
        val f = file(key)
        if (!f.isFile) return null
        return try {
            val index = f.inputStream().use { CatalogIndexStore.read(it) }
            f.setLastModified(System.currentTimeMillis())
            index
        } catch (e: IOException) {
            f.delete()
            null
        } catch (e: RuntimeException) {
            f.delete()
            null
        }
    }

    fun put(key: String, index: CatalogIndex) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val tmp = File(dir, "$key.tmp")
        try {
            tmp.outputStream().use { CatalogIndexStore.write(index, it) }
            val dst = file(key)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) tmp.delete()
        } catch (e: IOException) {
            tmp.delete()
            return
        }
        prune()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bcmi") } ?: return
        if (files.size <= maxEntries) return
        files.sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { it.delete() }
    }
}
