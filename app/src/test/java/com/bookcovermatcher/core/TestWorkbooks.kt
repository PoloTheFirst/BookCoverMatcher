package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.MapZipSource

/**
 * Builds small but *real* OOXML packages for the legacy-comment tests: workbook, worksheet
 * relationships, comments XML, VML drawing, VML relationships and media bytes. Nothing is mocked —
 * the scanner sees the same parts an `.xlsx` would contain.
 */
object TestWorkbooks {

    /** Four bytes of a PNG signature, enough for [MediaBytes.sniff] to say "png". */
    val PNG: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun workbook(vararg sheets: Pair<String, Int>): String = buildString {
        append("<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$R_NS\"><sheets>")
        sheets.forEachIndexed { index, (name, id) ->
            append("<sheet name=\"$name\" sheetId=\"$id\" r:id=\"rId${index + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    fun workbookRels(sheetCount: Int): String = buildString {
        append("<Relationships xmlns=\"$RELS_NS\">")
        for (i in 1..sheetCount) {
            append(
                "<Relationship Id=\"rId$i\" Type=\"$R_NS/worksheet\" Target=\"worksheets/sheet$i.xml\"/>",
            )
        }
        append("</Relationships>")
    }

    /** Worksheet relationships: comments first, then VML, then an optional threaded-comment part. */
    fun sheetRels(comments: String?, vml: String?, threaded: String? = null): String = buildString {
        append("<Relationships xmlns=\"$RELS_NS\">")
        var id = 1
        if (comments != null) {
            append("<Relationship Id=\"rId$id\" Type=\"$R_NS/comments\" Target=\"$comments\"/>"); id++
        }
        if (vml != null) {
            append("<Relationship Id=\"rId$id\" Type=\"$R_NS/vmlDrawing\" Target=\"$vml\"/>"); id++
        }
        if (threaded != null) {
            append("<Relationship Id=\"rId$id\" Type=\"$R_NS/threadedComment\" Target=\"$threaded\"/>")
        }
        append("</Relationships>")
    }

    fun comments(authors: List<String>, vararg comments: String): String = buildString {
        append("<comments xmlns=\"$MAIN_NS\"><authors>")
        authors.forEach { append("<author>$it</author>") }
        append("</authors><commentList>")
        comments.forEach { append(it) }
        append("</commentList></comments>")
    }

    /** One `<comment>` element; [body] is the raw content of `<text>`. */
    fun comment(ref: String, authorId: Int, body: String): String =
        "<comment ref=\"$ref\" authorId=\"$authorId\"><text>$body</text></comment>"

    /** A note shape at zero-based ([row], [col]) with the given fill/imagedata fragment. */
    fun noteShape(row: Int, col: Int, image: String = "", id: String = "_x0000_s1025"): String = """
        <v:shape id="$id" type="#_x0000_t202">
          $image
          <x:ClientData ObjectType="Note"><x:Row>$row</x:Row><x:Column>$col</x:Column></x:ClientData>
        </v:shape>
    """.trimIndent()

    fun vml(vararg shapes: String): String = """
        <xml xmlns:v="urn:schemas-microsoft-com:vml"
             xmlns:o="urn:schemas-microsoft-com:office:office"
             xmlns:x="urn:schemas-microsoft-com:office:excel">
        ${shapes.joinToString("\n")}
        </xml>
    """.trimIndent()

    fun vmlRels(vararg rels: Pair<String, String>): String = buildString {
        append("<Relationships xmlns=\"$RELS_NS\">")
        rels.forEach { (id, target) ->
            append("<Relationship Id=\"$id\" Type=\"$R_NS/image\" Target=\"$target\"/>")
        }
        append("</Relationships>")
    }

    /** `xl/comments1.xml` -> `../comments1.xml`, the target form a worksheet's rels file uses. */
    private fun fromWorksheets(path: String): String = "../" + path.removePrefix("xl/")

    /** A one-sheet workbook; parts can be replaced or omitted to build the edge cases. */
    fun xlsx(
        sheetName: String = "Sheet1",
        commentsXml: String? = null,
        commentsPath: String = "xl/comments1.xml",
        vmlXml: String? = null,
        vmlPath: String = "xl/drawings/vmlDrawing1.vml",
        vmlRelsXml: String? = null,
        media: Map<String, ByteArray> = mapOf("xl/media/image1.png" to PNG),
        extra: Map<String, ByteArray> = emptyMap(),
    ): MapZipSource {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["[Content_Types].xml"] = "<Types/>".toByteArray()
        entries["xl/workbook.xml"] = workbook(sheetName to 1).toByteArray()
        entries["xl/_rels/workbook.xml.rels"] = workbookRels(1).toByteArray()
        entries["xl/worksheets/sheet1.xml"] = "<worksheet/>".toByteArray()
        // Relationship targets are relative to xl/worksheets/, not to the package root.
        entries["xl/worksheets/_rels/sheet1.xml.rels"] =
            sheetRels(
                commentsXml?.let { fromWorksheets(commentsPath) },
                vmlXml?.let { fromWorksheets(vmlPath) },
            ).toByteArray()
        if (commentsXml != null) entries[commentsPath] = commentsXml.toByteArray()
        if (vmlXml != null) entries[vmlPath] = vmlXml.toByteArray()
        if (vmlRelsXml != null) {
            val relsName = vmlPath.substringAfterLast('/')
            entries["xl/drawings/_rels/$relsName.rels"] = vmlRelsXml.toByteArray()
        }
        media.forEach { (path, bytes) -> entries[path] = bytes }
        extra.forEach { (path, bytes) -> entries[path] = bytes }
        return MapZipSource(entries)
    }
}
