// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.reverse.MultipartForm
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageState
import com.bookcovermatcher.core.online.reverse.SauceNaoProtocol
import com.bookcovermatcher.core.online.reverse.SauceNaoResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — SauceNAO's documented protocol, without a socket.
 *
 * The fixtures are **sanitised**: invented titles and `example.test` URLs, no credential of any kind, and
 * nothing copied from a real response. What they pin is the shape the app depends on: the documented request
 * parts, the `header`/`results` envelope, the quota fields, and the exact state each kind of answer maps to.
 */
class SauceNaoProtocolTest {

    private val searchedAt = 1_700_000_000_000L

    private fun envelope(results: String, status: Int = 0, shortRemaining: Int = 3, longRemaining: Int = 99) =
        """
        {
          "header": {
            "status": $status,
            "short_limit": "4",
            "long_limit": "100",
            "short_remaining": $shortRemaining,
            "long_remaining": $longRemaining,
            "results_returned": 1,
            "minimum_similarity": "50"
          },
          "results": $results
        }
        """.trimIndent()

    private val twoResults = """
        [
          {
            "header": {
              "similarity": "95.1",
              "thumbnail": "https://img.example.test/thumb.jpg",
              "index_id": 5,
              "index_name": "Index #5: Example"
            },
            "data": {
              "ext_urls": ["https://shop.example.test/work/=/product_id/RJ01234567.html"],
              "title": "Blue Archive 同人誌",
              "creator": ["Yostar"],
              "company": "Yostar Circle",
              "dlsite_id": "RJ01234567"
            }
          },
          {
            "header": { "similarity": "71.4", "thumbnail": "https://img.example.test/t2.jpg", "index_name": "Index #9" },
            "data": { "ext_urls": ["https://art.example.test/999"], "title": "Other Work", "member_name": "Someone" }
          }
        ]
    """.trimIndent()

    // ---- request construction ------------------------------------------------------------------

    @Test
    fun `the documented request parts are sent, and the key only in the body fields`() {
        val fields = SauceNaoProtocol.formFields("SECRET-KEY-1234", maxResults = 6, minSimilarity = 80)
        assertEquals("2", fields["output_type"])
        assertEquals("999", fields["db"])
        assertEquals("6", fields["numres"])
        assertEquals("80", fields["minsim"])
        assertEquals("SECRET-KEY-1234", fields["api_key"])
        // The endpoint is the documented one and never carries a query string the key could hide in.
        assertEquals("https://saucenao.com/search.php", SauceNaoProtocol.ENDPOINT)
        assertFalse(SauceNaoProtocol.ENDPOINT.contains("?"))
    }

    @Test
    fun `the result count is capped and a blank key is refused`() {
        assertEquals("30", SauceNaoProtocol.formFields("k-12345678", maxResults = 999)["numres"])
        assertEquals("1", SauceNaoProtocol.formFields("k-12345678", maxResults = 0)["numres"])
        assertTrue(runCatching { SauceNaoProtocol.formFields("   ", maxResults = 5) }.isFailure)
    }

    @Test
    fun `a stored key is only ever shown masked`() {
        val masked = SauceNaoProtocol.maskKey("abcd1234567890wxyz")
        assertFalse("the mask must not contain the key", masked.contains("1234567890"))
        assertTrue(masked.endsWith("wxyz"))
        assertEquals("", SauceNaoProtocol.maskKey("  "))
    }

    // ---- response parsing ----------------------------------------------------------------------

    @Test
    fun `a successful answer is parsed with the provider's own number preserved`() {
        val parsed = SauceNaoResponseParser.parse(envelope(twoResults), searchedAt)
        assertEquals(ReverseImageState.SUCCESS, parsed.state)
        assertEquals(2, parsed.hits.size)
        assertEquals(0, parsed.status)

        val first = parsed.hits[0]
        assertEquals(ReverseImageProviders.SAUCE_NAO_ID, first.providerId)
        assertEquals(95.1, first.providerSimilarity!!, 0.0001)
        assertEquals("RJ01234567", first.productId)
        assertEquals("Yostar Circle", first.circle)
        assertEquals("Yostar", first.author)
        assertEquals("Blue Archive 同人誌", first.title)
        assertEquals("https://shop.example.test/work/=/product_id/RJ01234567.html", first.sourceUrl)
        assertEquals("https://img.example.test/thumb.jpg", first.imageUrl)
        assertEquals("Index #5: Example", first.indexName)
        assertEquals(searchedAt, first.searchedAtMillis)
        assertEquals("95.1", first.metadata["similarity"])

        val second = parsed.hits[1]
        assertEquals("", second.productId)
        assertEquals("Someone", second.author)
        assertEquals(71.4, second.providerSimilarity!!, 0.0001)
    }

