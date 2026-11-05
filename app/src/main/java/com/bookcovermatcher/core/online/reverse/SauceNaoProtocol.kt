// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online.reverse

import com.bookcovermatcher.core.online.OnlineQuery
import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * SauceNAO's documented search API, as far as the app uses it: request parameters, the JSON response
 * schema and the state mapping.
 *
 * **Access method.** The API is an HTTP `POST` to `https://saucenao.com/search.php` with a
 * `multipart/form-data` body carrying the image, `output_type=2` (JSON) and the user's own `api_key`.
 * The credential therefore travels in the request **body**, not in a URL, which is why this class builds
 * fields for [MultipartForm] instead of a query string: a key in a URL ends up in `Referer` headers and
 * proxy logs.
 *
 * **What is documented versus measured.** The parameter names, the JSON envelope (`header`/`results`),
 * the `header.status` success value of `0` and the quota fields (`short_limit`, `short_remaining`,
 * `long_limit`, `long_remaining`) come from the provider's published API description. No live request was
 * made while writing this build (the documentation host answered HTTP 403 to the tooling used), so the
 * parser is defensive on purpose: any status other than `0` becomes a named failure carrying the
 * provider's own number, and an unknown extra field is kept as metadata instead of being interpreted.
 *
 * **Never invented.** Only the documented request parameters (`api_key`, `output_type`, `db`, `numres`,
 * `minsim`, `file`) and the documented response members are read.
 *
 * Plain Kotlin/JVM (no `android.*`), unit-tested against sanitised fixtures.
 */
object SauceNaoProtocol {

    const val ENDPOINT = "https://saucenao.com/search.php"
    const val DISPLAY_NAME = "SauceNAO"

    /** The documented file part name for the image upload. */
    const val FILE_FIELD = "file"

    /** `output_type=2` is the documented JSON output. */
    const val OUTPUT_TYPE_JSON = "2"

    /** `db=999` is the documented "search every index" value. */
    const val DB_ALL = "999"

    const val DEFAULT_MAX_RESULTS = 6
    const val MAX_RESULTS = 30

    /** Documented upload ceiling for an API call; the app caps covers far below it. */
    const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024

    /** The image types the documented API accepts. */
    val SUPPORTED_MIME: Set<String> = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    /**
     * The request body's text parts, in the documented spelling.
     *
     * @param apiKey the user's own key; never logged, never placed in the URL
     * @param minSimilarity 0–100 floor in SauceNAO's own scale, when the user asked for one
     */
    fun formFields(apiKey: String, maxResults: Int, minSimilarity: Int? = null): Map<String, String> {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "SauceNAO needs an API key" }
        val fields = LinkedHashMap<String, String>()
        fields["output_type"] = OUTPUT_TYPE_JSON
        fields["db"] = DB_ALL
        fields["numres"] = maxResults.coerceIn(1, MAX_RESULTS).toString()
        if (minSimilarity != null) fields["minsim"] = minSimilarity.coerceIn(0, 100).toString()
        fields["api_key"] = key
        return fields
    }

    /** The only thing the UI may ever show about a stored key: its last four characters. */
    fun maskKey(apiKey: String): String {
        val key = apiKey.trim()
        if (key.isEmpty()) return ""
        return "\u2022".repeat(8) + key.takeLast(4)
    }
}

/** One parsed SauceNAO answer, already mapped to a [ReverseImageState]. */
class SauceNaoResponse(
    val state: ReverseImageState,
    val hits: List<ReverseImageHit> = emptyList(),
    val quota: ReverseImageQuota? = null,
    /** Provider-reported detail. Never contains the API key. */
    val message: String? = null,
    /** `header.status` exactly as reported, for diagnostics. */
    val status: Int? = null,
)

/**
 * Parses the documented SauceNAO JSON answer.
 *
 * Failure mapping, all of it from the provider's own report or the transport:
 *  * unreadable document → [ReverseImageState.ERROR];
 *  * `header.status` other than `0` with an exhausted allowance → [ReverseImageState.QUOTA_EXCEEDED];
 *  * any other non-zero `header.status` → [ReverseImageState.ERROR] carrying that number;
 *  * `status = 0` with an empty `results` array → [ReverseImageState.NO_RESULTS] (a real answer);
 *  * `status = 0` with rows → [ReverseImageState.SUCCESS].
 */
object SauceNaoResponseParser {

    private val RJ = Regex("(?i)\\bRJ[\\s_-]?(\\d{6,8})\\b")

    fun parse(
        json: String,
        searchedAtMillis: Long,
        providerName: String = SauceNaoProtocol.DISPLAY_NAME,
    ): SauceNaoResponse {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            return SauceNaoResponse(
                state = ReverseImageState.ERROR,
                message = "SauceNAO answered with something that is not JSON: ${e.message ?: "unreadable"}",
            )
        } catch (e: Exception) {
            return SauceNaoResponse(ReverseImageState.ERROR, message = "SauceNAO's answer could not be read.")
        }
        if (root !is Map<*, *>) {
            return SauceNaoResponse(ReverseImageState.ERROR, message = "SauceNAO's answer was not a JSON object.")
        }

