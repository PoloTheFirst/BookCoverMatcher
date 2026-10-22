package com.bookcovermatcher.online

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Owns the embedded CPython interpreter that runs `chrome-lens-py`.
 *
 * Chaquopy starts one interpreter per process, so this is a process-wide singleton in practice:
 * [start] is idempotent and safe to call from any thread. Starting it costs a few hundred
 * milliseconds, which is why it happens lazily the first time the online panel is used rather
 * than in `Application.onCreate()` — the offline matcher must stay as fast as it was.
 *
 * If the interpreter cannot start (for example an unsupported ABI), the app does not crash: the
 * failure is remembered and reported through [problem] so the online panel can explain itself
 * while the rest of the app keeps working.
 */
class PythonRuntime(context: Context) {

    // A few test/instrumentation contexts have no Application; never pass null to Chaquopy.
    private val appContext = context.applicationContext ?: context

    @Volatile private var started = false
    @Volatile private var problem: String? = null

    val isReady: Boolean get() = started
    val failureReason: String? get() = problem

    /** @return true when an interpreter is running (it may already have been started elsewhere). */
    @Synchronized
    fun start(): Boolean {
        if (started) return true
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            started = true
            problem = null
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError, missing assets, unsupported ABI — all of them fatal for Python
            // only, never for the offline features.
            problem = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /** The interpreter, or null when it could not be started. */
    fun python(): Python? = if (start()) Python.getInstance() else null
}
