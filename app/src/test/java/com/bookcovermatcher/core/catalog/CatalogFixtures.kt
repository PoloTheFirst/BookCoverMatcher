package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueHit
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.ImageCoverage
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.MediaAssociation
import com.bookcovermatcher.core.excel.comments.SheetInfo
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis

/**
 * Small, purpose-built fixtures for the catalogue features.
 *
 * Nothing here reads a workbook file: the tests exercise the pure layer with the same shapes the parser
 * produces, so they run on the desktop JVM and nothing large is staged into the repository.
 */
object CatalogFixtures {

    const val SHA = "sha-test"

    /** The default header row of [table], in workbook order. */
    val DEFAULT_HEADERS = listOf("タイトル", "サークル", "作者", "発行日", "ID", "URL", "価格", "ジャンル", "メモ")

    fun fields(vararg names: String): List<CatalogueField> =
        names.mapIndexed { index, name -> CatalogueField(name, index) }

    fun fields(): List<CatalogueField> = fields(*DEFAULT_HEADERS.toTypedArray())

    fun record(row: Int, vararg values: String): CatalogueRecord = CatalogueRecord(row, values.toList())

    /** A nine-column catalogue row, in [DEFAULT_HEADERS] order, with sensible defaults. */
    fun catalogueRecord(
        row: Int,
        title: String = "Book $row",
        circle: String = "Circle $row",
        artist: String = "Artist $row",
        published: String = "2024-05-17",
        identifier: String = "ID-$row",
        url: String = "https://example.com/item/$row",
        price: String = "1200",
        tags: String = "tag-a, tag-b",
        description: String = "note $row",
    ): CatalogueRecord = record(row, title, circle, artist, published, identifier, url, price, tags, description)

    fun table(
        sheetName: String = "LIST",
        headerRow: Int = 0,
        fields: List<CatalogueField> = fields(),
        records: List<CatalogueRecord> = emptyList(),
    ): CatalogueTable = CatalogueTable(sheetName, headerRow, fields, records)

    fun sheets(vararg names: String): List<SheetInfo> = names.mapIndexed { index, name ->
        SheetInfo(name, "visible", null, "xl/worksheets/sheet${index + 1}.xml")
    }

    fun sheets(): List<SheetInfo> = sheets("LIST")

    /** A profile that maps every canonical field to [DEFAULT_HEADERS]. */
    fun profile(name: String = "Test"): ColumnMappingProfile = ColumnMappingProfile(
        name,
        mapOf(
            CatalogField.TITLE to "タイトル",
            CatalogField.CIRCLE to "サークル",
            CatalogField.ARTIST to "作者",
            CatalogField.IDENTIFIER to "ID",
            CatalogField.URL to "URL",
            CatalogField.PRICE to "価格",
            CatalogField.DESCRIPTION to "メモ",
            CatalogField.PUBLISHED to "発行日",
            CatalogField.TAGS to "ジャンル",
        ),
    )

    /**
     * Date columns a *test* chose — the app never chooses them. Passing null leaves that timeline
     * unconfigured, which is the state an untouched workbook is in.
     */
    fun dates(
        published: String? = "発行日",
        acquired: String? = null,
        sheetName: String = "LIST",
        sha256: String = SHA,
    ): TimelineDateSettings = TimelineDateSettings(sha256, mapOf(sheetName to SheetDateChoice(published, acquired)))

    fun hit(sheetName: String, row: Int, title: String? = null): CatalogueHit =
        CatalogueHit(sheetName, row, title, null, null, emptyMap())

    fun linked(mediaPath: String, sheetName: String = "LIST", row: Int = 1): ImageRow = ImageRow(
        key = mediaPath,
        mediaPath = mediaPath,
        fileName = mediaPath.substringAfterLast('/'),
        format = "png",
        sizeBytes = 10,
        width = null,
        height = null,
        state = ImageState.LINKED,
        associations = listOf(MediaAssociation(sheetName, "B${row + 1}", "note on row $row")),
        catalogue = listOf(hit(sheetName, row)),
        unresolvedDetail = null,
    )

    fun unresolved(ref: String, sheetName: String = "LIST"): ImageRow = ImageRow(
        key = "unresolved:$ref",
        mediaPath = null,
        fileName = ref,
        format = "",
        sizeBytes = -1,
        width = null,
        height = null,
        state = ImageState.UNRESOLVED,
        associations = listOf(MediaAssociation(sheetName, "B5", "note")),
        catalogue = emptyList(),
        unresolvedDetail = "The note points at $ref, which is not in the package.",
    )

    fun unreferenced(mediaPath: String): ImageRow = ImageRow(
        key = mediaPath,
        mediaPath = mediaPath,
        fileName = mediaPath.substringAfterLast('/'),
        format = "png",
        sizeBytes = 20,
        width = null,
        height = null,
        state = ImageState.UNREFERENCED,
        associations = emptyList(),
        catalogue = emptyList(),
        unresolvedDetail = null,
    )

    fun analysis(
        tables: List<CatalogueTable>,
        images: List<ImageRow> = emptyList(),
        unreadable: List<String> = emptyList(),
        sheets: List<SheetInfo> = sheets(*tables.map { it.sheetName }.toTypedArray()),
    ): WorkbookAnalysis = WorkbookAnalysis(
        sheets = sheets,
        tables = tables,
        images = images,
        coverage = ImageCoverage(
            recordsTotal = tables.sumOf { it.records.size },
            recordsWithImage = images.count { it.state == ImageState.LINKED },
            unresolvedAssociations = images.count { it.state == ImageState.UNRESOLVED },
            unreferencedMedia = images.count { it.state == ImageState.UNREFERENCED },
        ),
        unreadableSheets = unreadable,
    )

    fun catalog(
        records: List<CatalogueRecord> = listOf(catalogueRecord(1), catalogueRecord(2)),
        images: List<ImageRow> = emptyList(),
        profile: ColumnMappingProfile = profile(),
        sheetName: String = "LIST",
    ): WorkbookCatalog = WorkbookCatalogBuilder.build(
        workbookSha256 = SHA,
        sheets = sheets(sheetName),
        tables = listOf(table(sheetName = sheetName, records = records)),
        images = images,
        profile = profile,
    )
}