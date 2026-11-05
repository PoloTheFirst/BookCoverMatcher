package com.bookcovermatcher.data

import com.bookcovermatcher.core.catalog.WorkbookMapping
import com.bookcovermatcher.core.catalog.WorkbookMappingCodec
import java.io.File

/**
 * The mapping each workbook was last left with (`files/mappings/workbook-mappings.json`).
 *
 * Stored beside the mapping profiles but scoped per workbook, like the date-column choices: a manual mapping —
 * the Default Order column included — is a decision about **one** collection, so it must never follow a shared
 * profile into a different workbook. Same shape as the app's other stores: a temp file + rename on write, so an
 * interrupted save cannot truncate the file, and a read that never throws (a missing or unreadable file means
 * "nothing stored yet", which the Fields screen shows as the detected mapping).
 */
class WorkbookMappingStore(private val dir: File) {

    private val file: File get() = File(dir, "workbook-mappings.json")

    fun load(): List<WorkbookMapping> {
        val text = try {
            if (file.isFile) file.readText() else null
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return WorkbookMappingCodec.decode(text)
    }

    /** The mapping saved for one workbook; an empty one when it has no stored entry. */
    fun load(sha256: String): WorkbookMapping =
        load().firstOrNull { it.workbookSha256 == sha256 } ?: WorkbookMapping(sha256)

    /**
     * Replaces this workbook's entry and returns the stored list.
     *
     * An empty mapping **removes** the entry: a user who goes back to the detected mapping is not left with a
     * stale column list that the next open would silently re-apply.
     */
    fun save(mapping: WorkbookMapping): List<WorkbookMapping> {
        val others = load().filterNot { it.workbookSha256 == mapping.workbookSha256 }
        val next = if (mapping.isEmpty) others else others + mapping
        write(next)
        return next
    }

    /** Forgets one workbook's mapping (used when the user returns to the detected mapping). */
    fun delete(sha256: String): List<WorkbookMapping> {
        val next = load().filterNot { it.workbookSha256 == sha256 }
        write(next)
        return next
    }

    private fun write(mappings: List<WorkbookMapping>) {
        dir.mkdirs()
        val temp = File(dir, "workbook-mappings.json.tmp")
        temp.writeText(WorkbookMappingCodec.encode(mappings))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
