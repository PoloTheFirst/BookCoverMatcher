// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.remote.AccessToken
import com.bookcovermatcher.core.remote.AccessTokenSource
import com.bookcovermatcher.core.remote.DownloadOutcome
import com.bookcovermatcher.core.remote.DownloadProgress
import com.bookcovermatcher.core.remote.GraphJson
import com.bookcovermatcher.core.remote.GraphUrls
import com.bookcovermatcher.core.remote.RemoteFailure
import com.bookcovermatcher.core.remote.RemoteItem
import com.bookcovermatcher.core.remote.RemoteListing
import com.bookcovermatcher.core.remote.RemoteResult
import com.bookcovermatcher.core.remote.RemoteState
import com.bookcovermatcher.core.remote.RemoteWorkbookSource
import com.bookcovermatcher.core.remote.TokenResult
import com.bookcovermatcher.core.remote.toRemoteFailure
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Microsoft Graph implementation of [RemoteWorkbookSource] (v2.6.0-dev.1 Phase 3).
 *
 * Small on purpose: URL construction and response parsing are the pure, unit-tested
 * [GraphUrls] / [GraphJson], transport is [GraphHttp], and this class only joins them, obtains a token
 * and keeps the blocking work off the caller's thread.
 *
 * **Read-only.** Three GET requests exist — children, item metadata, content — and no other verb is
 * reachable from here. The user's OneDrive is never written to.
 */
class GraphWorkbookSource(
    private val tokens: AccessTokenSource,
    private val http: GraphHttp = GraphHttp(),
    private val acceptFileName: (String) -> Boolean = RemoteWorkbookFilter::isSupported,
) : RemoteWorkbookSource {

    override suspend fun listFolder(folderId: String?, pageToken: String?): RemoteResult<RemoteListing> {
        val url = when {
            pageToken != null -> GraphUrls.resolvePageToken(pageToken)
                ?: return RemoteResult.Err(RemoteFailure.BadRequest)
            folderId == null -> GraphUrls.rootChildren()
            else -> GraphUrls.children(folderId)
        }
        val token = when (val state = requireToken()) {
            is TokenState.Got -> state.token
            is TokenState.Missing -> return RemoteResult.Err(state.failure)
        }
        return withContext(Dispatchers.IO) {
            when (val response = http.getJson(url, token, stage = "list")) {
                is GraphHttp.JsonOutcome.Body -> GraphJson.listing(folderId, response.text, acceptFileName)
                is GraphHttp.JsonOutcome.Failed -> RemoteResult.Err(response.failure)
            }
        }
    }

    override suspend fun probe(itemId: String): RemoteResult<RemoteState> {
        val token = when (val state = requireToken()) {
            is TokenState.Got -> state.token
            is TokenState.Missing -> return RemoteResult.Err(state.failure)
        }
        return withContext(Dispatchers.IO) {
            val response = http.getJson(GraphUrls.item(itemId), token, stage = "probe")
            if (response is GraphHttp.JsonOutcome.Failed) {
                // A deleted or moved file is a *state*, not an error: the caller keeps its local copy
                // and says so. Everything else is still a failure.
                return@withContext if (response.failure == RemoteFailure.NotFound) {
                    RemoteResult.Ok(RemoteState.Missing)
                } else {
                    RemoteResult.Err(response.failure)
                }
            }
            when (val parsed = GraphJson.item((response as GraphHttp.JsonOutcome.Body).text)) {
                is RemoteResult.Ok -> RemoteResult.Ok(RemoteState.Present(parsed.value))
                is RemoteResult.Err -> if (parsed.failure == RemoteFailure.NotFound) {
                    RemoteResult.Ok(RemoteState.Missing)
                } else {
                    RemoteResult.Err(parsed.failure)
                }
            }
        }
    }

    override suspend fun download(
        item: RemoteItem,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome {
        val token = when (val state = requireToken()) {
            is TokenState.Got -> state.token
            // "Sign in again" and "sign in first" are different sentences, so the reason is kept.
            is TokenState.Missing -> return DownloadOutcome.Failed(state.failure)
        }
        return withContext(Dispatchers.IO) { http.download(item.id, token, sink, onProgress) }
    }

    private sealed interface TokenState {
        data class Got(val token: AccessToken) : TokenState
        data class Missing(val failure: RemoteFailure) : TokenState
    }

    private suspend fun requireToken(): TokenState = when (val result = tokens.accessToken()) {
        is TokenResult.Success -> TokenState.Got(result.token)
        is TokenResult.Failure -> TokenState.Missing(result.failure.toRemoteFailure())
    }
}
