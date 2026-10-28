package com.bookcovermatcher.core.export

import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageState

/**
 * CSV manifest for a batch image export.
 *
 * One row per exported file, with the metadata the previewer actually knows. Unknown values stay empty
 * — nothing is invented. Escaping follows RFC 4180 (quotes doubled, fields with `,`, `"`, CR or LF
 * quoted), and the caller writes the text as UTF-8 so Japanese survives.
 */
object ImageManifest {

    val HEADER: List<String> = listOf(
        "exported_file",
        "media_file",
        "format",
        "bytes",
        "width",
        "height",
        "status",
        "worksheet",
        "cell",
        "title",
        "author",
        "circle",
    )

    /** Status wording matches the browser: linked / unreferenced / unresolved. */
    fun status(row: ImageRow): String = when (row.state) {
        ImageState.LINKED -> "linked"
        ImageState.UNREFERENCED -> "unreferenced"
        ImageState.UNRESOLVED -> "unresolved"
    }

    /** The CSV text for [entries] (export file name -> inventory row), header first. */
    fun csv(entries: List<Pair<String, ImageRow>>): String = buildString {
        append(HEADER.joinToString(",") { escape(it) }).append("\r\n")
        for ((exported, row) in entries) {
            val hit = row.catalogue.firstOrNull()
            val fields = listOf(
                exported,
                row.fileName,
                row.format,
                if (row.sizeBytes >= 0) row.sizeBytes.toString() else "",
                row.width?.toString().orEmpty(),
                row.height?.toString().orEmpty(),
                status(row),
                row.sheetName.orEmpty(),
                row.cell.orEmpty(),
                hit?.title.orEmpty(),
                hit?.author.orEmpty(),
                hit?.circle.orEmpty(),
            )
            append(fields.joinToString(",") { escape(it) }).append("\r\n")
        }
    }

    /** RFC 4180 field escaping; a plain value is written unchanged. */
    fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /**
     * A safe file name for the export: no path separators, no traversal, no control characters, a
     * sensible length cap, and the original extension preserved when it is present.
     */
    fun safeFileName(original: String, fallback: String = "image"): String {
        val base = original.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (ch in base) {
                append(
                    when {
                        ch == '\u0000' || ch.code < 0x20 -> '_'
                        ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?' ||
                            ch == '"' || ch == '<' || ch == '>' || ch == '|' -> '_'
                        else -> ch
                    },
                )
            }
        }.trim().trimStart('.')
        val withoutTraversal = cleaned.replace("..", "_")
        val name = withoutTraversal.ifBlank { fallback }
        if (name.length <= 120) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 8) name.substring(dot) else ""
        return name.take(120 - extension.length) + extension
    }

    /** De-duplicates within one export: `a.png`, `a (1).png`, `a (2).png` — never overwrites. */
    fun uniqueName(candidate: String, used: MutableSet<String>): String {
        if (used.add(candidate)) return candidate
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val ext = if (dot > 0) candidate.substring(dot) else ""
        var index = 1
        while (true) {
            val next = "$stem ($index)$ext"
            if (used.add(next)) return next
            index++
        }
    }
}
