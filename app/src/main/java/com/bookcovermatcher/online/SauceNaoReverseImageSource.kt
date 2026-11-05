package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSourceException
import com.bookcovermatcher.core.online.reverse.MultipartForm
import com.bookcovermatcher.core.online.reverse.ReverseImageAutomation
import com.bookcovermatcher.core.online.reverse.ReverseImageOutcome
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageRequest
import com.bookcovermatcher.core.online.reverse.ReverseImageSource
import com.bookcovermatcher.core.online.reverse.ReverseImageState
import com.bookcovermatcher.core.online.reverse.SauceNaoProtocol
import com.bookcovermatcher.core.online.reverse.SauceNaoResponseParser

/**
 * Reverse-image discovery through **SauceNAO**, the release's first-priority provider.
 *
 * The adapter is deliberately thin: [SauceNaoProtocol] owns the documented request fields, the JSON schema
 * lives in [SauceNaoResponseParser], and this class owns only the transport and the state mapping. That
 * split is what makes the protocol unit-testable without a socket.
 *
 * Deliberate behaviour:
 *
 *  * **No retries.** A rate-limited or quota-exhausted provider is reported, not hammered; retrying is what
 *    turns a soft limit into a long one.
 *  * **The key goes in the body.** It is read from [apiKey] (the Keystore-backed store) at call time and
 *    placed in the multipart body, never in a URL, never in a log line, never in an exception message.
 *  * **A missing key is not a search.** [readiness] reports `NOT_CONFIGURED` and [search] refuses before any
 *    socket is opened, so the UI can never claim SauceNAO "found nothing".
 *  * **Synchronous.** The controller runs it on `Dispatchers.IO`; the runner measures its own elapsed time.
 */
class SauceNaoReverseImageSource(
    /** Reads the user's key at call time; null/blank = not configured. */
    private val apiKey: () -> String?,
    private val http: HttpJsonClient = HttpJsonClient(
        connectTimeoutMs = 10_000,
        readTimeoutMs = 25_000,
        maxBytes = 512 * 1024,
    ),
    private val clock: () -> Long = System::currentTimeMillis,
) : ReverseImageSource {

    override val id: String get() = ReverseImageProviders.SAUCE_NAO_ID
    override val displayName: String get() = SauceNaoProtocol.DISPLAY_NAME
    override val priority: Int get() = ReverseImageProviders.SAUCE_NAO_PRIORITY
    override val automation: ReverseImageAutomation get() = ReverseImageAutomation.AUTOMATED

    /** SauceNAO answers in seconds; 30 s still leaves room for a slow mobile connection. */
    override val timeoutMs: Long = 30_000L

    override val searchPageUrl: String? get() = ReverseImageProviders.SAUCE_NAO_SEARCH_PAGE

    override fun readiness(): ReverseImageState =
        if (apiKey()?.isNotBlank() == true) ReverseImageState.READY else ReverseImageState.NOT_CONFIGURED

    override fun search(request: ReverseImageRequest): ReverseImageOutcome {
        val key = apiKey()?.trim()
        if (key.isNullOrEmpty()) {
            return ReverseImageOutcome.Failed(
                ReverseImageState.NOT_CONFIGURED,
                "No SauceNAO API key is stored, so nothing was sent.",
            )
        }
        if (!ReverseImageRequest.isUsableSize(request.sizeBytes)) {
            return ReverseImageOutcome.Failed(
                ReverseImageState.ERROR,
                "This image cannot be submitted (${request.sizeBytes} bytes).",
            )
        }
        if (request.mimeType.lowercase() !in SauceNaoProtocol.SUPPORTED_MIME) {
            return ReverseImageOutcome.Failed(
                ReverseImageState.ERROR,
                "SauceNAO accepts JPEG, PNG, GIF or WebP; this cover is ${request.mimeType}.",
            )
        }

        return try {
            val fields = SauceNaoProtocol.formFields(key, request.maxResults, request.minSimilarity)
            val form = MultipartForm.build(
                fields = fields,
                fileField = SauceNaoProtocol.FILE_FIELD,
                fileName = request.fileName,
                mimeType = request.mimeType,
                bytes = request.bytes,
            )
            val response = http.postMultipart(
                url = SauceNaoProtocol.ENDPOINT,
                contentType = form.contentType,
                body = form.body,
                acceptLanguage = "ja",
            )
            val parsed = SauceNaoResponseParser.parse(response.body, clock())
            when (parsed.state) {
                ReverseImageState.SUCCESS -> ReverseImageOutcome.Found(parsed.hits, parsed.quota)
                ReverseImageState.NO_RESULTS -> ReverseImageOutcome.NoResults(parsed.quota)
                else -> ReverseImageOutcome.Failed(
                    parsed.state,
                    parsed.message ?: "SauceNAO did not return a usable result document.",
                    parsed.quota,
                )
            }
        } catch (e: ProductSourceException) {
            ReverseImageOutcome.Failed(mapFailure(e.failure), describe(e.failure, e.message), null)
        } catch (t: Throwable) {
            ReverseImageOutcome.Failed(
                ReverseImageState.ERROR,
                "SauceNAO could not be reached: ${t.message ?: t.javaClass.simpleName}",
                null,
            )
        }
    }

    /** Transport failures, mapped onto the states this subsystem reports. */
    private fun mapFailure(failure: OnlineFailure): ReverseImageState = when (failure) {
        OnlineFailure.OFFLINE -> ReverseImageState.UNAVAILABLE
        OnlineFailure.TIMEOUT -> ReverseImageState.TIMEOUT
        OnlineFailure.RATE_LIMITED -> ReverseImageState.RATE_LIMITED
        OnlineFailure.BLOCKED -> ReverseImageState.BLOCKED
        OnlineFailure.NOT_CONFIGURED -> ReverseImageState.NOT_CONFIGURED
        OnlineFailure.SERVER_ERROR -> ReverseImageState.UNAVAILABLE
        OnlineFailure.BAD_RESPONSE, OnlineFailure.INTERNAL -> ReverseImageState.ERROR
    }

    /** A message that names the likely cause without ever echoing the credential. */
    private fun describe(failure: OnlineFailure, detail: String?): String {
        val base = when (failure) {
            OnlineFailure.BLOCKED ->
                "SauceNAO refused the request. It may be rejecting the stored key or the client; nothing was " +
                    "retried. Check the key in the SauceNAO settings."
            OnlineFailure.RATE_LIMITED -> "SauceNAO is throttling requests; try again later."
            OnlineFailure.TIMEOUT -> "SauceNAO did not answer in time."
            OnlineFailure.OFFLINE -> "No connection to SauceNAO."
            OnlineFailure.SERVER_ERROR -> "SauceNAO had a server problem."
            OnlineFailure.BAD_RESPONSE -> "SauceNAO sent something unexpected."
            OnlineFailure.NOT_CONFIGURED -> "No SauceNAO API key is stored."
            OnlineFailure.INTERNAL -> "The SauceNAO request failed."
        }
        return if (detail.isNullOrBlank()) base else "$base ($detail)"
    }
}
