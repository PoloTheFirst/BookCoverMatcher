package com.bookcovermatcher.core.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.1 — the query model, the provider capability table and the explainable matcher.
 *
 * These are the pieces the unified search and the candidate comparison are built on, so they are pinned
 * here as plain Kotlin: what a typed query means, which provider may be given which part of it, and what
 * evidence a candidate-to-record comparison actually produces.
 */
class OnlineSearchRefinementTest {

    private fun candidate(
        title: String,
        circle: String? = null,
        authors: List<String> = emptyList(),
        identifier: String? = null,
        source: String = "DLsite",
    ): ProductSearchResult = ProductSearchResult(
        id = identifier ?: "id-$title",
        title = title,
        authors = authors,
        circle = circle,
        publishedYear = null,
        publishers = emptyList(),
        pageCount = null,
        languages = emptyList(),
        coverUrl = null,
        detailUrl = "https://example.test/item",
        source = source,
        providerId = if (source == "DLsite") "dl" else "ol",
        identifier = identifier,
    )

    // ---- query parsing ------------------------------------------------------------------------

    @Test
    fun `a plain query is a title`() {
        val query = OnlineQuery.parse("  Blue   Archive  ")
        assertEquals("Blue Archive", query.title)
        assertEquals("", query.circle)
        assertEquals("", query.code)
        assertEquals(listOf(SearchField.TITLE), query.fields)
        assertEquals("  Blue   Archive  ", query.original)
    }

    @Test
    fun `field prefixes split the parts`() {
        val query = OnlineQuery.parse("title:Blue Archive circle:Yostar author:Kim")
        assertEquals("Blue Archive", query.title)
        assertEquals("Yostar", query.circle)
        assertEquals("Kim", query.author)
        assertEquals(listOf(SearchField.TITLE, SearchField.CIRCLE, SearchField.AUTHOR), query.fields)
    }

    @Test
    fun `an rj code is lifted out of the text and normalised`() {
        val spaced = OnlineQuery.parse("rj 01234567")
        assertEquals("RJ01234567", spaced.code)
        assertEquals("", spaced.title)

        val inline = OnlineQuery.parse("RJ01234567 Blue Archive")
        assertEquals("RJ01234567", inline.code)
        assertEquals("Blue Archive", inline.title)

        assertEquals("RJ01234567", OnlineQuery.parse("code:RJ-01234567").code)
    }

    @Test
    fun `full-width latin and the ideographic space fold, japanese does not`() {
        val query = OnlineQuery.parse("Ｂｌｕｅ\u3000Ａｒｃｈｉｖｅ　同人誌")
        assertEquals("Blue Archive 同人誌", query.title)
        assertEquals("アイドル・マスター 〜特別編〜", OnlineQuery.parse("アイドル・マスター 〜特別編〜").title)
    }

    @Test
    fun `comparison forms strip only separators and decoration`() {
        assertEquals("RJ01234567", OnlineQuery.normalizeCode(" rj-0123 4567 "))
        assertEquals("blue archive", OnlineQuery.normalizeText("【Blue Archive】"))
        assertEquals("同人誌 特別編", OnlineQuery.normalizeText("同人誌・特別編"))
    }

    @Test
    fun `the keyword a provider receives keeps every part`() {
        val query = OnlineQuery.parse("title:Blue Archive circle:Yostar")
        assertEquals("Blue Archive Yostar", query.keyword())
        assertEquals("Yostar", query.without(SearchField.TITLE).keyword())
    }

    // ---- provider capabilities ----------------------------------------------------------------

    @Test
    fun `an author-only query is not sent to a provider without an author field`() {
        val query = OnlineQuery.parse("author:Kim")
        assertFalse("DLsite has no author field", ProviderCapabilities.canSearch("dl", query))
        assertTrue(ProviderCapabilities.canSearch("ol", query))
        assertTrue(ProviderCapabilities.canSearch("gb", query))
        val selected = setOf("dl", "ol", "gb")
        assertEquals(listOf("dl"), ProviderCapabilities.skipped(selected, query).map { it.id })
        assertEquals(listOf("ol", "gb"), ProviderCapabilities.eligible(selected, query).map { it.id })
    }

    @Test
    fun `a circle-only query goes to the shop, not to the bibliographic catalogues`() {
        val query = OnlineQuery.parse("circle:Yostar")
        assertTrue(ProviderCapabilities.canSearch("dl", query))
        assertFalse(ProviderCapabilities.canSearch("ol", query))
        assertEquals(listOf(SearchField.CIRCLE), ProviderCapabilities.of("ol")!!.ignoredFields(query))
        assertEquals(emptyList<SearchField>(), ProviderCapabilities.of("dl")!!.ignoredFields(query))
    }

    @Test
    fun `no provider claims image search, and a blank query searches nothing`() {
        assertTrue(ProviderCapabilities.ALL.none { it.supports(SearchField.IMAGE_REFERENCE) })
        val imageOnly = OnlineQuery(imageReference = "/covers/x.png")
        assertTrue(ProviderCapabilities.eligible(setOf("ol", "gb", "dl"), imageOnly).isEmpty())
        assertTrue(ProviderCapabilities.eligible(setOf("ol"), OnlineQuery()).isEmpty())
        assertEquals(listOf("ol", "gb", "dl"), ProviderCapabilities.ALL.map { it.providerId })
    }

