package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.ZipPaths
import com.bookcovermatcher.core.excel.ZipSource

/**
 * Reads legacy Excel comments (notes) and the images stored with them.
 *
 * Order of authority:
 *  1. the **comments XML** decides which cell a note belongs to and what it says;
 *  2. the **VML note shape** only supplies image references, matched by its zero-based `Row`/`Column`;
 *  3. every media path is validated against the package before it is reported.
 *
 * Nothing is guessed: a note shape that matches no comment, a comment with no shape, a missing media
 * part or a broken relationship becomes a [CommentDiagnostic], never a silent association and never an
 * exception. The source package is only read.
 */
object CommentImageScanner {

    fun scan(
        zip: ZipSource,
        workbookName: String,
        sha256: String,
        shouldStop: () -> Boolean = { false },
    ): CommentScanResult {
        val diagnostics = ArrayList<CommentDiagnostic>()

        val sheets = try {
            WorkbookSheets.list(zip)
        } catch (e: Exception) {
            return CommentScanResult.Failure("This file is not a readable .xlsx/.xlsm package.", diagnostics)
        }
        if (sheets.isEmpty()) {
            return CommentScanResult.Failure("The workbook contains no readable worksheets.", diagnostics)
        }

        val comments = ArrayList<LegacyCommentEntry>()
        val threaded = LinkedHashSet<String>()
        var truncated = false

        for (sheet in sheets) {
            if (shouldStop()) { truncated = true; break }

            val parts = try {
                WorkbookSheets.partsOf(zip, sheet)
            } catch (e: Exception) {
                diagnostics.add(warn(sheet.partPath, "Worksheet relationships could not be read: ${reason(e)}"))
                continue
            }
            threaded.addAll(parts.threadedCommentParts)

            val parsed = parts.commentsPart?.let { part ->
                readXml(zip, part, diagnostics)?.let { text ->
                    if (text.length > CommentScanLimits.MAX_XML_BYTES) {
                        diagnostics.add(warn(part, "The comments part is larger than the previewer limit; it was skipped."))
                        null
                    } else {
                        LegacyCommentParser.parse(text).also { result ->
                            if (!result.wellFormed) {
                                diagnostics.add(
                                    warn(part, "The comments part is not well-formed XML; readable comments were kept."),
                                )
                            }
                        }
                    }
                }
            }
            val sheetComments = parsed?.comments.orEmpty()

            // Every note shape of this sheet, with its image references already resolved.
            val shapes = LinkedHashMap<Long, List<CommentMediaRef>>()
            for (vmlPath in parts.vmlParts) {
                val vmlText = readXml(zip, vmlPath, diagnostics) ?: continue
                val rels = OoxmlRelationships.of(zip, vmlPath).associateBy { it.id }
                for (shape in VmlNoteParser.parse(vmlText)) {
                    val row = shape.row ?: continue
                    val col = shape.col ?: continue
                    val key = cellKey(row, col)
                    if (shapes.containsKey(key)) {
                        diagnostics.add(
                            warn(vmlPath, "A second note shape for row ${row + 1}, column ${col + 1} was ignored."),
                        )
                        continue
                    }
                    shapes[key] = resolveRefs(shape.refs, vmlPath, rels, zip, diagnostics)
                }
            }

            if (sheetComments.isEmpty()) {
                if (shapes.isNotEmpty()) {
                    diagnostics.add(
                        warn(sheet.partPath, "${shapes.size} VML note shape(s) found, but the sheet has no comments part."),
                    )
                }
                continue
            }
            if (parts.vmlParts.isEmpty()) {
                diagnostics.add(
                    warn(
                        sheet.partPath,
                        "The comments of '${sheet.name}' have no VML drawing; their text is shown without images.",
                    ),
                )
            }

            val shapeRows = HashMap<Long, Pair<Int, Int>>()
            for (shapeKey in shapes.keys) {
                shapeRows[shapeKey] = (shapeKey / 1_000_000L).toInt() to (shapeKey % 1_000_000L).toInt()
            }

            for (comment in sheetComments) {
                if (comments.size >= CommentScanLimits.MAX_COMMENTS) { truncated = true; break }
                val key = cellKey(comment.row, comment.col)
                val media = shapes.remove(key).orEmpty()
                val matched = media.isNotEmpty() || shapeRows.containsKey(key)
                comments.add(
                    LegacyCommentEntry(
                        sheetName = sheet.name,
                        sheetPath = sheet.partPath,
                        cell = comment.ref,
                        row = comment.row,
                        col = comment.col,
                        text = comment.text,
                        author = comment.author,
                        media = media,
                        vmlRow = comment.row.takeIf { matched },
                        vmlCol = comment.col.takeIf { matched },
                    ),
                )
            }

            for ((key, cell) in shapeRows) {
                if (shapes.containsKey(key)) {
                    diagnostics.add(
                        warn(
                            sheet.partPath,
                            "A note shape at row ${cell.first + 1}, column ${cell.second + 1} has no matching " +
                                "comment and was not associated with any cell.",
                        ),
                    )
                }
            }
        }

        return finish(zip, workbookName, sha256, sheets.map { it.name }, comments, threaded, diagnostics, truncated)
    }

