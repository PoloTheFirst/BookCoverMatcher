package com.bookcovermatcher.core.wishlist

import java.io.File
import java.io.IOException
import java.util.UUID

/** One saved match inside a wishlist record. [thumbName] is a file name inside the repository's thumbnail folder. */
class WishlistMatch(
    val cell: String,
    val cosine: Float,
    val percent: Float,
    val source: String,
    val thumbName: String?,
)

class WishlistRecord(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val scanThumbName: String?,
    val matches: List<WishlistMatch>,
)

/** A match about to be saved (thumbnail still as bytes). */
class NewWishlistMatch(
    val cell: String,
    val cosine: Float,
    val percent: Float,
    val source: String,
    val thumbJpeg: ByteArray?,
)

/**
 * The wishlist: one `records.json` plus one JPEG per thumbnail, inside [root].
 * Equivalent of v1's `localStorage` list (same fields, same `#N` default names, same timestamp),
 * but thumbnails live in files instead of base64 strings so the list stays fast with many records.
 *
 * Not thread-safe by itself; the app calls it from a single-threaded dispatcher.
 */
class WishlistRepository(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { "rec_" + UUID.randomUUID().toString().replace("-", "").take(16) },
) {
    private val recordsFile = File(root, "records.json")
    private val thumbDir = File(root, "thumbs")

    fun thumbFile(name: String): File = File(thumbDir, File(name).name)

    fun load(): List<WishlistRecord> {
        if (!recordsFile.isFile) return emptyList()
        return try {
            decode(recordsFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // keep the unreadable file for manual recovery instead of silently overwriting it
            recordsFile.renameTo(File(root, "records.json.corrupt"))
            emptyList()
        }
    }

    /** Adds a record named `#<count + 1>` at the end of the list (v1 naming). */
    fun add(scanThumbJpeg: ByteArray?, matches: List<NewWishlistMatch>): WishlistRecord {
        val list = load().toMutableList()
        val id = newId()
        thumbDir.mkdirs()

        val scanName = scanThumbJpeg?.let { writeThumb("${id}_s.jpg", it) }
        val saved = matches.mapIndexed { i, m ->
            WishlistMatch(m.cell, m.cosine, m.percent, m.source, m.thumbJpeg?.let { writeThumb("${id}_$i.jpg", it) })
        }
        val record = WishlistRecord(id, "#" + (list.size + 1), clock(), scanName, saved)
        list.add(record)
        save(list)
        return record
    }

    /** Returns false if the record does not exist. A blank name keeps the old one (v1 behaviour). */
    fun rename(id: String, name: String): Boolean {
        val list = load()
        val old = list.firstOrNull { it.id == id } ?: return false
        val trimmed = name.trim().ifEmpty { old.name }
        save(list.map { if (it.id == id) WishlistRecord(it.id, trimmed, it.createdAtMillis, it.scanThumbName, it.matches) else it })
        return true
    }

    fun delete(id: String): Boolean {
        val list = load()
        val gone = list.firstOrNull { it.id == id } ?: return false
        save(list.filter { it.id != id })
        gone.scanThumbName?.let { thumbFile(it).delete() }
        gone.matches.forEach { m -> m.thumbName?.let { thumbFile(it).delete() } }
        return true
    }

    // ---------- persistence ----------

    private fun writeThumb(name: String, bytes: ByteArray): String {
        thumbFile(name).writeBytes(bytes)
        return name
    }

    private fun save(list: List<WishlistRecord>) {
        root.mkdirs()
        val tmp = File(root, "records.json.tmp")
        tmp.writeText(encode(list), Charsets.UTF_8)
        if (recordsFile.exists() && !recordsFile.delete()) throw IOException("cannot replace ${recordsFile.name}")
        if (!tmp.renameTo(recordsFile)) throw IOException("cannot write ${recordsFile.name}")
    }

    companion object {
        private const val VERSION = 1

        fun encode(list: List<WishlistRecord>): String = MiniJson.write(
            mapOf(
                "version" to VERSION,
                "records" to list.map { r ->
                    mapOf(
                        "id" to r.id,
                        "name" to r.name,
                        "createdAt" to r.createdAtMillis,
                        "scanThumb" to r.scanThumbName,
                        "matches" to r.matches.map { m ->
                            mapOf(
                                "cell" to m.cell,
                                "cosine" to m.cosine.toDouble(),
                                "percent" to m.percent.toDouble(),
                                "source" to m.source,
                                "thumb" to m.thumbName,
                            )
                        },
                    )
                },
            )
        )

        @Suppress("UNCHECKED_CAST")
        fun decode(text: String): List<WishlistRecord> {
            val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw MiniJson.JsonException("not an object")
            val records = root["records"] as? List<Any?> ?: return emptyList()
            return records.mapNotNull { raw ->
                val r = raw as? Map<String, Any?> ?: return@mapNotNull null
                val id = r["id"] as? String ?: return@mapNotNull null
                val matches = (r["matches"] as? List<Any?>).orEmpty().mapNotNull { mr ->
                    val m = mr as? Map<String, Any?> ?: return@mapNotNull null
                    WishlistMatch(
                        cell = m["cell"] as? String ?: return@mapNotNull null,
                        cosine = (m["cosine"] as? Double)?.toFloat() ?: 0f,
                        percent = (m["percent"] as? Double)?.toFloat() ?: 0f,
                        source = m["source"] as? String ?: "",
                        thumbName = m["thumb"] as? String,
                    )
                }
                WishlistRecord(
                    id = id,
                    name = r["name"] as? String ?: "",
                    createdAtMillis = (r["createdAt"] as? Double)?.toLong() ?: 0L,
                    scanThumbName = r["scanThumb"] as? String,
                    matches = matches,
                )
            }
        }
    }
}
