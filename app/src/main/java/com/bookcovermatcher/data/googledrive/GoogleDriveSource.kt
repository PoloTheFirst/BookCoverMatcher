// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data.googledrive

import com.bookcovermatcher.core.remote.*
import com.bookcovermatcher.core.wishlist.MiniJson
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.*

/** Read-only My Drive transport. Tokens are memory-only and never enter persisted/UI state. */
class GoogleDriveSource(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : RemoteWorkbookSource {
    @Volatile var token: String? = null

    override suspend fun listFolder(folderId: String?, pageToken: String?): RemoteResult<RemoteListing> = withContext(Dispatchers.IO) {
        try {
            val parent = (folderId ?: "root").replace("\\", "\\\\").replace("'", "\\'")
            val params = linkedMapOf(
                "q" to "'$parent' in parents and trashed = false",
                "spaces" to "drive", "pageSize" to "100", "orderBy" to "folder,name",
                "fields" to "nextPageToken,files(id,name,mimeType,size,modifiedTime,capabilities(canDownload))",
            )
            if (pageToken != null) params["pageToken"] = pageToken
            val data = json("files?" + params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) })
            RemoteResult.Ok(GoogleDriveFiles.listing(data, folderId))
        } catch (e: CancellationException) { throw e }
        catch (e: DriveFailure) { RemoteResult.Err(e.failure) }
        catch (e: SocketTimeoutException) { RemoteResult.Err(RemoteFailure.Timeout) }
        catch (e: java.io.IOException) { RemoteResult.Err(RemoteFailure.Unstable) }
        catch (e: Exception) { RemoteResult.Err(RemoteFailure.UnexpectedResponse) }
    }

    override suspend fun probe(itemId: String): RemoteResult<RemoteState> = withContext(Dispatchers.IO) {
        try {
            val data = json("files/${enc(itemId)}?fields=id,name,mimeType,size,modifiedTime,trashed,capabilities(canDownload)")
            val item = GoogleDriveFiles.item(data)
            if (item == null || data["trashed"] == true) RemoteResult.Ok(RemoteState.Missing)
            else RemoteResult.Ok(RemoteState.Present(item))
        } catch (e: CancellationException) { throw e }
        catch (e: DriveFailure) { RemoteResult.Err(e.failure) }
        catch (e: SocketTimeoutException) { RemoteResult.Err(RemoteFailure.Timeout) }
        catch (e: java.io.IOException) { RemoteResult.Err(RemoteFailure.Unstable) }
        catch (e: Exception) { RemoteResult.Err(RemoteFailure.UnexpectedResponse) }
    }

    override suspend fun download(item: RemoteItem, sink: OutputStream, onProgress: (DownloadProgress) -> Unit): DownloadOutcome = withContext(Dispatchers.IO) {
        val checked = probe(item.id)
        if (checked is RemoteResult.Err) return@withContext DownloadOutcome.Failed(checked.failure)
        val current = ((checked as RemoteResult.Ok).value as? RemoteState.Present)?.item
        if (current == null || current.isFolder || !GoogleDriveFiles.allowedName(current.name)) {
            return@withContext DownloadOutcome.Failed(RemoteFailure.InvalidWorkbook)
        }
        try {
            val connection = connect("files/${enc(item.id)}?alt=media")
            try {
                checkStatus(connection)
                val total = connection.contentLengthLong.takeIf { it >= 0 }
                var written = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        written += count
                        onProgress(DownloadProgress(written, total))
                    }
                }
                if (total != null && total != written) DownloadOutcome.Failed(RemoteFailure.UnexpectedResponse)
                else DownloadOutcome.Completed(written)
            } finally { connection.disconnect() }
        } catch (e: CancellationException) { throw e }
        catch (e: DriveFailure) { DownloadOutcome.Failed(e.failure) }
        catch (e: SocketTimeoutException) { DownloadOutcome.Failed(RemoteFailure.Timeout) }
        // Sink IO failures propagate to SafeWorkbookDownload, which distinguishes storage exhaustion.
    }

    private fun connect(path: String): HttpURLConnection {
        val accessToken = token ?: throw DriveFailure(RemoteFailure.AuthRequired)
        return connectionFactory(URL("https://www.googleapis.com/drive/v3/$path")).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
    }

    private fun checkStatus(connection: HttpURLConnection) {
        val code = connection.responseCode
        val failure = when (code) {
            200 -> null
            401 -> { token = null; RemoteFailure.AuthExpired }
            403 -> RemoteFailure.PermissionDenied
            404 -> RemoteFailure.NotFound
            429 -> RemoteFailure.Throttled(connection.getHeaderField("Retry-After")?.toLongOrNull())
            in 500..599 -> RemoteFailure.ServerError(code)
            else -> RemoteFailure.BadRequest
        }
        if (failure != null) throw DriveFailure(failure)
    }

    private fun json(path: String): Map<*, *> {
        val connection = connect(path)
        try {
            checkStatus(connection)
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= 2 * 1024 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            if (bytes.size > 2 * 1024 * 1024) throw DriveFailure(RemoteFailure.UnexpectedResponse)
            return MiniJson.parse(bytes.toString(Charsets.UTF_8)) as? Map<*, *>
                ?: throw DriveFailure(RemoteFailure.UnexpectedResponse)
        } finally { connection.disconnect() }
    }

    private class DriveFailure(val failure: RemoteFailure) : Exception()
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** Google documents are deliberately excluded, even if their display name ends with .xlsx. */
object GoogleDriveFiles {
    const val FOLDER = "application/vnd.google-apps.folder"
    fun allowedName(name: String): Boolean = name.endsWith(".xlsx", true) || name.endsWith(".xlsm", true)

    fun item(data: Map<*, *>): RemoteItem? {
        val id = data["id"] as? String ?: return null
        val name = data["name"] as? String ?: return null
        val mime = data["mimeType"] as? String ?: return null
        val folder = mime == FOLDER
        if (!folder && (!allowedName(name) || mime.startsWith("application/vnd.google-apps."))) return null
        if (!folder && (data["capabilities"] as? Map<*, *>)?.get("canDownload") == false) return null
        if (data["trashed"] == true) return null
        return RemoteItem(id, name, folder,
            sizeBytes = data["size"]?.toString()?.toLongOrNull(),
            lastModified = (data["modifiedTime"] as? String)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    fun listing(data: Map<*, *>, folderId: String?): RemoteListing {
        val files = data["files"] as? List<*> ?: error("Missing files array")
        val items = files.mapNotNull { (it as? Map<*, *>)?.let(::item) }
        return RemoteListing(folderId, items.filter { it.isFolder }, items.filterNot { it.isFolder },
            (data["nextPageToken"] as? String)?.takeIf { it.isNotBlank() })
    }
}
