// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online.reverse

/**
 * `multipart/form-data` body builder for the one provider whose documented interface takes an upload.
 *
 * Written by hand for the same reason the app uses `HttpURLConnection`: a multipart POST is a dozen
 * lines of byte assembly and needs no networking or HTTP library. Keeping it here means the exact bytes
 * that leave the device are unit-tested, including the fact that a credential travels in the **body**
 * (never in a URL that ends up in a log or a `Referer`).
 *
 * Plain Kotlin/JVM; unit-tested.
 */
object MultipartForm {

    class Built(val contentType: String, val body: ByteArray)

    /**
     * @param fields ordinary text parts, written in iteration order
     * @param fileField the part name the provider documents for the image
     * @param boundary a caller-supplied boundary, so tests can pin the bytes exactly
     */
    fun build(
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        boundary: String = randomBoundary(),
    ): Built {
        require(boundary.isNotBlank()) { "a multipart boundary is required" }
        require(boundary.none { it == '\r' || it == '\n' }) { "a boundary must not contain a newline" }
        require(fileField.isNotBlank()) { "a file part name is required" }

        val out = java.io.ByteArrayOutputStream(bytes.size + 1024)
        for ((name, value) in fields) {
            out.writeAscii("--$boundary\r\n")
            out.writeAscii("Content-Disposition: form-data; name=\"${escape(name)}\"\r\n\r\n")
            out.writeUtf8(value)
            out.writeAscii("\r\n")
        }
        out.writeAscii("--$boundary\r\n")
        out.writeAscii(
            "Content-Disposition: form-data; name=\"${escape(fileField)}\"; " +
                "filename=\"${escape(fileName)}\"\r\n",
        )
        out.writeAscii("Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n")
        out.write(bytes)
        out.writeAscii("\r\n--$boundary--\r\n")
        return Built("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    /**
     * A boundary that cannot appear inside the payload: 24 random bytes, hex-encoded. The default is
     * random per request so two requests can never share one boundary.
     */
    fun randomBoundary(): String {
        val raw = ByteArray(24)
        java.security.SecureRandom().nextBytes(raw)
        return "----BookCoverMatcher" + raw.joinToString("") { "%02x".format(it) }
    }

    /** Quotes and backslashes are escaped as the header grammar requires; a `"` cannot break the part. */
    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun java.io.ByteArrayOutputStream.writeAscii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    private fun java.io.ByteArrayOutputStream.writeUtf8(text: String) = write(text.toByteArray(Charsets.UTF_8))
}
