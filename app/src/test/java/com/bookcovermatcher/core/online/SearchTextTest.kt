package com.bookcovermatcher.core.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search-text folding: full-width print becomes half-width for the catalogue query, and nothing
 * else moves. Written after the accuracy fixture run showed `じれったい２` being searched verbatim
 * for a cover whose title ends in an ASCII `2`.
 */
class SearchTextTest {

    @Test
    fun full_width_digits_and_latin_are_folded_for_searching() {
        assertEquals("2ギルR", SearchText.normalise("２ギルR"))
        assertEquals("R-18", SearchText.normalise("Ｒ－１８"))
        assertEquals("THE HOBBIT", SearchText.normalise("ＴＨＥ　ＨＯＢＢＩＴ"))
    }

    @Test
    fun japanese_text_and_its_punctuation_are_left_alone() {
        assertEquals("じれったい、2ギルR", SearchText.normalise("じれったい、2ギルR"))
        assertEquals("縦書き小説", SearchText.normalise("縦書き小説"))
        assertEquals("「この本」", SearchText.normalise("「この本」"))
    }

    @Test
    fun case_and_diacritics_are_not_folded() {
        assertEquals("Été", SearchText.normalise("Été"))
        assertEquals("Tifa", SearchText.normalise("Tifa"))
    }

    @Test
    fun blank_text_stays_blank_and_edges_are_trimmed() {
        assertEquals("", SearchText.normalise(""))
        assertEquals("", SearchText.normalise("   "))
        assertEquals("THE HOBBIT", SearchText.normalise("  THE HOBBIT  "))
    }

    @Test
    fun a_suggestion_searches_the_folded_title_but_reports_the_raw_one() {
        val suggestion = OcrQueryExtractor.suggest("じれったい２")
        assertEquals("the recognised text is kept verbatim", "じれったい２", suggestion?.title)
        assertEquals("the query is folded", "じれったい2", suggestion?.query)
        assertTrue(suggestion!!.confidence >= 1)
    }
}
