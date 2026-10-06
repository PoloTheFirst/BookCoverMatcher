package com.bookcovermatcher.core.excel

import com.bookcovermatcher.core.Config

/** Where an extracted picture came from. [label] is shown in the UI exactly as in v1. */
enum class ImageSource(val label: String) {
    FLOATING("floating"),
    COMMENT_FILL("comment-fill"),
}

/** A picture located in the package but not yet read (so bytes are never all held at once). */
class Candidate(
    val imageId: String,
    val cell: String,
    val row: Int,
    val col: Int,
    val source: ImageSource,
    val mediaPath: String,
)

/** Bytes of one picture handed to the decoder. */
class RawImage(
    val imageId: String,
    val cell: String,
    val row: Int,
    val col: Int,
    val source: ImageSource,
    val bytes: ByteArray,
    val mime: String,
)

class ExtractedImage<T : Any>(
    val imageId: String,
    val cell: String,
    val row: Int,
    val col: Int,
    val source: ImageSource,
    val payload: T,
)

class ExtractionPlan(val floating: List<Candidate>, val comments: List<Candidate>) {
    val total: Int get() = floating.size + comments.size
}

/**
 * Kotlin port of `js/excel.js` (v1.0.0). **The workflow is intentionally unchanged:**
 *
 *  1. *Comment-fill images*: every `xl/drawings/vmlDrawing<N>.vml` part is scanned; each
 *     `<v:shape>` whose `<x:Row>/<x:Column>` falls inside the range and that carries a picture
 *     fill (`o:relid` / `r:id`) is resolved through `xl/drawings/_rels/<part>.rels` to its media
 *     file. These are the pictures stored in **cell notes**.
 *  2. *Floating images*: pictures anchored in the first worksheet's drawing part (same behaviour
 *     as ExcelJS `sheet.getImages()` in v1). Disable with [Config.EXTRACT_FLOATING_IMAGES].
 *  3. Floating results come first, then comment-fill results; the first picture per cell address
 *     wins and later ones are dropped.
 *
 * Differences from the browser build that do not change results: parsing is done with the same
 * regular expressions on the raw XML; pictures are decoded lazily through [run]'s `decode`
 * callback (which returns `null` for undecodable pictures, as `bufferToCanvas` rejecting did).
 */
object WorkbookImageExtractor {

    // ---------- comment-fill (VML) ----------

