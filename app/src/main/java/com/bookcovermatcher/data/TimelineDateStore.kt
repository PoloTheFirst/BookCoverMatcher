package com.bookcovermatcher.data

import com.bookcovermatcher.core.catalog.TimelineDateCodec
import com.bookcovermatcher.core.catalog.TimelineDateSettings
import java.io.File

/**
 * The saved date-column choices (`files/mappings/date-columns.json`).
 *
 * One entry per workbook, keyed by the workbook's SHA-256 and then by worksheet, so a choice can never
 * follow a shared mapping profile into a different collection. Same shape as the app's other stores: a
 * temp file + rename on write, so an interrupted save cannot truncate the list, and a read that never
 * throws (a missing or unreadable file means "nothing chosen yet", which the timeline says out loud).
 */
class TimelineDateStore(private val dir: File) {

    private val file: File get() = File(dir, "date-columns.json")

    fun load(): List<TimelineDateSettings> {
        val text = try {
            if (file.isFile) file.readText() else null
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return TimelineDateCodec.decode(text)
    }

    /** The choices saved for one workbook; empty choices when this workbook has none. */
    fun load(sha256: String): TimelineDateSettings =
        load().firstOrNull { it.workbookSha256 == sha256 } ?: TimelineDateSettings(sha256)

    /** Adds or replaces this workbook's entry and returns the stored list. */
    fun save(settings: TimelineDateSettings): List<TimelineDateSettings> {
        val others = load().filterNot { it.workbookSha256 == settings.workbookSha256 }
        val next = if (settings.isConfigured) others + settings else others
        write(next)
        return next
    }

    /** Forgets one workbook's choices (used when the user clears them for every worksheet). */
    fun delete(sha256: String): List<TimelineDateSettings> {
        val next = load().filterNot { it.workbookSha256 == sha256 }
        write(next)
        return next
    }

    private fun write(settings: List<TimelineDateSettings>) {
        dir.mkdirs()
        val temp = File(dir, "date-columns.json.tmp")
        temp.writeText(TimelineDateCodec.encode(settings))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