    @Test
    fun `the quota the provider reported is kept`() {
        val quota = SauceNaoResponseParser.parse(envelope(twoResults), searchedAt).quota!!
        assertEquals(4, quota.shortLimit)
        assertEquals(3, quota.shortRemaining)
        assertEquals(100, quota.longLimit)
        assertFalse(quota.exhausted)
        assertEquals("99 of 100 left", quota.describe())
    }

    @Test
    fun `an empty result array is a real answer, not a failure`() {
        val parsed = SauceNaoResponseParser.parse(envelope("[]"), searchedAt)
        assertEquals(ReverseImageState.NO_RESULTS, parsed.state)
        assertTrue(parsed.hits.isEmpty())
        assertTrue("a genuine empty answer is not a failure", parsed.state.answered)

        val noArray = SauceNaoResponseParser.parse("""{"header":{"status":0}}""", searchedAt)
        assertEquals(ReverseImageState.NO_RESULTS, noArray.state)
    }

    @Test
    fun `an exhausted allowance is a quota state, not a generic error`() {
        val parsed = SauceNaoResponseParser.parse(
            envelope("[]", status = -1, shortRemaining = 0, longRemaining = 0),
            searchedAt,
        )
        assertEquals(ReverseImageState.QUOTA_EXCEEDED, parsed.state)
        assertTrue(parsed.quota!!.exhausted)
        assertEquals(-1, parsed.status)
        assertTrue(parsed.message!!.contains("no searches left"))
    }

    @Test
    fun `an unexplained non-zero status is reported with the provider's own number`() {
        val parsed = SauceNaoResponseParser.parse(envelope("[]", status = 3, shortRemaining = 2), searchedAt)
        assertEquals(ReverseImageState.ERROR, parsed.state)
        assertEquals(3, parsed.status)
        assertTrue(parsed.message!!.contains("status 3"))
        assertFalse("an error is not an empty answer", parsed.state.answered)
    }

    @Test
    fun `an unreadable document never becomes an empty result`() {
        assertEquals(ReverseImageState.ERROR, SauceNaoResponseParser.parse("<html>nope</html>", searchedAt).state)
        assertEquals(ReverseImageState.ERROR, SauceNaoResponseParser.parse("[]", searchedAt).state)
        assertEquals(ReverseImageState.ERROR, SauceNaoResponseParser.parse("{}", searchedAt).state)
        val missingHeader = SauceNaoResponseParser.parse("""{"results":[]}""", searchedAt)
        assertEquals(ReverseImageState.ERROR, missingHeader.state)
        assertFalse(missingHeader.state.answered)
    }

    @Test
    fun `a row without a similarity keeps null rather than a guessed number`() {
        val single = """[{"header":{"index_name":"Index #7"},"data":{"title":"No Score"}}]"""
        val hit = SauceNaoResponseParser.parse(envelope(single), searchedAt).hits.single()
        assertNull(hit.providerSimilarity)
        assertEquals("No Score", hit.title)
    }

    @Test
    fun `the upload body carries the image and the key as form parts, not as a query string`() {
        val image = ByteArray(8) { it.toByte() }
        val form = MultipartForm.build(
            fields = SauceNaoProtocol.formFields("SECRET-KEY-1234", maxResults = 4),
            fileField = SauceNaoProtocol.FILE_FIELD,
            fileName = "cover.jpg",
            mimeType = "image/jpeg",
            bytes = image,
            boundary = "BOUNDARY",
        )
        val text = String(form.body, Charsets.ISO_8859_1)

        assertEquals("multipart/form-data; boundary=BOUNDARY", form.contentType)
        assertTrue(text.contains("Content-Disposition: form-data; name=\"output_type\""))
        assertTrue(text.contains("Content-Disposition: form-data; name=\"api_key\""))
        assertTrue("the key travels in the body", text.contains("SECRET-KEY-1234"))
        assertTrue(text.contains("name=\"file\"; filename=\"cover.jpg\""))
        assertTrue(text.contains("Content-Type: image/jpeg"))
        assertTrue(text.trimEnd().endsWith("--BOUNDARY--"))
        assertTrue("the image bytes are present verbatim", form.body.size > image.size)
        assertFalse("no part of the request may be a URL query", form.contentType.contains("?"))
    }
}
