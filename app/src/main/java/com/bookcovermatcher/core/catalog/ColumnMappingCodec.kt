package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * JSON codec for the saved mapping profiles.
 *
 * It reuses the app's hand-written [MiniJson] (the wishlist's codec) so no JSON dependency is added.
 * Decoding is tolerant per entry: a profile with an unknown field name or a broken shape is skipped and
 * the rest still load; a file that is not JSON at all yields an empty list, which the store reports as
 * "no saved profiles" rather than overwriting the file.
 */
object ColumnMappingCodec {

    fun encode(profiles: List<ColumnMappingProfile>): String = MiniJson.write(
        mapOf(
            "schema" to ColumnMappingProfile.SCHEMA_VERSION,
            "profiles" to profiles.map { profile ->
                mapOf(
                    "name" to profile.name,
                    "schema" to profile.schemaVersion,
                    "columns" to profile.columns.entries
                        .associate { (field, header) -> field.name to header }
                        .toSortedMap(),
                )
            },
        ),
    )

    fun decode(text: String): List<ColumnMappingProfile> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = root["profiles"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val rawColumns = map["columns"] as? Map<*, *> ?: return@mapNotNull null
            val columns = LinkedHashMap<CatalogField, String>()
            for (field in CatalogField.ordered) {
                val header = (rawColumns[field.name] as? String)?.trim().orEmpty()
                if (header.isNotEmpty()) columns[field] = header
            }
            if (columns.isEmpty()) return@mapNotNull null
            val schema = (map["schema"] as? Number)?.toInt() ?: ColumnMappingProfile.SCHEMA_VERSION
            ColumnMappingProfile(name, columns, schema)
        }.distinctBy { it.name }
    }
}