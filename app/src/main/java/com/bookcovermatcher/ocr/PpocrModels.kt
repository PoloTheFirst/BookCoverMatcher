// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** What [PpocrModels] found on disk. */
sealed interface ModelState {
    data class Ready(val models: PpocrModels.Installed) : ModelState

    /** Nothing installed yet. */
    data object Absent : ModelState

    /** Files exist but are the wrong files (bad hash, truncated, wrong model). */
    data class Invalid(val reason: String) : ModelState
}

/**
 * Keeps the PP-OCRv6 detector, recogniser and dictionary in the app's private storage, pinned by
 * SHA-256.
 *
 * This follows [com.bookcovermatcher.data.ModelStore] — the mechanism the OpenCLIP encoder already
 * uses — rather than introducing a second storage or download system: copy to a `.part` file, hash
 * while copying, promote only when the hash matches, and remember the verification so the 139 MB is
 * not re-hashed on every launch.
 *
 * ### Where the files come from
 *
 * The three files are **packaged into every APK** under `assets/ocr/ppocrv6` (the Gradle task
 * `preparePaddleOcrAssets` fetches them at build time and refuses to build without them). On a clean
 * installation the first OCR call copies them into app-private storage here, verifying each SHA-256,
 * so a normal user never needs ADB, a script, a download or a directory picker. See `ensureInstalled`.
 *
 * ### Why the hashes are pinned here
 *
 * The recogniser's class count (18,710) only means anything together with *this* dictionary
 * (18,708 entries), and the detector was verified against *these* weights. A swapped or truncated
 * file would not fail loudly at inference time; it would produce plausible, wrong text. Pinning the
 * hashes turns that into a startup error naming the file that is wrong.
 */
