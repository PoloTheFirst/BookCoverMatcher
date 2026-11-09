// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.remote.DownloadOutcome
import com.bookcovermatcher.core.remote.DownloadProgress
import com.bookcovermatcher.core.remote.RemoteFailure
import com.bookcovermatcher.core.remote.RemoteItem
import com.bookcovermatcher.core.remote.RemoteWorkbookSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** What a download left behind. */
sealed interface DownloadResult {
    /**
     * A **validated** file, still named `*.part`. It is the caller's to commit (Phase 4 renames it into
     * the library and calls `WorkbookLibrary.import`), so a partial or invalid download can never be
     * mistaken for a saved workbook in the meantime.
     */
    data class Ready(val file: File, val displayName: String, val sizeBytes: Long) : DownloadResult

    data class Failed(val failure: RemoteFailure) : DownloadResult
    data object Cancelled : DownloadResult
}

/**
 * Streams one remote workbook to a temporary file, validates it, and cleans up after every failure
 * (v2.6.0-dev.1 Phase 3).
 *
 * Guarantees, in the order they matter:
 *
 *  1. **Nothing partial is ever left visible.** The bytes go to `<id>.part`; the library only ever sees
 *     a file this class returned as [DownloadResult.Ready], after validation.
 *  2. **Every failure path deletes the temporary file** — cancellation, transport failure, storage
 *     exhaustion, validation failure. A `.part` that outlives a session is a leak, so the deletion is in
 *     `finally`-equivalent paths rather than on the happy path only.
 *  3. **No size limit and no buffering.** The bytes are streamed through [RemoteWorkbookSource.download]
 *     straight to disk; the only number held in memory is a counter.
 *  4. **The destination is the caller's directory**, so this class never invents a library layout.
 */
class SafeWorkbookDownload(
    private val directory: File,
    private val validate: (File, String) -> RemoteFailure? = WorkbookDownloadValidator::validate,
) {

    suspend fun download(
        item: RemoteItem,
        source: RemoteWorkbookSource,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult {
        if (!directory.isDirectory && !directory.mkdirs()) return DownloadResult.Failed(RemoteFailure.NoStorage)
        val part = partFileFor(item)
        // A leftover from a killed process is not evidence of anything; start from zero.
        part.delete()

        val outcome = try {
            FileOutputStream(part).use { sink -> source.download(item, sink, onProgress) }
        } catch (e: CancellationException) {
            // The caller's coroutine was cancelled: keep it cancelled, but do not leave a file behind.
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            return DownloadResult.Failed(storageOrTransport(e))
        } catch (e: Exception) {
            part.delete()
            // Not an IOException, so not a transport failure: claiming "the connection failed" for an
            // unexpected exception is exactly the misreport this app must not make.
            return DownloadResult.Failed(RemoteFailure.UnexpectedResponse)
        }

        return when (outcome) {
            DownloadOutcome.Cancelled -> {
                part.delete()
                DownloadResult.Cancelled
            }
            is DownloadOutcome.Failed -> {
                part.delete()
                DownloadResult.Failed(outcome.failure)
            }
            is DownloadOutcome.Completed -> {
                // The server's byte count is not evidence the file is a workbook; the validator decides.
                val failure = validate(part, item.name)
                if (failure != null) {
                    part.delete()
                    DownloadResult.Failed(failure)
                } else {
                    DownloadResult.Ready(part, item.name, part.length())
                }
            }
        }
    }

    /**
     * The temporary name for [item]: `download-<sanitised id>.part`.
     *
     * Shape comes from the id, never from the display name — a remote name can contain `..`, `/` or a
     * Windows device name, and a download must never be able to choose its own path. The `.part` suffix
     * is what keeps it out of the library's view.
     */
    fun partFileFor(item: RemoteItem): File {
        val safe = item.id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(48)
            .ifBlank { "workbook" }
        return File(directory, "download-$safe.part")
    }

    private fun storageOrTransport(e: IOException): RemoteFailure {
        val message = e.message.orEmpty()
        val full = message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true) ||
            message.contains("There is not enough space", ignoreCase = true)
        return if (full) RemoteFailure.NoStorage else RemoteFailure.Unstable
    }
}
