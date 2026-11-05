package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * JSON codec for the saved date-column choices.
 *
 * It reuses the app's hand-written [MiniJson] (the wishlist's codec), so no JSON dependency is added, and
 * follows the mapping-profile codec's rules: the file carries a schema version, decoding is tolerant per
 * entry (one broken workbook is skipped, the rest still load), and a file that is not JSON at all yields an
 * empty list instead of being overwritten.
 *
 * The stored value is the **header name**, never a column index: a column inserted at the left of a sheet
 * must not silently move the date assignment to its neighbour.
 */
object TimelineDateCodec {

    fun encode(settings: List<TimelineDateSettings>): String = MiniJson.write(
        mapOf(
            "schema" to TimelineDateSettings.SCHEMA_VERSION,
            "workbooks" to settings
                .filter { it.workbookSha256.isNotBlank() && it.isConfigured }
                .map { workbook ->
                    mapOf(
                        "sha256" to workbook.workbookSha256,
                        "sheets" to workbook.bySheet
                            .filterValues { !it.isEmpty }
                            .toSortedMap()
                            .mapValues { (_, choice) ->
                                val entry = LinkedHashMap<String, String>()
                                choice.publishedHeader?.let { entry["published"] = it }
                                choice.acquiredHeader?.let { entry["acquired"] = it }
                                entry
                            },
                    )
                },
        ),
    )

    fun decode(text: String): List<TimelineDateSettings> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = root["workbooks"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val sha256 = (map["sha256"] as? String)?.trim().orEmpty()
            if (sha256.isEmpty()) return@mapNotNull null
            val sheets = map["sheets"] as? Map<*, *> ?: return@mapNotNull null
            val bySheet = LinkedHashMap<String, SheetDateChoice>()
            for ((rawName, rawChoice) in sheets) {
                val sheetName = (rawName as? String)?.trim().orEmpty()
                val choice = rawChoice as? Map<*, *> ?: continue
                if (sheetName.isEmpty()) continue
                val published = (choice["published"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val acquired = (choice["acquired"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val value = SheetDateChoice(published, acquired)
                if (!value.isEmpty) bySheet[sheetName] = value
            }
            if (bySheet.isEmpty()) return@mapNotNull null
            TimelineDateSettings(sha256, bySheet)
        }.distinctBy { it.workbookSha256 }
    }
}
