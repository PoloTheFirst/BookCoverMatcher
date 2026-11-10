// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import com.bookcovermatcher.core.wishlist.MiniJson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Where a saved workbook's bytes came from.
 *
 * `LOCAL` is the only origin a schema-1 library can have, so it is the default of every field that
 * mentions it: an entry that predates OneDrive, and any entry built by [WorkbookLibrary.import], is
 * local without saying so. The local copy is the app's own file either way — an imported OneDrive
 * workbook is **not** read from the network, so nothing about opening or parsing depends on this.
 */
enum class WorkbookOrigin { LOCAL, ONEDRIVE, GOOGLE_DRIVE }

/**
 * What the library remembers about a workbook that came from OneDrive (schema 2).
 *
 * Display and change-detection only: nothing here is a credential. [itemId] is the Graph identity of
 * the remote file, [path] is for the user (it is never resolved), and [eTag]/[lastModified]/[sizeBytes]
 * describe the remote version the local copy corresponds to. [syncedAt] is the last successful
 * download and [checkedAt] the last remote probe, so a row can say *Synced <date>* or *Last check
 * failed* without touching the network to find out.
 *
 * A missing remote value is `null` — never `0`, never an empty string that would look like a real one.
 */
data class RemoteOrigin(
    val itemId: String,
    val path: String = "",
    val eTag: String? = null,
    /**
     * The content tag of the same remote version (v2.6.0-dev.1 Phase 5). Optional and additive: a
     * schema-2 entry written before this field existed simply has none, and a comparison then falls back
     * to the ETag or the timestamp instead of guessing.
     */
    val cTag: String? = null,
    val lastModified: Long? = null,
    val sizeBytes: Long? = null,
    val syncedAt: Long? = null,
    val checkedAt: Long? = null,
)

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
    /**
     * Where the copy came from (schema 2). Defaults to [WorkbookOrigin.LOCAL], which is what every
     * schema-1 entry migrates to and what every locally imported workbook is.
     */
    val origin: WorkbookOrigin = WorkbookOrigin.LOCAL,
    /** Non-null exactly for a [WorkbookOrigin.ONEDRIVE] entry; never holds a token or an account id. */
    val remote: RemoteOrigin? = null,
)

/** What an import actually did. */
sealed interface ImportResult {
    data class Added(val workbook: SavedWorkbook) : ImportResult
    /** The exact same bytes are already saved; nothing was copied again. */
    data class AlreadySaved(val workbook: SavedWorkbook) : ImportResult
    data class Rejected(val message: String) : ImportResult
}

/** What replacing an existing entry's bytes did (v2.6.0-dev.1 Phase 5). */
sealed interface ReplaceResult {
    /**
     * The library now holds the new bytes under the same id. [workbook] carries the **new** `sha256`
     * and size — that hash is the cache key the caller must open the workbook with.
     */
    data class Replaced(val workbook: SavedWorkbook) : ReplaceResult

    /**
     * The downloaded bytes are identical to the ones already saved. Nothing was copied; only the remote
     * metadata moved, so the entry now describes the version OneDrive has.
     */
    data class SameContent(val workbook: SavedWorkbook) : ReplaceResult

    /** The new bytes were refused (or could not be installed); the previous copy is intact. */
    data class Rejected(val message: String) : ReplaceResult

    /** No entry with that id — nothing was touched. */
    data object NotFound : ReplaceResult
}

/**
 * The app-managed "Saved workbooks" library.
 *
 * The workbook bytes live in `files/workbooks/<id>.<xlsx|xlsm>`; metadata lives in `library.json` next
 * to them. Identity is the **id**, never the display name and never the original path, so a saved
 * workbook keeps working when the picked file disappears, and two files with the same name can coexist
 * (the second import gets a new id instead of overwriting the first).
 *
 * Every write is temp-file + replace, a failed import removes its partial copy, and a corrupt metadata
 * file is quarantined rather than crashing the library.
 *
 * **Schema 2 (v2.6.0-dev.1).** A schema-1 library — and a legacy file with no `schema` key at all — is
 * read, migrated in memory, and rewritten once as schema 2 with every entry marked
 * [WorkbookOrigin.LOCAL]. Existing ids, names, file references, pins, recency and parsed counts are
 * carried over unchanged. A library written by a **newer** schema is not read, not rewritten and not
 * downgraded: this build treats it as empty and refuses to write over it.
 */
