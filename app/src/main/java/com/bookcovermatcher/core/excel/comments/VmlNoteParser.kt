package com.bookcovermatcher.core.excel.comments

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Parser for a legacy VML drawing (`xl/drawings/vmlDrawing1.vml`).
 *
 * Only shapes whose `<x:ClientData ObjectType="Note">` marks them as cell notes are returned; buttons,
 * drop-downs and other VML objects are ignored. Element and attribute names are matched by local name,
 * so `v:`, `o:` and `x:` prefixes (or different ones) all work, and undeclared prefixes — which Excel
 * writes routinely — do not break the parse.
 */
object VmlNoteParser {

    /** One image reference exactly as the note shape declared it. */
    data class RawRef(val value: String, val kind: MediaRefKind, val attribute: String)

    /** One note shape: zero-based coordinates and zero or more image references. */
    data class NoteShape(
        val shapeId: String?,
        val row: Int?,
        val col: Int?,
        val refs: List<RawRef>,
    )

    fun parse(xml: String): List<NoteShape> {
        val out = ArrayList<NoteShape>()

        var shapeId: String? = null
        var inShape = false
        var isNote = false
        var row: Int? = null
        var col: Int? = null
        var refs = LinkedHashMap<String, RawRef>()

        var capturing: String? = null // "row" | "column"
        var capture = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (val name = XlsxXml.local(qName ?: "")) {
                    "shape" -> {
                        inShape = true
                        isNote = false
                        row = null
                        col = null
                        refs = LinkedHashMap()
                        shapeId = attributes?.let { WorkbookSheets.attributeByLocal(it, "id") }
                    }
                    "clientdata" -> {
                        if (!inShape || attributes == null) return
                        val type = WorkbookSheets.attributeByLocal(attributes, "objecttype")
                        if (type.equals("Note", ignoreCase = true)) isNote = true
                    }
                    "row", "column" -> if (inShape) {
                        capturing = name
                        capture = StringBuilder()
                    }
                    "fill", "imagedata" -> if (inShape && attributes != null) {
                        // A direct package path: <v:fill src="../media/image1.png">.
                        WorkbookSheets.attributeByLocal(attributes, "src")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { value -> refs.putIfAbsent("src:$value", RawRef(value, MediaRefKind.SRC, "src")) }
                        // Relationship ids: o:relid on either element, r:id on either element.
                        WorkbookSheets.attributeByLocal(attributes, "relid")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { value -> refs.putIfAbsent("rel:$value", RawRef(value, MediaRefKind.RELID, "relid")) }
                        relationshipId(attributes)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { value -> refs.putIfAbsent("rel:$value", RawRef(value, MediaRefKind.RELID, "r:id")) }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capturing != null) capture.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (XlsxXml.local(qName ?: "")) {
                    "row" -> if (capturing == "row") {
                        row = capture.toString().trim().toIntOrNull(); capturing = null
                    }
                    "column" -> if (capturing == "column") {
                        col = capture.toString().trim().toIntOrNull(); capturing = null
                    }
                    "shape" -> {
                        if (inShape && isNote) {
                            out.add(NoteShape(shapeId, row, col, refs.values.toList()))
                        }
                        inShape = false
                        isNote = false
                        refs = LinkedHashMap()
                        row = null
                        col = null
                    }
                }
            }
        }

        XlsxXml.parse(xml, handler)
        return out
    }

    /** The `r:id` attribute (prefix `r`, any case), or null when the element has none. */
    private fun relationshipId(attributes: Attributes): String? {
        for (i in 0 until attributes.length) {
            val qName = attributes.getQName(i)
            val prefix = qName.substringBefore(':', "")
            val local = XlsxXml.local(qName)
            if (local == "id" && prefix.equals("r", ignoreCase = true)) return attributes.getValue(i)
        }
        return null
    }
}
