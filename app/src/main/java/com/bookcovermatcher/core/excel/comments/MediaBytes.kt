package com.bookcovermatcher.core.excel.comments

/**
 * Format naming for workbook media, without decoding.
 *
 * The extension is a hint only; [sniff] checks the magic bytes that the workbook actually stores, so a
 * `.png` file whose bytes are not a PNG is reported as unreadable instead of being drawn as an image.
 */
object MediaBytes {

    /** Short, human-readable format from the file extension (`png`, `jpeg`, ...); `unknown` otherwise. */
    fun fromExtension(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "png"
        "jpg", "jpeg", "jfif" -> "jpeg"
        "gif" -> "gif"
        "bmp" -> "bmp"
        "webp" -> "webp"
        "emf", "wmf" -> "emf"
        "tif", "tiff" -> "tiff"
        "svg" -> "svg"
        else -> "unknown"
    }

    /**
     * Format from the first bytes, or null when they match no format this app can draw. Used by the
     * previewer to show a placeholder + diagnostic for corrupt or unsupported media.
     */
    fun sniff(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 4) return null
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 -> "png"
            u(0) == 0xFF && u(1) == 0xD8 -> "jpeg"
            u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 -> "gif"
            u(0) == 0x42 && u(1) == 0x4D -> "bmp"
            u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
                bytes.size >= 12 && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
            u(0) == 0x01 && u(1) == 0x00 && u(2) == 0x00 && u(3) == 0x00 -> "emf"
            u(0) == 0xD7 && u(1) == 0xCD && u(2) == 0xC6 && u(3) == 0x9A -> "wmf"
            else -> null
        }
    }

    /** True when the bytes are an image Android's `BitmapFactory` is expected to decode. */
    fun isDrawable(bytes: ByteArray?): Boolean = sniff(bytes) in setOf("png", "jpeg", "gif", "bmp", "webp")
}
