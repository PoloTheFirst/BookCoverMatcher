// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * The column mappings one workbook was last left with, stored **per workbook** (its SHA-256) and **per
 * worksheet** (the worksheet identity: package part path, or `name:<display>`).
 *
 * A saved profile is reusable and named by the user; this is the other half: which mapping each worksheet is
 * actually read with, so reopening the workbook does not fall back to detection — and so the mapping of the
 * worksheet that happened to be on screen is never written over the others. The Default Order column is part of a
 * worksheet's mapping like every other field.
 *
 * Each entry's columns carry the workbook's **own** header text, never a column position, so inserting a column
 * does not shift what a field reads.
 */
data class WorkbookMapping(
    val workbookSha256: String = "",
    /** The profile view this workbook was last left on; null is the Detected view. */
    val activeProfile: String? = null,
    /** Worksheet identity -> the mapping applied to it on top of the selected view. */
    val sheets: Map<String, SheetMapping> = emptyMap(),
    /**
     * A **v1** file's single workbook-wide column set, kept only so it can be migrated on the next open.
     *
     * Version 1 predates per-worksheet mappings, so its columns cannot say which worksheet they belonged to.
     * [WorkbookCommentsUi.restoredMapping] gives each of them to the worksheets whose headers really contain them
     * and leaves every other worksheet on its own detection, which is the only migration that cannot contaminate
     * a worksheet. It is never written back.
     */
    val legacy: SheetMapping? = null,
) {
    val isEmpty: Boolean
        get() = sheets.values.none { !it.isEmpty } && legacy?.isEmpty != false
}

/**
 * JSON codec for [WorkbookMapping], reusing the app's hand-written [MiniJson].
 *
 * Schema 2 writes one entry per worksheet. A schema-1 file (one column set for the whole workbook) still decodes:
 * its columns come back as [WorkbookMapping.legacy] and are migrated per worksheet when the workbook is opened,
 * so no stored choice is thrown away and no worksheet is given another worksheet's columns. Same rules as the
 * other stores: tolerant per-entry decoding (one broken workbook is skipped and the rest still load), unknown
 * field names ignored, and a file that is not JSON at all yields an empty list instead of being overwritten.
 */
object WorkbookMappingCodec {

    const val SCHEMA_VERSION = 2

    fun encode(mappings: List<WorkbookMapping>): String = MiniJson.write(
        mapOf(
            "schema" to SCHEMA_VERSION,
            "workbooks" to mappings
                .filter { it.workbookSha256.isNotBlank() && !it.isEmpty }
                .map { mapping ->
                    val entry = LinkedHashMap<String, Any?>()
                    entry["sha256"] = mapping.workbookSha256
                    mapping.activeProfile?.takeIf { it.isNotBlank() }?.let { entry["profile"] = it }
                    val sheets = mapping.sheets
                        .filterValues { !it.isEmpty }
                        .toSortedMap()
                        .mapValues { (_, sheet) -> sheetJson(sheet) }
                    if (sheets.isNotEmpty()) entry["sheets"] = sheets
                    entry
                },
        ),
    )

    private fun sheetJson(mapping: SheetMapping): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        mapping.profileName?.takeIf { it.isNotBlank() }?.let { out["profile"] = it }
        out["columns"] = mapping.columns.entries
            .sortedBy { it.key.ordinal }
            .associate { (field, header) -> field.name to header }
        return out
    }

    fun decode(text: String): List<WorkbookMapping> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = root["workbooks"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val sha256 = (map["sha256"] as? String)?.trim().orEmpty()
            if (sha256.isEmpty()) return@mapNotNull null
            val sheets = LinkedHashMap<String, SheetMapping>()
            (map["sheets"] as? Map<*, *>)?.forEach { (rawKey, rawEntry) ->
                val key = (rawKey as? String)?.trim().orEmpty()
                if (key.isEmpty()) return@forEach
                val entry = sheetOf(rawEntry) ?: return@forEach
                if (!entry.isEmpty) sheets[key] = entry
            }
            // Schema 1: one column set for the whole workbook. Kept for migration, never re-written.
            val legacy = if (sheets.isEmpty() && map["sheets"] == null) sheetOf(map) else null
            WorkbookMapping(
                workbookSha256 = sha256,
                activeProfile = (map["profile"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                sheets = sheets,
                legacy = legacy?.takeIf { !it.isEmpty },
            )
        }.filterNot { it.isEmpty }.distinctBy { it.workbookSha256 }
    }

    /** One worksheet entry (schema 2) or one workbook entry (schema 1); null when it carries no columns. */
    private fun sheetOf(raw: Any?): SheetMapping? {
        val map = raw as? Map<*, *> ?: return null
        val columns = LinkedHashMap<CatalogField, String>()
        // A field the app does not know (a newer schema, a removed field) is skipped rather than guessed.
        (map["columns"] as? Map<*, *>)?.forEach { (rawField, rawHeader) ->
            val field = CatalogField.entries.firstOrNull { it.name == rawField } ?: return@forEach
            val header = (rawHeader as? String).orEmpty()
            if (header.isNotBlank()) columns[field] = header
        }
        val profile = (map["profile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return if (columns.isEmpty()) null else SheetMapping(profileName = profile, columns = columns)
    }
}
