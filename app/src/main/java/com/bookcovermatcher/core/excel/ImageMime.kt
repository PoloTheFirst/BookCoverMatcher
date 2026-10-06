package com.bookcovermatcher.core.excel

/** Port of `sniffMime` / `mimeFromExtension` / `resolvePath` from `js/excel.js`. */
object ImageMime {

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    fun sniff(b: ByteArray?): String? {
        if (b == null || b.size < 4) return null
        if (u(b, 0) == 0x89 && u(b, 1) == 0x50 && u(b, 2) == 0x4E && u(b, 3) == 0x47) return "image/png"
        if (u(b, 0) == 0xFF && u(b, 1) == 0xD8) return "image/jpeg"
        if (u(b, 0) == 0x47 && u(b, 1) == 0x49 && u(b, 2) == 0x46) return "image/gif"
        if (u(b, 0) == 0x42 && u(b, 1) == 0x4D) return "image/bmp"
        if (u(b, 0) == 0x52 && u(b, 1) == 0x49 && u(b, 2) == 0x46 && u(b, 3) == 0x46) return "image/webp"
        return null
    }

    fun fromExtension(ext: String?): String = when ((ext ?: "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg", "jfif" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    /** MIME from magic bytes first, then from the file extension (same order as v1). */
    fun detect(bytes: ByteArray, path: String): String =
        sniff(bytes) ?: fromExtension(path.substringAfterLast('.', path))
}

/** Resolves an OPC relationship `Target` against the directory of the part that owns it. */
object ZipPaths {
    fun resolve(baseDir: String, target: String?): String {
        if (target.isNullOrEmpty()) return ""
        if (target[0] == '/') return target.substring(1)
        val stack = ArrayList<String>()
        for (p in (baseDir + target).split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(p)
            }
        }
        return stack.joinToString("/")
    }
}