class PpocrModels(
    private val root: File,
    /** Pinned `file name -> lowercase sha256`; injectable so tests can use small fixtures. */
    private val expected: Map<String, String> = PINNED_SHA256,
) {

    class Installed(val detector: File, val recognizer: File, val dictionary: File) {
        /** Shown in diagnostics; identical for every valid installation. */
        val fingerprint: String get() = FINGERPRINT
        val bytes: Long get() = detector.length() + recognizer.length() + dictionary.length()
    }

    /**
     * Current state, without hashing when a previous full verification still applies.
     *
     * The marker is trusted only when it names the pinned hashes *and* every file still has the
     * length recorded at verification time, so a truncated or replaced file triggers a real check.
     */
    fun state(): ModelState {
        val files = files()
        if (files.values.any { !it.isFile }) return ModelState.Absent
        val marker = File(root, MARKER)
        if (marker.isFile && markerMatches(marker.readText(), files)) {
            return ModelState.Ready(installed(files))
        }
        return verify(files)
    }

    /**
     * Verifies the installed files, or installs them from [open] when they are not usable yet.
     *
     * [open] returns one packaged asset by file name — on Android, `AssetManager.open("ocr/ppocrv6/x")`.
     * This is what makes a clean install work: the first OCR call extracts the APK's copies into
     * app-private storage, verifies every SHA-256, and only then reports them ready. Every later
     * launch costs one marker read.
     *
     * One extraction per process ([INSTALL_LOCK]), so two engine instances cannot write the same
     * files concurrently; an interrupted extraction leaves only `.part` files, which the next call
     * deletes and redoes.
     */
    fun ensureInstalled(open: (String) -> InputStream, onProgress: (Long) -> Unit = {}): ModelState {
        val current = state()
        if (current is ModelState.Ready) return current
        synchronized(INSTALL_LOCK) {
            val again = state()
            if (again is ModelState.Ready) return again
            return install(NAMES.associateWith { open(it) }, onProgress)
        }
    }

    /**
     * Copies the three files from [source] into place, hashing as it goes.
     *
     * Nothing is promoted until **all three** hashes match, so a wrong or half-copied file can never
     * be handed to ONNX Runtime.
     */
    fun installFromDirectory(source: File, onProgress: (Long) -> Unit = {}): ModelState {
        val missing = NAMES.filter { !File(source, it).isFile }
        if (missing.isNotEmpty()) return ModelState.Invalid("missing from ${source.name}: ${missing.joinToString()}")
        return install(NAMES.associateWith { File(source, it).inputStream() }, onProgress)
    }

    /** Same contract as [installFromDirectory], from streams (a picker, a test fixture). */
    fun install(sources: Map<String, InputStream>, onProgress: (Long) -> Unit = {}): ModelState {
        val unknown = sources.keys.filter { !expected.containsKey(it) }
        if (unknown.isNotEmpty()) return ModelState.Invalid("unexpected file ${unknown.joinToString()}")
        root.mkdirs()
        val staged = LinkedHashMap<String, File>()
        var copied = 0L
        try {
            for ((name, stream) in sources) {
                val part = File(root, "$name.part")
                part.delete()
                val sha = stream.use { input ->
                    part.outputStream().use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }
                val want = expected.getValue(name)
                if (sha != want) {
                    part.delete()
                    return ModelState.Invalid(
                        "$name does not match the pinned SHA-256 (got ${sha.take(12)}…, want ${want.take(12)}…)",
                    )
                }
                staged[name] = part
            }
            // Every file verified: promote them together and record the verification.
            val marker = StringBuilder()
            for ((name, part) in staged) {
                val target = File(root, name)
                target.delete()
                if (!part.renameTo(target)) throw IOException("could not store $name")
                marker.append(name).append(' ').append(expected.getValue(name)).append(' ')
                    .append(target.length()).append('\n')
            }
            File(root, MARKER).writeText(marker.toString())
            return ModelState.Ready(installed(files()))
        } catch (e: IOException) {
            // Nothing half-written may survive: a `.part` file is unrecognisable to a reader, and an
            // interrupted extraction must be retryable from scratch.
            staged.values.forEach { it.delete() }
            root.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            return ModelState.Invalid("the model files could not be stored: ${e.message}")
        } finally {
            staged.values.forEach { it.delete() }
            sources.values.forEach { runCatching { it.close() } }
        }
    }

    /** Deletes everything, used when a verification failure should not be retried in place. */
    fun remove() {
        root.listFiles()?.forEach { it.delete() }
    }

    private fun verify(files: Map<String, File>): ModelState {
        val marker = StringBuilder()
        for ((name, file) in files) {
            val want = expected[name] ?: return ModelState.Invalid("no pinned hash for $name")
            val got = sha256Of(file)
            if (got != want) {
                return ModelState.Invalid(
                    "$name does not match the pinned SHA-256 (got ${got.take(12)}…, want ${want.take(12)}…)",
                )
            }
            marker.append(name).append(' ').append(want).append(' ').append(file.length()).append('\n')
        }
        runCatching { File(root, MARKER).writeText(marker.toString()) }
        return ModelState.Ready(installed(files))
    }

    private fun markerMatches(text: String, files: Map<String, File>): Boolean {
        val recorded = text.lineSequence().filter { it.isNotBlank() }.map { it.trim().split(' ') }.toList()
        if (recorded.size != NAMES.size) return false
        for (parts in recorded) {
            if (parts.size != 3) return false
            val (name, sha, length) = parts
            val file = files[name] ?: return false
            if (sha != expected[name]) return false
            if (length.toLongOrNull() != file.length()) return false
        }
        return true
    }

    private fun files(): Map<String, File> = NAMES.associateWith { File(root, it) }

    private fun installed(files: Map<String, File>) = Installed(
        detector = files.getValue(DETECTOR),
        recognizer = files.getValue(RECOGNIZER),
        dictionary = files.getValue(DICTIONARY),
    )

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DETECTOR = "det.onnx"
        const val RECOGNIZER = "rec.onnx"
        const val DICTIONARY = "ppocrv6_dict.txt"
        const val MARKER = "verified.txt"
        const val FINGERPRINT = "ppocrv6-medium"

        /** Where the packaged assets live inside the APK. */
        const val ASSET_DIR = "ocr/ppocrv6"

        /** One extraction at a time, process-wide; see [ensureInstalled]. */
        private val INSTALL_LOCK = Any()

        val NAMES = listOf(DETECTOR, RECOGNIZER, DICTIONARY)

        /**
         * The exact artifacts verified on the host (see `historical_prompt/v2.5.0-dev.5-ag.md`):
         * `PaddlePaddle/PP-OCRv6_medium_{det,rec}_onnx` at revisions `6132380…` / `50c7eac…`, and the
         * Apache-2.0 `ppocrv6_dict.txt` committed to this repository.
         */
        val PINNED_SHA256: Map<String, String> = mapOf(
            DETECTOR to "eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1",
            RECOGNIZER to "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
            DICTIONARY to "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
        )
    }
}