class WorkbookLibrary(private val dir: File) {

    companion object {
        const val SCHEMA_VERSION = 2

        /** The legacy schema still readable and migrated here: no `origin` field and no remote metadata. */
        const val LEGACY_SCHEMA_VERSION = 1

        val SUPPORTED = setOf("xlsx", "xlsm")
    }

    private fun indexFile() = File(dir, "library.json")

    /**
     * Set by [readAll] when the index on disk was written by a schema this build does not know.
     *
     * While it is set, every write refuses: a downgrade would replace a newer library with this build's
     * schema and lose whatever the newer one stored. The flag belongs to one read, so it is recomputed
     * from the file (never cached across a successful read of a known schema).
     */
    private var indexFromNewerSchema = false

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
    fun import(
        input: InputStream,
        originalName: String,
        now: Long = System.currentTimeMillis(),
        origin: WorkbookOrigin = WorkbookOrigin.LOCAL,
    ): ImportResult {
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
            origin = origin,
        )
        val next = existing + entry
        if (!writeAll(next)) {
            target.delete()
            return ImportResult.Rejected(indexWriteFailureMessage())
        }
        return ImportResult.Added(entry)
    }

    /**
     * Why an index write failed, in the terms the user needs.
     *
     * A refused downgrade is not a disk problem: the list belongs to a newer build, and telling the user
     * "the list could not be updated" would invite them to keep trying something that must not succeed.
     */
    private fun indexWriteFailureMessage(): String = if (indexFromNewerSchema) {
        "The saved-workbook list was written by a newer version of the app, so it was left unchanged."
    } else {
        "The saved-workbook list could not be updated."
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

    /**
     * Records that this entry came from OneDrive, and the remote version the local copy has (schema 2).
     *
     * This is the only writer of the `ONEDRIVE` origin and of [SavedWorkbook.remote]; the later phases of
     * this release call it after a download or a successful remote check. It changes **no** local data:
     * the copy, the id, the file name, the display name, the pin, the recency and the parsed counts are
     * all left exactly as they are, so a later remote failure can never damage what the user already has.
     */
    fun setRemote(id: String, remote: RemoteOrigin): SavedWorkbook? {
        val list = readAll()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = list[index].copy(origin = WorkbookOrigin.ONEDRIVE, remote = remote)
        val next = list.toMutableList().also { it[index] = updated }
        return if (writeAll(next)) updated else null
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Installs [newBytes] as the content of the existing entry [id] (v2.6.0-dev.1 Phase 5).
     *
     * The ordering is what makes this safe, and every step earns its place:
     *
     *  1. The new bytes are copied to `<file>.replacing` and hashed **first**. Nothing the user has is
     *     touched while that happens, so a full disk, a read error or a non-workbook file changes nothing.
     *  2. If the hash equals the saved one there is nothing to copy: only the remote metadata moves, and
     *     the caller is told so ([ReplaceResult.SameContent]).
     *  3. The current file is copied to `<file>.previous` before the swap, so a failure in step 4 or 5 can
     *     put the old bytes back **byte for byte**.
     *  4. The swap is one `ATOMIC_MOVE` inside the library directory: the entry's file reference never
     *     points at a half-written file, not even for an instant.
     *  5. One [writeAll] records the new hash, size and remote version together. If that write fails the
     *     old bytes are restored from the backup, so the index and the file cannot end up disagreeing.
     *
     * The parsed counts are dropped because they describe the *old* bytes; the id, display name, file
     * name, import time, recency and pin are kept, so the user's workbook stays the same workbook.
     *
     * This does not download and does not validate the workbook format beyond what [import] checks: the
     * caller downloads and validates first (see `OneDriveUpdateSession`), and this method only installs.
     */
    fun replaceFrom(
        id: String,
        newBytes: File,
        remote: RemoteOrigin,
    ): ReplaceResult {
        val list = readAll()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return ReplaceResult.NotFound
        val entry = list[index]
        if (!newBytes.isFile || newBytes.length() <= 0L) {
            return ReplaceResult.Rejected("The downloaded file is empty.")
        }
        if (!dir.isDirectory && !dir.mkdirs()) return ReplaceResult.Rejected("App storage is unavailable.")

        val target = fileOf(entry)
        val backup = File(dir, "${entry.fileName}.previous")
        val staging = File(dir, "${entry.fileName}.replacing")

        // Recovery before anything else (v2.6.0-dev.1 Phase 7). A process death during an earlier
        // replacement can leave `<file>.previous` as the **only** surviving copy of this workbook — the
        // bytes that were about to be replaced, with the target gone. Deleting leftovers first would
        // destroy the user's last copy, so a lone backup is put back where it belongs and then treated
        // as the current file.
        //
        // If it cannot be put back, that backup *is* the last copy: it is left alone and the replacement
        // is refused, because deleting leftovers here would destroy the only surviving bytes on a path
        // that was reached precisely because the previous install went wrong (v2.6.0-dev.1 Phase 9).
        if (!target.isFile && backup.isFile) {
            val restored = runCatching { moveReplacing(backup, target) }.isSuccess
            if (!restored) {
                staging.delete()
                return ReplaceResult.Rejected(
                    "The saved copy of this workbook could not be recovered, so nothing was changed.",
                )
            }
        }
        backup.delete()
        staging.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            newBytes.inputStream().use { input ->
                staging.outputStream().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                    }
                }
            }
        } catch (e: Exception) {
            staging.delete()
            return ReplaceResult.Rejected("The downloaded workbook could not be copied: ${e.message ?: "read error"}")
        }

        if (total <= 0L) {
            staging.delete()
            return ReplaceResult.Rejected("The downloaded file is empty.")
        }
        val head = ByteArray(4)
        val isZip = runCatching { staging.inputStream().use { it.read(head) } }.getOrDefault(-1) == 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        if (!isZip) {
            staging.delete()
            return ReplaceResult.Rejected("That file is not an .xlsx/.xlsm workbook.")
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }

        if (sha == entry.sha256) {
            staging.delete()
            val updated = entry.copy(origin = WorkbookOrigin.ONEDRIVE, remote = remote)
            val next = list.toMutableList().also { it[index] = updated }
            return if (writeAll(next)) {
                ReplaceResult.SameContent(updated)
            } else {
                ReplaceResult.Rejected(indexWriteFailureMessage())
            }
        }

        // The old bytes are kept until the new ones are in place *and* recorded.
        val hadOldFile = target.isFile
        if (hadOldFile) {
            try {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                staging.delete()
                backup.delete()
                return ReplaceResult.Rejected("The current copy could not be backed up, so nothing was changed.")
            }
        }

        try {
            moveReplacing(staging, target)
        } catch (e: Exception) {
            staging.delete()
            restore(backup, target, hadOldFile)
            return ReplaceResult.Rejected("The new workbook could not be installed; the previous copy was kept.")
        }

        val updated = entry.copy(
            sizeBytes = total,
            sha256 = sha,
            origin = WorkbookOrigin.ONEDRIVE,
            remote = remote,
            // Counts from the old bytes say nothing about the new ones.
            sheetCount = null,
            commentCount = null,
            imageCount = null,
        )
        val next = list.toMutableList().also { it[index] = updated }
        if (!writeAll(next)) {
            restore(backup, target, hadOldFile)
            return ReplaceResult.Rejected(indexWriteFailureMessage())
        }
        backup.delete()
        return ReplaceResult.Replaced(updated)
    }

    /** One atomic move inside the library directory, falling back only if the platform refuses it. */
    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Puts [backup] back as [target]; with no previous file, the new one is removed instead. */
    private fun restore(backup: File, target: File, hadOldFile: Boolean) {
        runCatching {
            if (hadOldFile && backup.isFile) moveReplacing(backup, target) else target.delete()
        }
        backup.delete()
    }

    private fun uniqueId(sha: String, existing: List<SavedWorkbook>): String {
        val base = sha.take(12)
        if (existing.none { it.id == base }) return base
        var index = 2
        while (existing.any { it.id == "$base-$index" }) index++
        return "$base-$index"
    }

    /**
     * Reads every entry, migrating a schema-1 library to schema 2 on the way.
     *
     * The migration is in memory first: the entries are parsed as `LOCAL`, and only then is the file
     * rewritten. If that rewrite fails the schema-1 file is still on disk and the entries are still
     * returned, so a read-only profile or a full disk degrades to "the library works, it just is not
     * upgraded yet" instead of "the library is empty".
     *
     * A schema this build does not know is **not** touched — no rewrite, no downgrade, and
     * [indexFromNewerSchema] makes later writes refuse too. A document that cannot be parsed keeps the
     * original behaviour: quarantined as `library.json.bad`, read as empty, copies untouched.
     */
    private fun readAll(): List<SavedWorkbook> {
        indexFromNewerSchema = false
        val file = indexFile()
        if (!file.isFile) return emptyList()
        return try {
            val root = MiniJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return emptyList()
            // A missing key is a legacy file, not a broken one: schema 1 was written without `schema`
            // by the earliest builds, and treating that as unsupported would hide a user's library.
            val schema = (root["schema"] as? Double)?.toInt() ?: LEGACY_SCHEMA_VERSION
            when {
                schema > SCHEMA_VERSION -> {
                    indexFromNewerSchema = true
                    emptyList()
                }
                schema == SCHEMA_VERSION || schema == LEGACY_SCHEMA_VERSION -> {
                    val raw = (root["workbooks"] as? List<*>).orEmpty()
                    val entries = raw.mapNotNull { parseEntry(it) }
                    // Rewrite only when every object parsed. A record this build cannot read must not be
                    // deleted from the user's file by a migration it never agreed to — the valid entries
                    // are still returned, and the file is simply left at schema 1.
                    if (schema == LEGACY_SCHEMA_VERSION && entries.size == raw.size) migrate(entries)
                    entries
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            // A corrupt index is preserved for inspection and treated as empty; the copies stay.
            runCatching { file.renameTo(File(dir, "library.json.bad")) }
            emptyList()
        }
    }

    /**
     * One entry from one JSON object, or `null` when the object cannot describe a workbook at all.
     *
     * A missing `file` is fatal because the entry would point at nothing; everything else falls back to
     * the schema-1 default, so a partially written record still yields a usable entry.
     */
    private fun parseEntry(raw: Any?): SavedWorkbook? {
        val map = raw as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val name = map["name"] as? String ?: return null
        val fileName = map["file"] as? String ?: return null
        val remote = parseRemote(map["remote"])
        val origin = when ((map["origin"] as? String)?.lowercase()) {
            "onedrive" -> WorkbookOrigin.ONEDRIVE
            "google_drive" -> WorkbookOrigin.GOOGLE_DRIVE
            // An unknown origin string keeps the entry and reads it as local: hiding a workbook because
            // a field has a value this build has not learned yet is exactly what must not happen.
            else -> WorkbookOrigin.LOCAL
        }
        return SavedWorkbook(
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
            // An "onedrive" entry without a usable remote block cannot be checked or refreshed, so it is
            // read as local rather than leaving a half-OneDrive entry for the later phases to defend on.
            origin = if (origin == WorkbookOrigin.ONEDRIVE && remote == null) WorkbookOrigin.LOCAL else origin,
            remote = remote,
        )
    }

    private fun parseRemote(raw: Any?): RemoteOrigin? {
        val map = raw as? Map<*, *> ?: return null
        val itemId = map["itemId"] as? String ?: return null
        return RemoteOrigin(
            itemId = itemId,
            path = map["path"] as? String ?: "",
            eTag = map["etag"] as? String,
            cTag = map["ctag"] as? String,
            lastModified = (map["modified"] as? Double)?.toLong(),
            sizeBytes = (map["bytes"] as? Double)?.toLong(),
            syncedAt = (map["synced"] as? Double)?.toLong(),
            checkedAt = (map["checked"] as? Double)?.toLong(),
        )
    }

    /**
     * Rewrites a schema-1 library as schema 2, once, atomically.
     *
     * Failure is not an error the caller has to handle: the entries were already parsed, they are
     * returned either way, and every later read retries the rewrite. Nothing is deleted to make room for
     * the new index — [writeAll] replaces the file in one step and puts the old one back if it cannot.
     */
    private fun migrate(entries: List<SavedWorkbook>) {
        runCatching { writeAll(entries) }
    }

    private fun writeAll(entries: List<SavedWorkbook>): Boolean {
        // A library written by a newer build is never overwritten: that would downgrade it and destroy
        // entries this build cannot even read.
        if (indexFromNewerSchema) return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val root = linkedMapOf<String, Any?>(
            "schema" to SCHEMA_VERSION,
            "workbooks" to entries.map { entry ->
                linkedMapOf<String, Any?>(
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
                    "origin" to entry.origin.name.lowercase(),
                ).apply {
                    // Written only when there is one, so a local entry's line carries no `remote` key at
                    // all and a migrated schema-1 library differs from the original by adding `origin`.
                    entry.remote?.let { remote ->
                        put(
                            "remote",
                            linkedMapOf(
                                "itemId" to remote.itemId,
                                "path" to remote.path,
                                "etag" to remote.eTag,
                                "ctag" to remote.cTag,
                                "modified" to remote.lastModified,
                                "bytes" to remote.sizeBytes,
                                "synced" to remote.syncedAt,
                                "checked" to remote.checkedAt,
                            ),
                        )
                    }
                }
            },
        )
        val tmp = File(dir, "library.json.tmp")
        return try {
            tmp.writeText(MiniJson.write(root), Charsets.UTF_8)
            replaceIndex(tmp)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Puts [tmp] in place of `library.json` without ever leaving the library without an index.
     *
     * The previous code deleted the target and then renamed, so a rename that failed silently (which is
     * `File.renameTo`'s way of reporting failure) lost the index. The migration of a real user's library
     * must not have that failure mode, so this replaces in one step where the filesystem allows it and
     * otherwise keeps a backup until the new index is in place.
     */
    private fun replaceIndex(tmp: File): Boolean {
        val target = indexFile()
        if (atomicMove(tmp, target)) return true

        val backup = File(dir, "library.json.old")
        if (backup.exists()) backup.delete()
        val hadPrevious = target.exists()
        if (hadPrevious && !target.renameTo(backup)) {
            tmp.delete()
            return false
        }
        if (tmp.renameTo(target)) {
            if (hadPrevious) backup.delete()
            return true
        }
        // The new index did not arrive: put the previous one back rather than leaving none.
        if (hadPrevious) runCatching { backup.renameTo(target) }
        tmp.delete()
        return false
    }

    /** One-step replacement; `false` when the platform cannot do it atomically (then a backup is used). */
    private fun atomicMove(from: File, to: File): Boolean = try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (e: AtomicMoveNotSupportedException) {
        false
    } catch (e: Exception) {
        false
    }
}