    /** Builds the final model: threaded parts, associations, the media inventory, ordering. */
    private fun finish(
        zip: ZipSource,
        workbookName: String,
        sha256: String,
        sheetNames: List<String>,
        comments: List<LegacyCommentEntry>,
        threaded: LinkedHashSet<String>,
        diagnostics: MutableList<CommentDiagnostic>,
        truncatedIn: Boolean,
    ): CommentScanResult {
        var truncated = truncatedIn

        zip.entryNames
            .filter { it.startsWith("xl/threadedComments/") && !it.endsWith("/") }
            .forEach { threaded.add(it) }
        if (threaded.isNotEmpty()) {
            diagnostics.add(
                CommentDiagnostic(
                    DiagnosticLevel.INFO,
                    threaded.first(),
                    "This workbook has ${threaded.size} threaded-comment part(s). Threaded comments are not " +
                        "legacy notes and are not shown as note images here.",
                ),
            )
        }

        val associations = HashMap<String, MutableList<MediaAssociation>>()
        for (entry in comments) {
            for (path in entry.mediaPaths) {
                associations.getOrPut(path) { ArrayList() }
                    .add(MediaAssociation(entry.sheetName, entry.cell, preview(entry.text)))
            }
        }

        val allMedia = zip.entryNames.filter { it.startsWith("xl/media/") && !it.endsWith("/") }.sorted()
        if (allMedia.size > CommentScanLimits.MAX_MEDIA_FILES) {
            truncated = true
            diagnostics.add(
                warn(null, "Only the first ${CommentScanLimits.MAX_MEDIA_FILES} of ${allMedia.size} media files are listed."),
            )
        }
        val media = allMedia.take(CommentScanLimits.MAX_MEDIA_FILES).map { path ->
            WorkbookMediaEntry(
                path = path,
                fileName = path.substringAfterLast('/'),
                // Not read here: listing stays cheap; bytes are opened only when shown or exported.
                sizeBytes = -1L,
                format = MediaBytes.fromExtension(path),
                associations = associations[path].orEmpty(),
            )
        }

        val order = sheetNames.mapIndexed { index, name -> name to index }.toMap()
        return CommentScanResult.Success(
            WorkbookCommentImages(
                workbookName = workbookName,
                sha256 = sha256,
                sheets = sheetNames,
                comments = comments.sortedWith(
                    compareBy({ order[it.sheetName] ?: Int.MAX_VALUE }, { it.row }, { it.col }),
                ),
                media = media,
                diagnostics = diagnostics,
                threadedCommentParts = threaded.toList(),
                truncated = truncated,
            ),
        )
    }

