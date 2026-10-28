package com.bookcovermatcher.data

import com.bookcovermatcher.core.excel.comments.CommentDiagnostic
import com.bookcovermatcher.core.excel.comments.CommentMediaRef
import com.bookcovermatcher.core.excel.comments.DiagnosticLevel
import com.bookcovermatcher.core.excel.comments.LegacyCommentEntry
import com.bookcovermatcher.core.excel.comments.MediaAssociation
import com.bookcovermatcher.core.excel.comments.MediaRefKind
import com.bookcovermatcher.core.excel.comments.MediaResolution
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.WorkbookMediaEntry
import com.bookcovermatcher.core.wishlist.MiniJson
import java.io.File

/**
 * On-disk cache of a scanned workbook's legacy comments and media associations.
 *
 * Same idea as the existing `IndexCache`: one file per workbook under the app's private storage, a
 * bounded number of entries, atomic writes and "a broken file is deleted, not trusted".
 *
 * Identity is the workbook's **SHA-256** (already computed while staging the file), so replacing or
 * editing the workbook produces a different key instead of reusing stale data, and two workbooks can
 * never share an entry. [SCHEMA_VERSION] is stored in the file: a different version, a different hash
 * or an unparsable document invalidates the entry and the caller rescans from the package.
 */
class CommentImageCache(private val dir: File, private val maxEntries: Int = 6) {

    companion object {
        /** Bump when the stored shape changes; old files are then ignored and rewritten. */
        const val SCHEMA_VERSION = 1
    }

    private fun file(key: String) = File(dir, "comments-$key.json")

    fun read(sha256: String): WorkbookCommentImages? {
        val f = file(sha256)
        if (!f.isFile) return null
        return try {
            val root = MiniJson.parse(f.readText(Charsets.UTF_8)) as? Map<*, *> ?: return discard(f)
            if ((root["schema"] as? Double)?.toInt() != SCHEMA_VERSION) return discard(f)
            if (root["sha256"] != sha256) return discard(f)
            val model = decode(root) ?: return discard(f)
            f.setLastModified(System.currentTimeMillis())
            model
        } catch (e: Exception) {
            discard(f)
        }
    }

    fun write(model: WorkbookCommentImages) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val target = file(model.sha256)
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(MiniJson.write(encode(model)), Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            return
        }
        prune()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Drops one workbook's cached parse (used when that workbook is removed from the library). */
    fun remove(sha256: String) {
        file(sha256).delete()
    }

    private fun discard(f: File): WorkbookCommentImages? {
        f.delete()
        return null
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("comments-") && f.name.endsWith(".json") }
            ?: return
        if (files.size <= maxEntries) return
        files.sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { it.delete() }
    }

    // ---------------------------------------------------------------------------------------------

    private fun encode(model: WorkbookCommentImages): Map<String, Any?> = linkedMapOf(
        "schema" to SCHEMA_VERSION,
        "sha256" to model.sha256,
        "workbook" to model.workbookName,
        "sheets" to model.sheets,
        "truncated" to model.truncated,
        "threaded" to model.threadedCommentParts,
        "comments" to model.comments.map { c ->
            linkedMapOf(
                "sheet" to c.sheetName,
                "sheetPath" to c.sheetPath,
                "cell" to c.cell,
                "row" to c.row,
                "col" to c.col,
                "text" to c.text,
                "author" to c.author,
                "vmlRow" to c.vmlRow,
                "vmlCol" to c.vmlCol,
                "mismatch" to c.coordinateMismatch,
                "media" to c.media.map { m ->
                    linkedMapOf(
                        "ref" to m.reference,
                        "kind" to m.kind.name,
                        "resolved" to (m.resolution == MediaResolution.RESOLVED),
                        "path" to m.mediaPath,
                        "detail" to m.detail,
                    )
                },
            )
        },
        "media" to model.media.map { m ->
            linkedMapOf(
                "path" to m.path,
                "file" to m.fileName,
                "format" to m.format,
                "assoc" to m.associations.map { a ->
                    linkedMapOf("sheet" to a.sheetName, "cell" to a.cell, "preview" to a.commentPreview)
                },
            )
        },
        "diagnostics" to model.diagnostics.map { d ->
            linkedMapOf("level" to d.level.name, "part" to d.part, "message" to d.message)
        },
    )

    private fun decode(root: Map<*, *>): WorkbookCommentImages? {
        val sha = root["sha256"] as? String ?: return null
        val comments = (root["comments"] as? List<*>).orEmpty().mapNotNull { raw ->
            val c = raw as? Map<*, *> ?: return@mapNotNull null
            val sheet = c["sheet"] as? String ?: return@mapNotNull null
            val cell = c["cell"] as? String ?: return@mapNotNull null
            LegacyCommentEntry(
                sheetName = sheet,
                sheetPath = c["sheetPath"] as? String ?: "",
                cell = cell,
                row = int(c["row"]),
                col = int(c["col"]),
                text = c["text"] as? String ?: "",
                author = c["author"] as? String,
                media = (c["media"] as? List<*>).orEmpty().mapNotNull { m ->
                    val ref = m as? Map<*, *> ?: return@mapNotNull null
                    val value = ref["ref"] as? String ?: return@mapNotNull null
                    CommentMediaRef(
                        reference = value,
                        kind = enumOr(ref["kind"] as? String, MediaRefKind.SRC),
                        resolution = if (ref["resolved"] == true) MediaResolution.RESOLVED else MediaResolution.UNRESOLVED,
                        mediaPath = ref["path"] as? String,
                        detail = ref["detail"] as? String,
                    )
                },
                vmlRow = (c["vmlRow"] as? Double)?.toInt(),
                vmlCol = (c["vmlCol"] as? Double)?.toInt(),
                coordinateMismatch = c["mismatch"] == true,
            )
        }
        val media = (root["media"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val path = m["path"] as? String ?: return@mapNotNull null
            WorkbookMediaEntry(
                path = path,
                fileName = m["file"] as? String ?: path.substringAfterLast('/'),
                sizeBytes = -1L,
                format = m["format"] as? String ?: "unknown",
                associations = (m["assoc"] as? List<*>).orEmpty().mapNotNull { a ->
                    val assoc = a as? Map<*, *> ?: return@mapNotNull null
                    MediaAssociation(
                        sheetName = assoc["sheet"] as? String ?: "",
                        cell = assoc["cell"] as? String ?: "",
                        commentPreview = assoc["preview"] as? String ?: "",
                    )
                },
            )
        }
        val diagnostics = (root["diagnostics"] as? List<*>).orEmpty().mapNotNull { raw ->
            val d = raw as? Map<*, *> ?: return@mapNotNull null
            val message = d["message"] as? String ?: return@mapNotNull null
            CommentDiagnostic(
                level = enumOr(d["level"] as? String, DiagnosticLevel.WARNING),
                part = d["part"] as? String,
                message = message,
            )
        }
        return WorkbookCommentImages(
            workbookName = root["workbook"] as? String ?: "",
            sha256 = sha,
            sheets = (root["sheets"] as? List<*>).orEmpty().filterIsInstance<String>(),
            comments = comments,
            media = media,
            diagnostics = diagnostics,
            threadedCommentParts = (root["threaded"] as? List<*>).orEmpty().filterIsInstance<String>(),
            fromCache = true,
            truncated = root["truncated"] == true,
        )
    }

    private fun int(value: Any?): Int = (value as? Double)?.toInt() ?: 0

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
}