    private val VML_PATH = Regex("^xl/drawings/vmlDrawing\\d+\\.vml$", RegexOption.IGNORE_CASE)
    private val REL_ELEMENT = Regex("<Relationship\\b[^>]*/?>")
    private val REL_ID = Regex("\\bId=\"([^\"]+)\"")
    private val REL_TARGET = Regex("\\bTarget=\"([^\"]+)\"")
    private val REL_TYPE = Regex("\\bType=\"([^\"]+)\"")
    private val SHAPE = Regex("<v:shape\\s[^>]*>([\\s\\S]*?)</v:shape>", RegexOption.IGNORE_CASE)
    private val X_ROW = Regex("<x:Row>\\s*(\\d+)\\s*</x:Row>", RegexOption.IGNORE_CASE)
    private val X_COL = Regex("<x:Column>\\s*(\\d+)\\s*</x:Column>", RegexOption.IGNORE_CASE)
    private val RELID_PATTERNS = listOf(
        Regex("<v:fill\\b[^>]*\\bo:relid=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
        Regex("<v:imagedata\\b[^>]*\\br:id=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
        Regex("<v:fill\\b[^>]*\\br:id=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
        Regex("<v:imagedata\\b[^>]*\\bo:relid=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
    )

    private fun relationships(relsText: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (el in REL_ELEMENT.findAll(relsText)) {
            val s = el.value
            val id = REL_ID.find(s)?.groupValues?.get(1)
            val target = REL_TARGET.find(s)?.groupValues?.get(1)
            if (id != null && target != null) map[id] = target
        }
        return map
    }

    private fun listCommentCandidates(zip: ZipSource, range: CellRange): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (vmlPath in zip.entryNames.filter { VML_PATH.containsMatchIn(it) }) {
            val vmlText = zip.readText(vmlPath) ?: continue

            val vmlName = vmlPath.substringAfterLast('/')
            val relsText = zip.readText("xl/drawings/_rels/$vmlName.rels") ?: continue
            val relMap = relationships(relsText)

            for (sm in SHAPE.findAll(vmlText)) {
                val full = sm.value
                val inner = sm.groupValues[1]

                val rowM = X_ROW.find(inner) ?: continue
                val colM = X_COL.find(inner) ?: continue
                val row = rowM.groupValues[1].toIntOrNull() ?: continue
                val col = colM.groupValues[1].toIntOrNull() ?: continue

                if (!range.contains(row, col)) continue

                var relId: String? = null
                for (p in RELID_PATTERNS) {
                    relId = p.find(full)?.groupValues?.get(1)
                    if (relId != null) break
                }
                if (relId == null) continue

                val target = relMap[relId] ?: continue
                val mediaPath = ZipPaths.resolve("xl/drawings/", target)
                if (!zip.contains(mediaPath)) continue

                out.add(
                    Candidate(
                        imageId = mediaPath,
                        cell = CellRange.indexToColumnLetters(col) + (row + 1),
                        row = row, col = col,
                        source = ImageSource.COMMENT_FILL,
                        mediaPath = mediaPath,
                    )
                )
            }
        }
        return out
    }

    // ---------- floating (anchored) pictures of the first worksheet ----------

    private val SHEET_ELEMENT = Regex("<sheet\\b[^>]*>")
    private val RID_ATTR = Regex("\\br:id=\"([^\"]+)\"")
    private val ANCHOR = Regex(
        "<(?:[A-Za-z0-9_]+:)?(twoCellAnchor|oneCellAnchor|absoluteAnchor)\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?\\1>"
    )
    private val FROM = Regex("<(?:[A-Za-z0-9_]+:)?from\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?from>")
    private val FROM_COL = Regex("<(?:[A-Za-z0-9_]+:)?col\\b[^>]*>\\s*(\\d+)\\s*</(?:[A-Za-z0-9_]+:)?col>")
    private val FROM_ROW = Regex("<(?:[A-Za-z0-9_]+:)?row\\b[^>]*>\\s*(\\d+)\\s*</(?:[A-Za-z0-9_]+:)?row>")
    private val PIC = Regex("<(?:[A-Za-z0-9_]+:)?pic\\b")
    private val BLIP_EMBED = Regex("<(?:[A-Za-z0-9_]+:)?blip\\b[^>]*?\\b(?:[A-Za-z0-9_]+:)?embed=\"([^\"]+)\"")

    private fun dirOf(path: String): String = path.substring(0, path.lastIndexOf('/') + 1)
    private fun nameOf(path: String): String = path.substringAfterLast('/')
    private fun relsPathOf(partPath: String): String = dirOf(partPath) + "_rels/" + nameOf(partPath) + ".rels"

    /** Path of the first `<sheet>` in `xl/workbook.xml` that is a real worksheet, or null. */
    internal fun firstWorksheetPath(zip: ZipSource): String? {
        val wb = zip.readText("xl/workbook.xml") ?: return null
        val rels = zip.readText("xl/_rels/workbook.xml.rels") ?: return null

        val relTarget = HashMap<String, String>()
        val relType = HashMap<String, String>()
        for (el in REL_ELEMENT.findAll(rels)) {
            val s = el.value
            val id = REL_ID.find(s)?.groupValues?.get(1) ?: continue
            REL_TARGET.find(s)?.groupValues?.get(1)?.let { relTarget[id] = it }
            REL_TYPE.find(s)?.groupValues?.get(1)?.let { relType[id] = it }
        }

        for (sheet in SHEET_ELEMENT.findAll(wb)) {
            val rid = RID_ATTR.find(sheet.value)?.groupValues?.get(1) ?: continue
            val target = relTarget[rid] ?: continue
            val type = relType[rid]
            val isWorksheet = if (type != null) type.endsWith("/worksheet") else target.contains("worksheets/")
            if (!isWorksheet) continue
            return ZipPaths.resolve("xl/", target)
        }
        return null
    }

    private fun listFloatingCandidates(zip: ZipSource, range: CellRange): List<Candidate> {
        val sheetPath = firstWorksheetPath(zip) ?: return emptyList()
        val sheetRels = zip.readText(relsPathOf(sheetPath)) ?: return emptyList()

        var drawingPath: String? = null
        for (el in REL_ELEMENT.findAll(sheetRels)) {
            val s = el.value
            val type = REL_TYPE.find(s)?.groupValues?.get(1) ?: continue
            if (!type.endsWith("/drawing")) continue
            val target = REL_TARGET.find(s)?.groupValues?.get(1) ?: continue
            drawingPath = ZipPaths.resolve(dirOf(sheetPath), target)
            break
        }
        if (drawingPath == null) return emptyList()

        val drawingXml = zip.readText(drawingPath) ?: return emptyList()
        val drawingRels = zip.readText(relsPathOf(drawingPath)) ?: return emptyList()
        val relMap = relationships(drawingRels)

        val out = ArrayList<Candidate>()
        for (anchor in ANCHOR.findAll(drawingXml)) {
            val body = anchor.groupValues[2]
            val from = FROM.find(body)?.groupValues?.get(1) ?: continue       // absoluteAnchor: skipped
            val col = FROM_COL.find(from)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val row = FROM_ROW.find(from)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!PIC.containsMatchIn(body)) continue
            val embed = BLIP_EMBED.find(body)?.groupValues?.get(1) ?: continue

            if (row < range.r1 || row > range.r2 || col < range.c1 || col > range.c2) continue

            val target = relMap[embed] ?: continue
            val mediaPath = ZipPaths.resolve(dirOf(drawingPath), target)
            if (!zip.contains(mediaPath)) continue

            out.add(
                Candidate(
                    imageId = "img#$mediaPath",
                    cell = CellRange.indexToColumnLetters(col) + (row + 1),
                    row = row, col = col,
                    source = ImageSource.FLOATING,
                    mediaPath = mediaPath,
                )
            )
        }
        return out
    }

    // ---------- public API ----------

    /** Throws [IllegalStateException] if the file is not a usable workbook. */
    fun requireWorkbook(zip: ZipSource) {
        if (!zip.contains("xl/workbook.xml")) {
            throw IllegalStateException("Not a valid .xlsx/.xlsm workbook (xl/workbook.xml is missing).")
        }
        if (firstWorksheetPath(zip) == null) {
            throw IllegalStateException("The workbook contains no worksheets.")
        }
    }

    /**
     * Locates every candidate picture inside [range] without reading image bytes.
     * A failure in one of the two sources never hides the other (v1 wrapped each in try/catch).
     */
    fun plan(
        zip: ZipSource,
        range: CellRange,
        includeFloating: Boolean = Config.EXTRACT_FLOATING_IMAGES,
    ): ExtractionPlan {
        val floating = if (includeFloating) {
            try { listFloatingCandidates(zip, range) } catch (e: Exception) { emptyList() }
        } else emptyList()
        val comments = try { listCommentCandidates(zip, range) } catch (e: Exception) { emptyList() }
        return ExtractionPlan(floating, comments)
    }

    /**
     * Reads and decodes candidates with the exact bookkeeping of v1:
     *  * floating: stops after [maxFloating] successfully decoded pictures;
     *  * comment-fill: a cell is only "taken" once a picture for it decoded successfully;
     *  * final pass keeps the first picture per cell (floating before comment-fill).
     *
     * [decode] returns `null` when the picture cannot be decoded; any exception it throws
     * propagates to the caller. [onStep] is called after each candidate with `(done, total)`.
     */
    fun <T : Any> run(
        zip: ZipSource,
        plan: ExtractionPlan,
        maxFloating: Int = Config.MAX_EXTRACTED_IMAGES,
        onStep: (done: Int, total: Int) -> Unit = { _, _ -> },
        decode: (RawImage) -> T?,
    ): List<ExtractedImage<T>> {
        val total = plan.total
        var done = 0
        val floatingOut = ArrayList<ExtractedImage<T>>()
        val commentOut = ArrayList<ExtractedImage<T>>()

        for (c in plan.floating) {
            done++
            val extracted = load(zip, c, decode)
            if (extracted != null) {
                floatingOut.add(extracted)
                if (floatingOut.size >= maxFloating) { onStep(done, total); break }
            }
            onStep(done, total)
        }
        done = plan.floating.size

        val seenCells = HashSet<String>()
        for (c in plan.comments) {
            done++
            if (c.cell !in seenCells) {
                val extracted = load(zip, c, decode)
                if (extracted != null) {
                    seenCells.add(c.cell)
                    commentOut.add(extracted)
                }
            }
            onStep(done, total)
        }

        val seen = HashSet<String>()
        val result = ArrayList<ExtractedImage<T>>()
        for (r in floatingOut + commentOut) if (seen.add(r.cell)) result.add(r)
        return result
    }

    fun <T : Any> extractAll(
        zip: ZipSource,
        range: CellRange,
        includeFloating: Boolean = Config.EXTRACT_FLOATING_IMAGES,
        decode: (RawImage) -> T?,
    ): List<ExtractedImage<T>> = run(zip, plan(zip, range, includeFloating), decode = decode)

    private fun <T : Any> load(zip: ZipSource, c: Candidate, decode: (RawImage) -> T?): ExtractedImage<T>? {
        val bytes = zip.read(c.mediaPath) ?: return null
        if (bytes.isEmpty()) return null
        val raw = RawImage(c.imageId, c.cell, c.row, c.col, c.source, bytes, ImageMime.detect(bytes, c.mediaPath))
        val payload = decode(raw) ?: return null
        return ExtractedImage(c.imageId, c.cell, c.row, c.col, c.source, payload)
    }
}
