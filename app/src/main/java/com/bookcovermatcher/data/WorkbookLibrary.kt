package com.bookcovermatcher.data

import com.bookcovermatcher.core.wishlist.MiniJson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** One workbook the app keeps a copy of. [id] is the stable identity; the display name never is. */
data class SavedWorkbook(
    val id: String,
    val displayName: String,
    val originalName: String,
    val format: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val pinned: Boolean,
    /** Counts from the last successful parse; null until the workbook has been opened. */
    val sheetCount: Int? = null,
    val commentCount: Int? = null,
    val imageCount: Int? = null,
)

/** What an import actually did. */
sealed interface ImportResult {
    data class Added(val workbook: SavedWorkbook) : ImportResult
    /** The exact same bytes are already saved; nothing was copied again. */
    data class AlreadySaved(val workbook: SavedWorkbook) : ImportResult
    data class Rejected(val message: String) : ImportResult
}

/**
 * The app-managed "Saved workbooks" library.
 *
 * The workbook bytes live in `files/workbooks/<id>.<xlsx|xlsm>`; metadata lives in `library.json` next
 * to them. Identity is the **id**, never the display name and never the original path, so a saved
 * workbook keeps working when the picked file disappears, and two files with the same name can coexist
 * (the second import gets a new id instead of overwriting the first).
 *
 * Every write is temp-file + rename, a failed import removes its partial copy, and a corrupt metadata
 * file is quarantined rather than crashing the library.
 */
class WorkbookLibrary(private val dir: File) {

    companion object {
        const val SCHEMA_VERSION = 1
        val SUPPORTED = setOf("xlsx", "xlsm")
    }

    private fun indexFile() = File(dir, "library.json")

    /** Pinned first, then most recently opened, then most recently imported; ties by id. */
    fun list(): List<SavedWorkbook> = readAll().sortedWith(
        compareByDescending<SavedWorkbook> { it.pinned }
            .thenByDescending { it.lastOpenedAt ?: 0L }
            .thenByDescending { it.importedAt }
            .thenBy { it.id },
    )

    fun byId(id: String): SavedWorkbook? = readAll().firstOrNull { it.id == id }

    fun fileOf(entry: SavedWorkbook): File = File(dir, entry.fileName)

