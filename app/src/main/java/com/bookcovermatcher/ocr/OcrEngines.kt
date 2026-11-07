// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

import android.content.Context
import com.bookcovermatcher.data.OnlineSearchPrefs
import com.bookcovermatcher.online.ChromeLensPyBridge
import com.bookcovermatcher.online.OcrAvailability
import com.bookcovermatcher.online.OcrText
import com.bookcovermatcher.online.OcrTextSource
import com.bookcovermatcher.online.PythonRuntime
import com.bookcovermatcher.vision.AndroidImageCodec
import java.io.File

/**
 * Picks the OCR engine the user's settings select.
 *
 * `chrome-lens-py` stays the default and the shipped path: the native engine is only used when the
 * engine preference says so explicitly, which the app exposes **only in debug builds** while the
 * migration is unfinished. There is deliberately **no automatic fallback**: if the native engine is
 * selected and cannot start, the user gets a native error rather than a silent switch, because a
 * silent switch would make "which engine read this cover?" unanswerable.
 */
object OcrEngines {

    /** The engine that ships: `chrome-lens-py` through Chaquopy. */
    const val PYTHON = OnlineSearchPrefs.ENGINE_PYTHON

    /** The engine under development: PP-OCRv6 in Rust. */
    const val RUST = OnlineSearchPrefs.ENGINE_RUST

    /**
     * The source the controller uses.
     *
     * It reads the engine preference on every call, so changing the setting takes effect on the next
     * cover read without rebuilding the controller — and the two engines' state (a Python
     * interpreter, a native session) each live in their own lazily-created delegate.
     */
    fun create(context: Context): OcrTextSource {
        val prefs = OnlineSearchPrefs(context)
        return SwitchingOcrTextSource(
            current = { prefs.ocrEngine },
            build = { engine -> if (engine == RUST) rustOcrTextSource(context) else ChromeLensPyBridge(PythonRuntime(context)) },
        )
    }
}

/**
 * Delegates to whichever engine the preference names, at call time.
 *
 * Keeping this separate from the two engines means the switch is a preference write rather than a
 * controller rebuild, and it is where the "no silent fallback" rule is enforced: the selected engine
 * answers, or its own error is reported.
 */
class SwitchingOcrTextSource(
    private val current: () -> String,
    private val build: (String) -> OcrTextSource,
) : OcrTextSource, AutoCloseable {

    private val lock = Any()
    private var active: Pair<String, OcrTextSource>? = null

    private fun delegate(): OcrTextSource {
        val wanted = current()
        synchronized(lock) {
            val existing = active
            if (existing != null && existing.first == wanted) return existing.second
            // Closing the previous engine releases its interpreter/sessions instead of leaving two
            // engines resident after a switch.
            (existing?.second as? AutoCloseable)?.let { runCatching { it.close() } }
            val created = build(wanted)
            active = wanted to created
            return created
        }
    }

    override fun availability(): OcrAvailability = delegate().availability()

    override fun recognise(
        imageBytes: ByteArray,
        ocrLanguage: String?,
        targetLanguage: String?,
        timeoutSeconds: Int,
    ): OcrText = delegate().recognise(imageBytes, ocrLanguage, targetLanguage, timeoutSeconds)

    override fun close() {
        synchronized(lock) {
            (active?.second as? AutoCloseable)?.let { runCatching { it.close() } }
            active = null
        }
    }
}

/**
 * Android wiring for [RustOcrTextSource].
 *
 * The runtime is resolved **by soname**, not by absolute path: Android loads app libraries through
 * a per-app linker namespace, and a raw `dlopen("/data/app/…/libonnxruntime.so")` from native code is
 * refused there. `libbc_ocr.so` is built with `load-dynamic`, so it links no runtime and asks the
 * dynamic loader for `libonnxruntime.so`; loading Maven's copy first means that request reuses the
 * mapping that already exists. One runtime per process, which is the invariant the crate was built
 * around.
 *
 * The models come from the APK: every build packages them under `assets/ocr/ppocrv6` (see
 * `preparePaddleOcrAssets` in `app/build.gradle.kts`), and the first OCR call extracts the three
 * files into private storage, verifying each SHA-256. A clean installation therefore needs no ADB,
 * no script and no network.
 */
fun rustOcrTextSource(context: Context): RustOcrTextSource {
    val codec = AndroidImageCodec()
    // Idempotent; the ONNX Runtime Java API would do this lazily anyway.
    runCatching { System.loadLibrary("onnxruntime") }
    val assets = context.assets
    return RustOcrTextSource(
        models = ppocrModels(context),
        runtimeLibraryPath = "",
        installer = { store, onProgress ->
            store.ensureInstalled({ name -> assets.open("${PpocrModels.ASSET_DIR}/$name") }, onProgress)
        },
        decode = { bytes, maxEdge -> codec.decodePhoto(bytes, maxEdge) },
    )
}

/** Where the detector, recogniser and dictionary live once installed. */
fun ppocrModels(context: Context): PpocrModels = PpocrModels(File(context.filesDir, "ocr/ppocrv6"))

/**
 * Where a developer can drop the three files for [PpocrModels.installFromDirectory].
 *
 * Only needed for builds made **without** the Gradle asset task (for example a quick local experiment
 * with `-x preparePaddleOcrAssets`): a normal build packages the models and the app extracts them from
 * the APK by itself. Internal storage, because `adb push` into the app's external directory produces
 * shell-owned files that Android 11+ hides from the app.
 */
fun ppocrImportDirectory(context: Context): File = File(context.filesDir, "ocr-import/ppocrv6")
