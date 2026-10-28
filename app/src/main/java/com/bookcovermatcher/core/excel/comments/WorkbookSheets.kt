package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.ZipSource
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** One worksheet as the workbook declares it: display name plus its part path. */
data class SheetRef(val name: String, val partPath: String, val visibility: String = "visible")

/**
 * The package parts this feature reads for one worksheet.
 *
 * Everything is discovered through relationships — never by guessing `comments1.xml` or
 * `vmlDrawing1.vml`, whose numbering does not have to match the sheet order.
 */
data class SheetParts(
    val sheet: SheetRef,
    val commentsPart: String?,
    val vmlParts: List<String>,
    val threadedCommentParts: List<String>,
)

object WorkbookSheets {

    private val WORKSHEET_TYPE = "worksheet"

    /** Worksheets in workbook order, resolved through `xl/_rels/workbook.xml.rels`. */
    fun list(zip: ZipSource): List<SheetRef> {
        val workbook = zip.readText("xl/workbook.xml") ?: return emptyList()
        val rels = OoxmlRelationships.of(zip, "xl/workbook.xml")
        if (rels.isEmpty()) return emptyList()
        val byId = rels.associateBy { it.id }

        val declared = ArrayList<Triple<String, String, String>>() // name, r:id, state
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                if (XlsxXml.local(qName ?: "") != "sheet" || attributes == null) return
                val name = attributes.getValue("name") ?: attributes.getValue("Name") ?: return
                val rid = attributeByLocal(attributes, "id") ?: return
                val state = attributeByLocal(attributes, "state") ?: "visible"
                declared.add(Triple(name, rid, state))
            }
        }
        if (!XlsxXml.parse(workbook, handler)) return emptyList()

        val out = ArrayList<SheetRef>()
        for ((name, rid, state) in declared) {
            val rel = byId[rid] ?: continue
            if (rel.shortType != WORKSHEET_TYPE) continue
            val path = OoxmlRelationships.targetPath("xl/workbook.xml", rel) ?: continue
            if (!zip.contains(path)) continue
            out.add(SheetRef(name, path, state))
        }
        return out
    }

    /** The comments / VML / threaded-comment parts a worksheet declares (each may be absent). */
    fun partsOf(zip: ZipSource, sheet: SheetRef): SheetParts {
        val rels = OoxmlRelationships.of(zip, sheet.partPath)
        fun paths(shortType: String): List<String> = rels
            .filter { it.shortType == shortType }
            .mapNotNull { OoxmlRelationships.targetPath(sheet.partPath, it) }
            .filter { zip.contains(it) }

        return SheetParts(
            sheet = sheet,
            commentsPart = paths("comments").firstOrNull(),
            vmlParts = paths("vmldrawing"),
            threadedCommentParts = paths("threadedcomment"),
        )
    }

    fun discover(zip: ZipSource): List<SheetParts> = list(zip).map { partsOf(zip, it) }

    /** First attribute whose local name equals [name] (any or no prefix). */
    internal fun attributeByLocal(attributes: Attributes, name: String): String? {
        for (i in 0 until attributes.length) {
            if (XlsxXml.local(attributes.getQName(i)) == name) return attributes.getValue(i)
        }
        return null
    }
}