        val header = root["header"] as? Map<*, *>
            ?: return SauceNaoResponse(
                ReverseImageState.ERROR,
                message = "SauceNAO's answer had no header — the document is not the documented schema.",
            )

        val quota = ReverseImageQuota(
            shortLimit = int(header["short_limit"]),
            longLimit = int(header["long_limit"]),
            shortRemaining = int(header["short_remaining"]),
            longRemaining = int(header["long_remaining"]),
        )
        val status = int(header["status"])

        if (status != null && status != 0) {
            val exhausted = quota.exhausted
            val state = if (exhausted) ReverseImageState.QUOTA_EXCEEDED else ReverseImageState.ERROR
            val detail = if (exhausted) {
                "SauceNAO reports no searches left in this window (status $status)."
            } else {
                "SauceNAO returned status $status instead of a result set."
            }
            return SauceNaoResponse(state, emptyList(), quota, detail, status)
        }

        val rows = root["results"] as? List<*> ?: emptyList<Any?>()
        val hits = rows.mapNotNull { row -> parseHit(row, providerName, searchedAtMillis) }
        return if (hits.isEmpty()) {
            SauceNaoResponse(ReverseImageState.NO_RESULTS, emptyList(), quota, null, status)
        } else {
            SauceNaoResponse(ReverseImageState.SUCCESS, hits, quota, null, status)
        }
    }

    private fun parseHit(row: Any?, providerName: String, searchedAtMillis: Long): ReverseImageHit? {
        val item = row as? Map<*, *> ?: return null
        val header = item["header"] as? Map<*, *>
        val data = item["data"] as? Map<*, *>

        val similarity = double(header?.get("similarity"))
        val thumbnail = text(header?.get("thumbnail"))
            ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        val indexName = text(header?.get("index_name"))

        val extUrls = (data?.get("ext_urls") as? List<*>)?.map { text(it) }.orEmpty()
        val sourceUrl = extUrls.firstOrNull { it.startsWith("https://", true) }
            ?: extUrls.firstOrNull { it.startsWith("http://", true) }
            ?: extUrls.firstOrNull { it.isNotBlank() }

        val title = firstNonBlank(
            text(data?.get("title")),
            text(data?.get("eng_name")),
            text(data?.get("jp_name")),
        )
        val circle = text(data?.get("company"))
        val author = firstNonBlank(
            joinValues(data?.get("creator")),
            text(data?.get("author_name")),
            text(data?.get("member_name")),
        )
        // A product code is extracted from what the provider actually sent; nothing is assumed about a
        // `dlsite_id`-style key existing in the response.
        val scannedForCode = listOfNotNull(title, sourceUrl, text(data?.get("dlsite_id")).takeIf { it.isNotBlank() })
            .joinToString(" ")
        val productId = RJ.find(scannedForCode)?.let { OnlineQuery.normalizeCode(it.value) }.orEmpty()

        val metadata = LinkedHashMap<String, String>()
        indexName.takeIf { it.isNotBlank() }?.let { metadata["index"] = it }
        text(header?.get("similarity")).takeIf { it.isNotBlank() }?.let { metadata["similarity"] = it }
        text(header?.get("index_id")).takeIf { it.isNotBlank() }?.let { metadata["index_id"] = it }
        data?.forEach { (key, value) ->
            val name = text(key)
            if (name.isBlank()) return@forEach
            val rendered = joinValues(value)
            if (rendered.isNotBlank()) metadata[name] = rendered
        }

        val fallbackId = listOf(indexName, similarity?.toString()).filterNotNull().joinToString("|")
        return ReverseImageHit(
            providerId = ReverseImageProviders.SAUCE_NAO_ID,
            providerName = providerName,
            providerResultId = sourceUrl ?: fallbackId.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            imageUrl = thumbnail,
            title = title,
            circle = circle,
            author = author,
            productId = productId,
            providerSimilarity = similarity,
            similarityScale = SimilarityScale.PERCENT_0_100,
            indexName = indexName.takeIf { it.isNotBlank() },
            metadata = metadata,
            searchedAtMillis = searchedAtMillis,
        )
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim()
        is Number -> if (value.toDouble() == Math.rint(value.toDouble())) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        else -> ""
    }

    /** Lists (`ext_urls`, `creator`) are joined; nested objects are skipped rather than guessed at. */
    private fun joinValues(value: Any?): String = when (value) {
        null -> ""
        is List<*> -> value.map { text(it) }.filter { it.isNotBlank() }.joinToString(", ")
        is Map<*, *> -> ""
        else -> text(value)
    }

    private fun int(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun double(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
}
