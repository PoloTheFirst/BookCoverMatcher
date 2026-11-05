package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * JSON codec for the saved mapping profiles.
 *
 * It reuses the app's hand-written [MiniJson] (the wishlist's codec) so no JSON dependency is added.
 *
 * Schema 2 writes a profile as **one entry per worksheet** (keyed by worksheet identity) plus the optional
 * workbook-wide column set. A schema-1 profile — a single column set with no worksheet — still decodes, so a
 * profile saved by an earlier build keeps working as the fallback it always was; it is never turned into
 * per-worksheet entries, because it never said which worksheet its columns belonged to.
 *
 * Decoding is tolerant per entry: a profile with an unknown field name or a broken shape is skipped and the rest
 * still load; a file that is not JSON at all yields an empty list, which the store reports as "no saved profiles"
 * rather than overwriting the file.
 */
object ColumnMappingCodec {

    fun encode(profiles: List<ColumnMappingProfile>): String = MiniJson.write(
        mapOf(
            "schema" to ColumnMappingProfile.SCHEMA_VERSION,
            "profiles" to profiles.map { profile -> profileJson(profile) },
        ),
    )

    private fun profileJson(profile: ColumnMappingProfile): Map<String, Any?> {
        val entry = LinkedHashMap<String, Any?>()
        entry["name"] = profile.name
        entry["schema"] = profile.schemaVersion
        val sheets = profile.sheets
            .filterValues { it.isNotEmpty() }
            .toSortedMap()
            .mapValues { (_, columns) -> columnsJson(columns) }
        if (sheets.isNotEmpty()) entry["sheets"] = sheets
        if (profile.columns.isNotEmpty()) entry["columns"] = columnsJson(profile.columns)
        return entry
    }

    private fun columnsJson(columns: Map<CatalogField, String>): Map<String, Any?> = columns.entries
        .associate { (field, header) -> field.name to header }
        .toSortedMap()

    fun decode(text: String): List<ColumnMappingProfile> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = root["profiles"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val sheets = LinkedHashMap<String, Map<CatalogField, String>>()
            (map["sheets"] as? Map<*, *>)?.forEach { (rawKey, rawColumns) ->
                val key = (rawKey as? String)?.trim().orEmpty()
                if (key.isEmpty()) return@forEach
                val columns = columnsOf(rawColumns)
                if (columns.isNotEmpty()) sheets[key] = columns
            }
            val columns = columnsOf(map["columns"])
            if (sheets.isEmpty() && columns.isEmpty()) return@mapNotNull null
            val schema = (map["schema"] as? Number)?.toInt() ?: ColumnMappingProfile.SCHEMA_VERSION
            ColumnMappingProfile(name, sheets, columns, schema)
        }.distinctBy { it.name }
    }

    /** One column map, keeping only the fields this build knows; a blank header is not an assignment. */
    private fun columnsOf(raw: Any?): Map<CatalogField, String> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val columns = LinkedHashMap<CatalogField, String>()
        for (field in CatalogField.ordered) {
            val header = (map[field.name] as? String)?.trim().orEmpty()
            if (header.isNotEmpty()) columns[field] = header
        }
        return columns
    }
}