    /**
     * Copies [input] into app storage and records it.
     *
     * Rejects anything that is not a `.xlsx`/`.xlsm` file whose bytes start with a ZIP signature — the
     * same validation the workbook staging path uses. The copy is hashed while it is written; a failure
     * at any point deletes the partial file and leaves the library untouched.
     */
    fun import(input: InputStream, originalName: String, now: Long = System.currentTimeMillis()): ImportResult {
        val cleanName = originalName.substringAfterLast('/').substringAfterLast('\\').trim()
        val format = cleanName.substringAfterLast('.', "").lowercase()
        if (format !in SUPPORTED) return ImportResult.Rejected("Only .xlsx and .xlsm workbooks can be saved.")
        if (cleanName.isEmpty()) return ImportResult.Rejected("The selected file has no name.")

        if (!dir.isDirectory && !dir.mkdirs()) return ImportResult.Rejected("App storage is unavailable.")

        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = File(dir, "import.tmp")
        tmp.delete()
        var total = 0L
        try {
            tmp.outputStream().use { out ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            return ImportResult.Rejected("The file could not be copied: ${e.message ?: "read error"}")
        } finally {
            runCatching { input.close() }
        }

        if (total <= 0L) {
            tmp.delete()
            return ImportResult.Rejected("The selected file is empty.")
        }
        val head = ByteArray(4)
        val isZip = runCatching { tmp.inputStream().use { it.read(head) } }.getOrDefault(-1) == 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        if (!isZip) {
            tmp.delete()
            return ImportResult.Rejected("That file is not an .xlsx/.xlsm workbook.")
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val existing = readAll()
        existing.firstOrNull { it.sha256 == sha }?.let { same ->
            tmp.delete()
            return ImportResult.AlreadySaved(same)
        }

        val id = uniqueId(sha, existing)
        val fileName = "$id.$format"
        val target = File(dir, fileName)
        if (target.exists()) {
            tmp.delete()
            return ImportResult.Rejected("A workbook with this identity already exists.")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return ImportResult.Rejected("The workbook could not be saved.")
        }

        val entry = SavedWorkbook(
            id = id,
            displayName = cleanName,
            originalName = cleanName,
            format = format,
            fileName = fileName,
            sizeBytes = total,
            sha256 = sha,
            importedAt = now,
            lastOpenedAt = null,
            pinned = false,
        )
        val next = existing + entry
        if (!writeAll(next)) {
            target.delete()
            return ImportResult.Rejected("The saved-workbook list could not be updated.")
        }
        return ImportResult.Added(entry)
    }

    /** Renames the user-facing name only: the stored file, its bytes and the id never change. */
    fun rename(id: String, displayName: String): SavedWorkbook? {
        val name = displayName.trim()
        if (name.isEmpty()) return null
        val list = readAll()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = list[index].copy(displayName = name)
        val next = list.toMutableList().also { it[index] = updated }
        return if (writeAll(next)) updated else null
    }

    fun setPinned(id: String, pinned: Boolean): SavedWorkbook? {
        val list = readAll()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = list[index].copy(pinned = pinned)
        val next = list.toMutableList().also { it[index] = updated }
        return if (writeAll(next)) updated else null
    }

    /** Records that the workbook was opened now; drives the recent list. */
    fun touch(id: String, now: Long = System.currentTimeMillis()): SavedWorkbook? {
        val list = readAll()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = list[index].copy(lastOpenedAt = now)
        val next = list.toMutableList().also { it[index] = updated }
        return if (writeAll(next)) updated else null
    }

    /**
     * Removes the library entry and its app-managed copy.
     *
     * [entryRemoved] is false when the id is unknown. [fileDeleted] reports whether the copy is gone;
     * a copy that could not be deleted is reported instead of leaving the caller to guess, and the
     * metadata is still cleaned so a broken entry cannot linger.
     */
    fun remove(id: String): Pair<Boolean, Boolean> {
        val list = readAll()
        val entry = list.firstOrNull { it.id == id } ?: return false to false
        val deleted = try {
            !fileOf(entry).exists() || fileOf(entry).delete()
        } catch (e: Exception) {
            false
        }
        val next = list.filterNot { it.id == id }
        writeAll(next)
        return true to deleted
    }

    /** Bytes occupied by the saved copies (not by caches). */
    fun bytes(): Long = readAll().sumOf { entry ->
        runCatching { fileOf(entry).length() }.getOrDefault(0L)
    }

    /** Stores the counts of the last successful parse, so the library can show them without parsing. */
    fun setSummary(id: String, sheets: Int, comments: Int, images: Int): SavedWorkbook? {
        val list = readAll()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = list[index].copy(sheetCount = sheets, commentCount = comments, imageCount = images)
        val next = list.toMutableList().also { it[index] = updated }
        return if (writeAll(next)) updated else null
    }

    // ---------------------------------------------------------------------------------------------

    private fun uniqueId(sha: String, existing: List<SavedWorkbook>): String {
        val base = sha.take(12)
        if (existing.none { it.id == base }) return base
        var index = 2
        while (existing.any { it.id == "$base-$index" }) index++
        return "$base-$index"
    }

    private fun readAll(): List<SavedWorkbook> {
        val file = indexFile()
        if (!file.isFile) return emptyList()
        return try {
            val root = MiniJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return emptyList()
            if ((root["schema"] as? Double)?.toInt() != SCHEMA_VERSION) return emptyList()
            (root["workbooks"] as? List<*>).orEmpty().mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"] as? String ?: return@mapNotNull null
                val name = map["name"] as? String ?: return@mapNotNull null
                val fileName = map["file"] as? String ?: return@mapNotNull null
                SavedWorkbook(
                    id = id,
                    displayName = name,
                    originalName = map["original"] as? String ?: name,
                    format = map["format"] as? String ?: "xlsx",
                    fileName = fileName,
                    sizeBytes = (map["bytes"] as? Double)?.toLong() ?: 0L,
                    sha256 = map["sha256"] as? String ?: id,
                    importedAt = (map["imported"] as? Double)?.toLong() ?: 0L,
                    lastOpenedAt = (map["opened"] as? Double)?.toLong(),
                    pinned = map["pinned"] == true,
                    sheetCount = (map["sheets"] as? Double)?.toInt(),
                    commentCount = (map["comments"] as? Double)?.toInt(),
                    imageCount = (map["images"] as? Double)?.toInt(),
                )
            }
        } catch (e: Exception) {
            // A corrupt index is preserved for inspection and treated as empty; the copies stay.
            runCatching { file.renameTo(File(dir, "library.json.bad")) }
            emptyList()
        }
    }

    private fun writeAll(entries: List<SavedWorkbook>): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val root = linkedMapOf<String, Any?>(
            "schema" to SCHEMA_VERSION,
            "workbooks" to entries.map { entry ->
                linkedMapOf(
                    "id" to entry.id,
                    "name" to entry.displayName,
                    "original" to entry.originalName,
                    "format" to entry.format,
                    "file" to entry.fileName,
                    "bytes" to entry.sizeBytes,
                    "sha256" to entry.sha256,
                    "imported" to entry.importedAt,
                    "opened" to entry.lastOpenedAt,
                    "pinned" to entry.pinned,
                    "sheets" to entry.sheetCount,
                    "comments" to entry.commentCount,
                    "images" to entry.imageCount,
                )
            },
        )
        val tmp = File(dir, "library.json.tmp")
        return try {
            tmp.writeText(MiniJson.write(root), Charsets.UTF_8)
            val target = indexFile()
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