    /**
     * Resolves the references of one note shape. A direct `src` wins when it resolves; relationship ids
     * are only consulted when no `src` worked, which is the documented preference.
     */
    private fun resolveRefs(
        refs: List<VmlNoteParser.RawRef>,
        vmlPath: String,
        rels: Map<String, OoxmlRelationship>,
        zip: ZipSource,
        diagnostics: MutableList<CommentDiagnostic>,
    ): List<CommentMediaRef> {
        if (refs.isEmpty()) return emptyList()

        val srcRefs = refs.filter { it.kind == MediaRefKind.SRC }.map { resolveSrc(it.value, vmlPath, zip) }
        if (srcRefs.any { it.resolution == MediaResolution.RESOLVED }) return dedupe(srcRefs)

        val relRefs = refs.filter { it.kind == MediaRefKind.RELID }
            .map { resolveRel(it.value, vmlPath, rels, zip) }
        val all = dedupe(srcRefs + relRefs)
        if (all.isNotEmpty() && all.all { it.resolution == MediaResolution.UNRESOLVED }) {
            diagnostics.add(warn(vmlPath, "A note shape references ${all.size} image(s) that could not be resolved."))
        }
        return all
    }

    /** A VML `src` is relative to the drawing part; external URLs are never followed. */
    private fun resolveSrc(raw: String, vmlPath: String, zip: ZipSource): CommentMediaRef {
        if (raw.contains("://") || raw.startsWith("data:", ignoreCase = true)) {
            return unresolved(raw, MediaRefKind.SRC, "external reference, not a package part")
        }
        val baseDir = vmlPath.substring(0, vmlPath.lastIndexOf('/') + 1)
        val path = ZipPaths.resolve(baseDir, raw)
        if (path.isBlank() || path.split('/').any { it == ".." }) {
            return unresolved(raw, MediaRefKind.SRC, "not a valid package path")
        }
        return existing(raw, MediaRefKind.SRC, path, zip)
    }

    private fun resolveRel(
        raw: String,
        vmlPath: String,
        rels: Map<String, OoxmlRelationship>,
        zip: ZipSource,
    ): CommentMediaRef {
        val rel = rels[raw]
            ?: return unresolved(raw, MediaRefKind.RELID, "relationship '$raw' is not declared by this drawing")
        if (rel.external) return unresolved(raw, MediaRefKind.RELID, "external relationship")
        val path = OoxmlRelationships.targetPath(vmlPath, rel)
            ?: return unresolved(raw, MediaRefKind.RELID, "target is not a package path")
        return existing(raw, MediaRefKind.RELID, path, zip)
    }

    private fun existing(raw: String, kind: MediaRefKind, path: String, zip: ZipSource): CommentMediaRef =
        if (zip.contains(path)) {
            CommentMediaRef(raw, kind, MediaResolution.RESOLVED, mediaPath = path)
        } else {
            unresolved(raw, kind, "the package has no part '$path'")
        }

    private fun unresolved(raw: String, kind: MediaRefKind, detail: String) =
        CommentMediaRef(raw, kind, MediaResolution.UNRESOLVED, detail = detail)

    /** The same media part twice is one image; different parts stay separate images. */
    private fun dedupe(refs: List<CommentMediaRef>): List<CommentMediaRef> {
        val seen = HashSet<String>()
        return refs.filter { seen.add(it.mediaPath ?: "${it.kind}:${it.reference}") }
    }

    private fun cellKey(row: Int, col: Int): Long = row.toLong() * 1_000_000L + col

    private fun preview(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= 120) line else line.take(119) + "\u2026"
    }

    private fun readXml(zip: ZipSource, path: String, diagnostics: MutableList<CommentDiagnostic>): String? = try {
        zip.readText(path) ?: run {
            diagnostics.add(warn(path, "The part is missing or too large to read."))
            null
        }
    } catch (e: Exception) {
        diagnostics.add(warn(path, "The part could not be read: ${reason(e)}"))
        null
    }

    private fun warn(part: String?, message: String) =
        CommentDiagnostic(DiagnosticLevel.WARNING, part, message)

    private fun reason(e: Exception): String = e.message ?: e.javaClass.simpleName
}
