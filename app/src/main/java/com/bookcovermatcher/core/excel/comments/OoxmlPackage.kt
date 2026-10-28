package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.ZipPaths
import com.bookcovermatcher.core.excel.ZipSource
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/**
 * XML reading for workbook parts, deliberately **prefix-agnostic**.
 *
 * Two reasons the parser is not namespace-aware:
 *  * VML drawings written by Excel routinely use `v:`, `o:`, `x:` prefixes that are not all declared,
 *    so a namespace-aware parser rejects documents Excel itself opens fine.
 *  * The same element may appear with a different prefix in a re-saved workbook.
 *
 * Element names are therefore compared by their local part (`o:relid` -> `relid`), lower-cased. The
 * parser is streaming, never resolves external entities and never fetches anything: workbook parts are
 * treated as untrusted input.
 */
internal object XlsxXml {

    /** `o:relid` -> `relid`; `Relationship` -> `relationship`. */
    fun local(raw: String): String {
        val name = raw.substringAfter(':')
        return name.lowercase()
    }

    fun newFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        // XXE / billion-laughs hardening; each flag is best-effort because support differs per JVM.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }

    /**
     * Feeds [text] to [handler]. Returns false when the document is not well-formed (the caller turns
     * that into a diagnostic instead of an exception).
     */
    fun parse(text: String, handler: DefaultHandler): Boolean = try {
        val parser = newFactory().newSAXParser()
        parser.parse(text.byteInputStream(Charsets.UTF_8), handler)
        true
    } catch (e: Exception) {
        false
    }
}

/** One OPC relationship (`<Relationship Id Type Target TargetMode>`), never resolved here. */
data class OoxmlRelationship(
    val id: String,
    val type: String,
    val target: String,
    val external: Boolean,
) {
    /** The last `/`-separated segment of the type URI, e.g. `comments`, `vmlDrawing`, `worksheet`. */
    val shortType: String get() = type.substringAfterLast('/').lowercase()
}

object OoxmlRelationships {

    /** Parses a `.rels` document; [] when it is missing or malformed. */
    fun parse(relsText: String?): List<OoxmlRelationship> {
        if (relsText.isNullOrBlank()) return emptyList()
        val out = ArrayList<OoxmlRelationship>()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                if (XlsxXml.local(qName ?: "") != "relationship" || attributes == null) return
                val id = attributes.getValue("Id") ?: attributes.getValue("id") ?: return
                val type = attributes.getValue("Type") ?: attributes.getValue("type") ?: return
                val target = attributes.getValue("Target") ?: attributes.getValue("target") ?: return
                val mode = attributes.getValue("TargetMode") ?: attributes.getValue("targetmode")
                out.add(OoxmlRelationship(id, type, target, mode.equals("External", ignoreCase = true)))
            }
        }
        XlsxXml.parse(relsText, handler)
        return out
    }

    /** `xl/worksheets/sheet1.xml` -> `xl/worksheets/_rels/sheet1.xml.rels`. */
    fun relsPathOf(partPath: String): String {
        val dir = partPath.substring(0, partPath.lastIndexOf('/') + 1)
        return dir + "_rels/" + partPath.substringAfterLast('/') + ".rels"
    }

    /**
     * The package path a relationship points at, or null when it must not be followed (external mode,
     * empty target). The result is normalised and stays inside the package: a target that climbs above
     * the root is rejected here rather than being handed to the ZIP layer.
     */
    fun targetPath(ownerPart: String, rel: OoxmlRelationship): String? {
        if (rel.external) return null
        val baseDir = ownerPart.substring(0, ownerPart.lastIndexOf('/') + 1)
        val resolved = ZipPaths.resolve(baseDir, rel.target)
        if (resolved.isBlank()) return null
        if (resolved.split('/').any { it == ".." }) return null
        return resolved
    }

    /** Reads and parses the relationships owned by [partPath] inside [zip]. */
    fun of(zip: ZipSource, partPath: String): List<OoxmlRelationship> =
        parse(zip.readText(relsPathOf(partPath)))

    /** First relationship of [shortType] (`comments`, `vmlDrawing`, `threadedComment`, ...). */
    fun firstOfType(rels: List<OoxmlRelationship>, shortType: String): OoxmlRelationship? =
        rels.firstOrNull { it.shortType == shortType.lowercase() }
}
