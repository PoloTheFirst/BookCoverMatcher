// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import com.bookcovermatcher.core.catalog.InsightsView
import com.bookcovermatcher.core.catalog.InsightsViewCodec
import java.io.File

/**
 * The selections the Insights screens remember per workbook (`files/mappings/insights-views.json`).
 *
 * One entry per workbook, keyed by its SHA-256, holding the three independent worksheet selections (Health,
 * Analytics, Gallery) and the Timeline year range of each date meaning. Same shape as the app's other
 * stores: a temp file + rename on write, so an interrupted save cannot truncate the file, and a read that
 * never throws — a missing or unreadable file means "nothing saved yet", which the screens show as their
 * defaults rather than as an error.
 */
class InsightsViewStore(private val dir: File) {

    private val file: File get() = File(dir, "insights-views.json")

    fun load(): List<InsightsView> {
        val text = try {
            if (file.isFile) file.readText() else null
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return InsightsViewCodec.decode(text)
    }

    /** What this workbook was last left with; defaults when it has no saved entry. */
    fun load(sha256: String): InsightsView =
        load().firstOrNull { it.workbookSha256 == sha256 } ?: InsightsView(workbookSha256 = sha256)

    /** Replaces this workbook's entry and returns the stored list. An empty view removes the entry. */
    fun save(view: InsightsView): List<InsightsView> {
        val others = load().filterNot { it.workbookSha256 == view.workbookSha256 }
        val next = if (view.isEmpty) others else others + view
        write(next)
        return next
    }

    private fun write(views: List<InsightsView>) {
        dir.mkdirs()
        val temp = File(dir, "insights-views.json.tmp")
        temp.writeText(InsightsViewCodec.encode(views))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
