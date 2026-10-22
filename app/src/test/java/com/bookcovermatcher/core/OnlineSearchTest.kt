package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.OcrQueryExtractor
import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.OpenLibraryResponseParser
import com.bookcovermatcher.core.online.ProductSearchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OCR-text → search-query rules. These are the heuristics the online panel lives by, so every
 * one of them is written down as an executable example.
 */
class OcrQueryExtractorTest {

    @Test fun titleIsTheTallestBlockNotMerelyTheFirstLine() {
        val ocr = "J.R.R. TOLKIEN\nTHE HOBBIT\nA LEGENDARY TALE"
        val heights = listOf(0.020f, 0.075f, 0.018f)

        val s = OcrQueryExtractor.suggest(ocr, heights)
        assertNotNull(s)
        assertEquals("THE HOBBIT", s!!.title)
        assertEquals("J.R.R. TOLKIEN", s.author)
        assertEquals(2, s.confidence)
    }

    @Test fun withoutGeometryTheFirstUsableLineWins() {
        val ocr = "J.R.R. TOLKIEN\nTHE HOBBIT"

        val s = OcrQueryExtractor.suggest(ocr)
        assertNotNull(s)
        assertEquals("J.R.R. TOLKIEN", s!!.title)
        // "THE HOBBIT" is an all-cap banner with no dotted initial, so it is deliberately not
        // guessed to be a person: a wrong author makes the catalogue search worse than none.
        assertNull(s.author)
        assertEquals("J.R.R. TOLKIEN", s.query)
    }

    @Test fun barcodePriceAndImprintLinesAreDropped() {
        val ocr = "THE HOBBIT\nISBN 9780000000000\nWILLIAM MORROW PAPERBACKS\nJ.R.R. TOLKIEN"

        assertEquals(listOf("THE HOBBIT", "J.R.R. TOLKIEN"), OcrQueryExtractor.lines(ocr))
        assertEquals("THE HOBBIT J.R.R. TOLKIEN", OcrQueryExtractor.query(ocr))
    }

    @Test fun aMixedCaseBylineIsRecognisedAsAnAuthor() {
        val ocr = "American Prometheus\nStuart A. Kriel"

        val s = OcrQueryExtractor.suggest(ocr)
        assertNotNull(s)
        assertEquals("American Prometheus", s!!.title)
        assertEquals("Stuart A. Kriel", s.author)
    }

    @Test fun metadataIsNeverTreatedAsAnAuthor() {
        val ocr = "DUNE\nTranslated from the Japanese\nBook One of the Chronicles"

        val s = OcrQueryExtractor.suggest(ocr)
        assertNotNull(s)
        assertNull("no author should be guessed here", s!!.author)
        assertEquals(1, s.confidence)
    }

    @Test fun nothingUsableMeansNoSuggestionAndAnEmptyQuery() {
        val ocr = "12345\n\$\$\$\n'''"

        assertTrue(OcrQueryExtractor.lines(ocr).isEmpty())
        assertNull(OcrQueryExtractor.suggest(ocr))
        assertEquals("", OcrQueryExtractor.query(ocr))
    }

    @Test fun personNameHeuristics() {
        assertTrue(OcrQueryExtractor.looksLikePersonName("J.R.R. TOLKIEN"))
        assertTrue(OcrQueryExtractor.looksLikePersonName("J.K. Rowling"))
        assertTrue(OcrQueryExtractor.looksLikePersonName("Ursula K. Le Guin"))
        assertFalse("a single word is not enough", OcrQueryExtractor.looksLikePersonName("Tolkien"))
        assertFalse("an all-cap banner is not a byline", OcrQueryExtractor.looksLikePersonName("WILLIAM MORROW PAPERBACKS"))
        assertFalse("a title is not a byline", OcrQueryExtractor.looksLikePersonName("THE HOBBIT"))
        assertFalse("metadata is not a byline", OcrQueryExtractor.looksLikePersonName("translated from the Japanese"))
        assertFalse("digits are not a byline", OcrQueryExtractor.looksLikePersonName("Edition 2001 Second"))
    }

    @Test fun whitespaceAndEdgePunctuationAreCollapsed() {
        assertEquals(
            listOf("The Hobbit", "or There and Back Again"),
            OcrQueryExtractor.lines("  “The   Hobbit” \n\n  or There  and Back Again  "),
        )
    }
}

/**
 * Reading an Open Library Books API response. The JSON below is the shape the live endpoint
 * returned when this was written (captured against `openlibrary.org/search.json`).
 */
class OpenLibraryResponseParserTest {

