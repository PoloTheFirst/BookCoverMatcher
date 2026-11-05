// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.wishlist.MiniJson

/** A saved year range. Either bound may be open, exactly like the filter it restores. */
data class YearRange(val fromYear: Int? = null, val toYear: Int? = null) {

    val isActive: Boolean get() = fromYear != null || toYear != null

    companion object {
        val NONE = YearRange()
    }
}

/**
 * What one workbook's Insights screens remember about the user's own selections.
 *
 * Scope is the workbook (its SHA-256) — never the display name — and each screen keeps its **own** entry,
 * so narrowing the gallery cannot narrow the health report. A worksheet selection is a set of worksheet
 * **identities** (package part paths, [SheetScope]), so renaming a sheet in Excel keeps the selection and a
 * saved key that the workbook no longer has is reconciled away instead of matching some other sheet.
 *
 * The year range is stored per date meaning, separately from the date-column choices and from the active
 * mode: opening Published must not inherit Acquired's range, and neither is a column mapping.
 */
data class InsightsView(
    val workbookSha256: String = "",
    val health: SheetScope = SheetScope.ALL,
    val analytics: SheetScope = SheetScope.ALL,
    val gallery: SheetScope = SheetScope.ALL,
    val years: Map<TimelineMode, YearRange> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = health.isAll && analytics.isAll && gallery.isAll && years.isEmpty()

    fun yearsOf(mode: TimelineMode): YearRange = years[mode] ?: YearRange.NONE

    /** Stores (or, with null, forgets) the year range of one date meaning. */
    fun withYears(mode: TimelineMode, range: YearRange?): InsightsView = when {
        range == null || !range.isActive -> if (mode in years) copy(years = years - mode) else this
        years[mode] == range -> this
        else -> copy(years = years + (mode to range))
    }

    /** Drops every worksheet key this workbook no longer has; an empty result means "all worksheets". */
    fun reconciled(available: Collection<String>): InsightsView {
        val next = copy(
            health = health.reconciled(available),
            analytics = analytics.reconciled(available),
            gallery = gallery.reconciled(available),
        )
        // The years are kept even when the current data has none of them: an empty result the user can clear
        // is honest, silently forgetting their range would not be.
        return next
    }
}

/**
 * JSON codec for [InsightsView], reusing the app's hand-written [MiniJson].
 *
 * Same rules as the other stores: a schema version, tolerant per-entry decoding (one broken workbook is
 * skipped, the rest still load), and a file that is not JSON at all yields an empty list instead of being
 * overwritten.
 */
object InsightsViewCodec {

    const val SCHEMA_VERSION = 1

    fun encode(views: List<InsightsView>): String = MiniJson.write(
        mapOf(
            "schema" to SCHEMA_VERSION,
            "workbooks" to views
                .filter { it.workbookSha256.isNotBlank() && !it.isEmpty }
                .map { view ->
                    val entry = LinkedHashMap<String, Any?>()
                    entry["sha256"] = view.workbookSha256
                    if (!view.health.isAll) entry["health"] = view.health.keys.sorted()
                    if (!view.analytics.isAll) entry["analytics"] = view.analytics.keys.sorted()
                    if (!view.gallery.isAll) entry["gallery"] = view.gallery.keys.sorted()
                    if (view.years.isNotEmpty()) {
                        entry["years"] = view.years.toSortedMap(compareBy { it.name }).entries
                            .associate { (mode, range) ->
                                mode.name to buildMap<String, Any?> {
                                    range.fromYear?.let { put("from", it) }
                                    range.toYear?.let { put("to", it) }
                                }
                            }
                    }
                    entry
                },
        ),
    )

    fun decode(text: String): List<InsightsView> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = root["workbooks"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val sha256 = (map["sha256"] as? String)?.trim().orEmpty()
            if (sha256.isEmpty()) return@mapNotNull null
            val years = LinkedHashMap<TimelineMode, YearRange>()
            (map["years"] as? Map<*, *>)?.forEach { (rawMode, rawRange) ->
                val mode = TimelineMode.entries.firstOrNull { it.name == rawMode } ?: return@forEach
                val range = rawRange as? Map<*, *> ?: return@forEach
                val from = (range["from"] as? Double)?.toInt()
                val to = (range["to"] as? Double)?.toInt()
                val value = YearRange(from, to)
                if (value.isActive) years[mode] = value
            }
            InsightsView(
                workbookSha256 = sha256,
                health = keysOf(map["health"]),
                analytics = keysOf(map["analytics"]),
                gallery = keysOf(map["gallery"]),
                years = years,
            )
        }.distinctBy { it.workbookSha256 }
    }

    private fun keysOf(raw: Any?): SheetScope {
        val keys = (raw as? List<*>).orEmpty()
            .mapNotNull { (it as? String)?.trim()?.takeIf { key -> key.isNotEmpty() } }
            .toSet()
        return SheetScope(keys)
    }
}