    // ---- matching -----------------------------------------------------------------------------

    @Test
    fun `a provider that sends no identifier is not punished for it`() {
        val local = LocalProduct("row-4", "Blue Archive", circle = "Yostar", identifier = "RJ01234567")
        val match = CandidateMatcher.compare(
            candidate("Blue Archive", circle = "Yostar", source = "Open Library"),
            local,
        )
        val identifier = match.evidence.first { it.dimension == "identifier" }
        assertEquals(EvidenceState.UNKNOWN, identifier.state)
        assertTrue(identifier.detail.contains("no product identifier"))
        assertTrue("title and circle agree: ${match.score}", match.score >= 90)
        assertEquals(MatchVerdict.LIKELY_MATCH, match.verdict)
    }

    @Test
    fun `an exact identifier is decisive whatever the titles say`() {
        val local = LocalProduct("row-1", "まったく別の題名", circle = "Other", identifier = "RJ01234567")
        val match = CandidateMatcher.compare(candidate("Blue Archive", "Yostar", identifier = "RJ01234567"), local)
        assertEquals(MatchVerdict.LIKELY_MATCH, match.verdict)
        assertEquals("RJ01234567 is the same", match.evidence.first { it.dimension == "identifier" }.detail)
    }

    @Test
    fun `a different identifier is not a match`() {
        val local = LocalProduct("row-1", "Blue Archive", circle = "Yostar", identifier = "RJ09999999")
        val match = CandidateMatcher.compare(candidate("Blue Archive", "Yostar", identifier = "RJ01234567"), local)
        assertEquals(MatchVerdict.NO_MATCH, match.verdict)
        assertEquals(EvidenceState.MISMATCH, match.evidence.first { it.dimension == "identifier" }.state)
    }

    @Test
    fun `the same title by another circle is not a match`() {
        val local = LocalProduct("row-2", "Blue Archive", circle = "Yostar")
        val match = CandidateMatcher.compare(candidate("Blue Archive", circle = "Someone Else"), local)
        assertEquals(MatchVerdict.NO_MATCH, match.verdict)
        assertEquals(EvidenceState.MISMATCH, match.evidence.first { it.dimension == "circle" }.state)
    }

    @Test
    fun `a title alone is only ever a possible match`() {
        val local = LocalProduct("row-3", "Blue Archive")
        val match = CandidateMatcher.compare(candidate("Blue Archive"), local)
        assertEquals(MatchVerdict.POSSIBLE_MATCH, match.verdict)
        assertEquals(EvidenceState.MATCH, match.evidence.first { it.dimension == "title" }.state)
        assertEquals(
            listOf("identifier", "circle", "author", "cover"),
            match.evidence.filter { it.state == EvidenceState.UNKNOWN }.map { it.dimension },
        )
    }

    @Test
    fun `nothing comparable is insufficient evidence, not a match`() {
        val local = LocalProduct("row-5", title = "")
        val match = CandidateMatcher.compare(candidate("", identifier = null), local)
        assertEquals(MatchVerdict.INSUFFICIENT_EVIDENCE, match.verdict)
        assertEquals(0, match.score)
    }

    @Test
    fun `cover similarity participates when the embedding pipeline supplies it`() {
        val local = LocalProduct("row-6", "Blue Archive", circle = "Yostar")
        val plain = CandidateMatcher.compare(candidate("Blue Archive", circle = "Yostar"), local)
        val withCover = CandidateMatcher.compare(candidate("Blue Archive", circle = "Yostar"), local, 0.93)
        assertEquals(EvidenceState.UNKNOWN, plain.evidence.first { it.dimension == "cover" }.state)
        assertEquals(EvidenceState.MATCH, withCover.evidence.first { it.dimension == "cover" }.state)
        assertEquals("cover similarity 93%", withCover.evidence.first { it.dimension == "cover" }.detail)
    }

    @Test
    fun `two close records are reported as ambiguous instead of one being picked`() {
        val locals = listOf(
            LocalProduct("row-7", "Blue Archive", circle = "Yostar"),
            LocalProduct("row-8", "Blue Archive", circle = "Yostar"),
            LocalProduct("row-9", "Something Else", circle = "Nobody"),
        )
        val ranked = CandidateMatcher.rank(candidate("Blue Archive", circle = "Yostar"), locals)
        assertEquals("row-7", ranked.first().localKey)
        assertEquals(2, CandidateMatcher.ambiguous(ranked).size)
        assertEquals(1, CandidateMatcher.ambiguous(ranked.take(1)).size)
    }

    @Test
    fun `similarity is exact, containment or bigram overlap`() {
        assertEquals(1.0, CandidateMatcher.similarity("Blue Archive", "ｂｌｕｅ　ａｒｃｈｉｖｅ"), 0.0001)
        assertTrue(CandidateMatcher.similarity("Blue Archive", "Blue Archive Complete Edition") > 0.68)
        assertTrue(CandidateMatcher.similarity("Blue Archive", "Crimson Echo") < 0.4)
        assertEquals(0.0, CandidateMatcher.similarity("", "Blue Archive"), 0.0001)
    }
}
