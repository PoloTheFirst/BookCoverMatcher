// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

/**
 * The raw JNI surface of `libbc_ocr.so`.
 *
 * The symbols here are implemented in `native/ocr/src/jni.rs`; the names are the contract, so
 * renaming a function on either side breaks the link (loudly, at the first call — which is why the
 * instrumentation test exercises every one of them).
 *
 * The library is loaded once, in this object's initializer. A load failure is **remembered** instead
 * of thrown: a build without the native library must still run the rest of the app, with OCR
 * reporting itself as unavailable. Calling any `external fun` after a failed load still throws
 * [UnsatisfiedLinkError], so callers must check [isLoaded] first — [RustOcrTextSource] does.
 */
object NativeOcrLib {

    private const val LIBRARY = "bc_ocr"

    /** `null` when the library loaded; otherwise the reason, kept for diagnostics. */
    val loadFailure: String? = try {
        System.loadLibrary(LIBRARY)
        null
    } catch (t: Throwable) {
        // UnsatisfiedLinkError (not packaged, wrong ABI) or, rarely, a broken dependency.
        t.message ?: t.javaClass.simpleName
    }

    val isLoaded: Boolean get() = loadFailure == null

    /** The crate version compiled into the library, e.g. `0.1.0`. */
    external fun version(): String

    /**
     * Opens both ONNX sessions. Returns a handle `> 0`, or `0` after throwing a Java exception
     * whose message says what was wrong (missing model, class-count mismatch, runtime failure).
     */
    external fun create(detector: String, recognizer: String, dictionary: String, runtimeLibrary: String): Long

    /** Model metadata in the `BC-OCR-INFO 1` format (see [NativeOcrProtocol.parseInfo]). */
    external fun describe(handle: Long): String

    /** Runs the whole pipeline; returns the `BC-OCR-RESULT 1` payload. */
    external fun recognise(handle: Long, argb: IntArray, width: Int, height: Int): String

    /** Drops the sessions. Idempotent. */
    external fun destroy(handle: Long)
}

/**
 * The JNI calls, as an interface, so [RustOcrTextSource] can be unit-tested on a desktop JVM.
 *
 * Only [NativeOcrLib] implements this in production code.
 */
interface NativeOcrApi {
    val isLoaded: Boolean
    val loadFailure: String?
    fun version(): String
    fun create(detector: String, recognizer: String, dictionary: String, runtimeLibrary: String): Long
    fun describe(handle: Long): String
    fun recognise(handle: Long, argb: IntArray, width: Int, height: Int): String
    fun destroy(handle: Long)
}

/** Delegates to [NativeOcrLib]. Deliberately does no error handling: the adapter decides. */
object NativeOcr : NativeOcrApi {
    override val isLoaded: Boolean get() = NativeOcrLib.isLoaded
    override val loadFailure: String? get() = NativeOcrLib.loadFailure
    override fun version(): String = NativeOcrLib.version()
    override fun create(detector: String, recognizer: String, dictionary: String, runtimeLibrary: String): Long =
        NativeOcrLib.create(detector, recognizer, dictionary, runtimeLibrary)
    override fun describe(handle: Long): String = NativeOcrLib.describe(handle)
    override fun recognise(handle: Long, argb: IntArray, width: Int, height: Int): String =
        NativeOcrLib.recognise(handle, argb, width, height)
    override fun destroy(handle: Long) = NativeOcrLib.destroy(handle)
}
