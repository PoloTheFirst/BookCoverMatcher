package com.bookcovermatcher.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Keeps the OpenCLIP image-encoder ONNX file in the app's private storage.
 *
 * The model can arrive two ways:
 *  * bundled: `tools/export_openclip_onnx.py` writes `app/src/main/assets/models/openclip_image.onnx`
 *    and the app copies it out of the APK on first launch (ONNX Runtime needs a real file path);
 *  * imported: the user picks the exported file in the setup screen.
 */
class ModelStore(private val context: Context) {

    class Installed(val file: File, val sha256: String) {
        /** Short id used in the index-cache key and shown in diagnostics. */
        val fingerprint: String get() = "onnx:" + sha256.take(16)
        val sizeBytes: Long get() = file.length()
    }

    private val dir = File(context.filesDir, "models")
    private val modelFile = File(dir, MODEL_NAME)
    private val shaFile = File(dir, "$MODEL_NAME.sha256")

    fun installed(): Installed? {
        if (!modelFile.isFile || modelFile.length() < MIN_BYTES) return null
        val recorded = if (shaFile.isFile) shaFile.readText().trim() else ""
        val sha = if (recorded.length == 64) recorded else sha256Of(modelFile).also { shaFile.writeText(it) }
        return Installed(modelFile, sha)
    }

    fun hasBundledAsset(): Boolean = try {
        context.assets.list(ASSET_DIR)?.contains(MODEL_NAME) == true
    } catch (e: IOException) {
        false
    }

    /** Copies the bundled model into private storage. [onProgress] gets the bytes copied so far. */
    fun installBundled(onProgress: (Long) -> Unit = {}): Installed =
        context.assets.open("$ASSET_DIR/$MODEL_NAME").use { install(it, onProgress) }

    /** Copies a user-picked file (Storage Access Framework URI) into private storage. */
    fun importFrom(uri: Uri, onProgress: (Long) -> Unit = {}): Installed {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file.")
        return stream.use { install(it, onProgress) }
    }

    /** Removes the installed model (used when it turns out to be unusable). */
    fun remove() {
        modelFile.delete()
        shaFile.delete()
    }

    private fun install(input: InputStream, onProgress: (Long) -> Unit): Installed {
        dir.mkdirs()
        val tmp = File(dir, "$MODEL_NAME.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var total = 0L
            DigestInputStream(input, digest).use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
            if (total < MIN_BYTES) throw IOException("The selected file is too small to be an OpenCLIP model.")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            remove()
            if (!tmp.renameTo(modelFile)) throw IOException("Could not store the model file.")
            shaFile.writeText(sha)
            return Installed(modelFile, sha)
        } finally {
            tmp.delete()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_NAME = "openclip_image.onnx"
        const val ASSET_DIR = "models"
        private const val MIN_BYTES = 1L * 1024 * 1024
    }
}