    private val sample = """
        {
          "numFound": 461,
          "start": 0,
          "docs": [
            {
              "key": "/works/OL27482W",
              "title": "The Hobbit",
              "author_name": ["J.R.R. Tolkien"],
              "first_publish_year": 1937,
              "publisher": ["George Allen & Unwin"],
              "cover_i": 14627509,
              "language": ["eng", "nob"],
              "number_of_pages": 310
            },
            {
              "key": "/works/OL219602W",
              "title": "The Hobbit",
              "cover_i": [8406766]
            },
            { "key": "/evil/OL1X", "title": "Key we should not follow" },
            { "key": "/works/OL3W", "title": "   " },
            { "key": "/books/OL999M", "title": "An edition record", "first_publish_year": 2001 }
          ]
        }
    """.trimIndent()

    @Test fun parsesWorksIntoProductResults() {
        val (items, total) = OpenLibraryResponseParser.parse(sample, limit = 10)

        assertEquals(3, items.size)
        assertEquals(461, total)

        val hobbit = items[0]
        assertEquals("OL27482W", hobbit.id)
        assertEquals("The Hobbit", hobbit.title)
        assertEquals(listOf("J.R.R. Tolkien"), hobbit.authors)
        assertEquals(1937, hobbit.publishedYear)
        assertEquals(listOf("George Allen & Unwin"), hobbit.publishers)
        assertEquals(310, hobbit.pageCount)
        assertEquals(listOf("eng", "nob"), hobbit.languages)
        assertEquals("Open Library", hobbit.source)
    }

    @Test fun urlsAreBuiltFromTheKeyNeverTakenFromThePayload() {
        val (items, _) = OpenLibraryResponseParser.parse(sample, limit = 10)

        assertEquals("https://openlibrary.org/works/OL27482W", items[0].detailUrl)
        assertEquals("https://covers.openlibrary.org/b/id/14627509-M.jpg", items[0].coverUrl)
        assertEquals("https://openlibrary.org/books/OL999M", items[2].detailUrl)
    }

    @Test fun aCoverIdSentAsAListIsStillRead() {
        val (items, _) = OpenLibraryResponseParser.parse(sample, limit = 10)

        assertEquals("https://covers.openlibrary.org/b/id/8406766-M.jpg", items[1].coverUrl)
    }

    @Test fun recordsWeCannotLinkToOrNameAreSkipped() {
        val (items, _) = OpenLibraryResponseParser.parse(sample, limit = 10)
        val ids = items.map { it.id }

        assertFalse("an unknown key shape must never become a URL", ids.contains("OL1X"))
        assertFalse("a record without a title is useless", ids.contains("OL3W"))
    }

    @Test fun missingOptionalFieldsDegradeToNullAndEmpty() {
        val (items, _) = OpenLibraryResponseParser.parse(sample, limit = 10)

        assertNull(items[1].publishedYear)
        assertNull(items[1].pageCount)
        assertTrue(items[1].authors.isEmpty())
        assertTrue(items[1].publishers.isEmpty())
        assertEquals("Unknown author", items[1].authorLine)
        assertEquals("year unknown · publisher unknown", items[1].metaLine)
    }

    @Test fun theLimitIsHonoured() {
        assertEquals(1, OpenLibraryResponseParser.parse(sample, limit = 1).first.size)
        assertEquals(2, OpenLibraryResponseParser.parse(sample, limit = 2).first.size)
        assertTrue(OpenLibraryResponseParser.parse(sample, limit = 0).first.isEmpty())
    }

    @Test fun anEmptyCatalogueIsNotAnError() {
        val (items, total) = OpenLibraryResponseParser.parse("""{"numFound":0,"docs":[]}""", limit = 5)

        assertTrue(items.isEmpty())
        assertEquals(0, total)
    }

    @Test fun aResponseOfTheWrongShapeIsReportedNotSwallowed() {
        val broken = listOf(
            """{"error": "boom"}""",
            """[]""",
            """not json at all""",
        )
        for (body in broken) {
            try {
                OpenLibraryResponseParser.parse(body, limit = 5)
                org.junit.Assert.fail("expected a ParseException for: $body")
            } catch (expected: OpenLibraryResponseParser.ParseException) {
                // exactly what the caller needs to turn into OnlineFailure.BAD_RESPONSE
            }
        }
    }

    @Test fun aBlankQueryNeverOpensASocket() {
        // OpenLibraryBooksSource is the real implementation; a blank query must short-circuit
        // before HttpJsonClient is touched, which is what keeps a mistyped search from costing
        // the user a request.
        val outcome = com.bookcovermatcher.online.OpenLibraryBooksSource().search("   ", 5)

        assertTrue(outcome is ProductSearchOutcome.Empty)
        assertEquals("", (outcome as ProductSearchOutcome.Empty).query)
    }
}

