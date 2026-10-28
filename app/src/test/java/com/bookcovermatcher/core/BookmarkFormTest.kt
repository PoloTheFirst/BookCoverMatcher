package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.ArtistFields
import com.bookcovermatcher.core.wishlist.BookmarkForm
import com.bookcovermatcher.core.wishlist.BookmarkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Placeholders (sample text only) and field-level validation, in visual order. */
class BookmarkFormTest {

    @Test fun theFolderAndBookmarkPlaceholdersAreTheExactSamples() {
        assertEquals("ブルアカ", BookmarkForm.FOLDER_NAME_PLACEHOLDER)
        assertEquals("みつ", BookmarkForm.TITLE_PLACEHOLDER)
        assertEquals(
            "https://www.dlsite.com/maniax-touch/work/=/product_id/RJ01266914.html",
            BookmarkForm.URL_PLACEHOLDER,
        )
    }

    @Test fun theArtistCircleAndPricePlaceholdersAreTheExactSamples() {
        assertEquals("eno", BookmarkForm.ARTIST_PLACEHOLDER)
        assertEquals("榎屋", BookmarkForm.CIRCLE_PLACEHOLDER)
        assertEquals("¥770", BookmarkForm.PRICE_PLACEHOLDER)
        assertEquals("先生がナギサ&サクラコとセックスします。", BookmarkForm.NOTES_PLACEHOLDER)
    }

    @Test fun anEmptyNameIsReportedOnTheNameField() {
        val errors = BookmarkForm.validate("", "https://example.com/book")

        assertEquals(setOf(BookmarkForm.FIELD_TITLE), errors.keys)
        assertEquals(BookmarkForm.FIELD_TITLE, BookmarkForm.firstInvalid(errors))
    }

    @Test fun aMissingUrlIsReportedOnTheUrlField() {
        val errors = BookmarkForm.validate("ブルアカ本", "")

        assertEquals(setOf(BookmarkForm.FIELD_URL), errors.keys)
        assertEquals(BookmarkForm.FIELD_URL, BookmarkForm.firstInvalid(errors))
    }

    @Test fun aNonHttpsUrlIsReportedOnTheUrlField() {
        assertEquals(setOf(BookmarkForm.FIELD_URL), BookmarkForm.validate("本", "http://example.com/x").keys)
    }

    @Test fun theTopmostInvalidFieldWins() {
        assertEquals(BookmarkForm.FIELD_TITLE, BookmarkForm.firstInvalid(BookmarkForm.validate("  ", "nope")))
    }

    @Test fun aValidFormHasNoErrors() {
        val errors = BookmarkForm.validate("みつ", BookmarkForm.URL_PLACEHOLDER)

        assertTrue(errors.isEmpty())
        assertNull(BookmarkForm.firstInvalid(errors))
    }

    @Test fun thePlaceholderIsNeverAcceptedAsTheName() {
        // The sample is UI text only: an empty field still fails, typing the sample yourself is valid.
        assertTrue(BookmarkForm.validate("", "https://example.com/book").isNotEmpty())
        assertTrue(BookmarkForm.validate(BookmarkForm.TITLE_PLACEHOLDER, "https://example.com/book").isEmpty())
    }

    @Test fun artistsStartWithOneEmptyRow() {
        assertEquals(listOf(""), ArtistFields.rows(emptyList()))
        assertEquals(listOf("eno", "ZUN"), ArtistFields.rows(listOf("eno", "ZUN")))
    }

    @Test fun thePlusButtonAddsAnIndependentRow() {
        val rows = ArtistFields.add(listOf("eno"))

        assertEquals(listOf("eno", ""), rows)
        val edited = rows.toMutableList().also { it[1] = "ZUN" }
        assertEquals(listOf("eno", "ZUN"), edited)
    }

    @Test fun aRowCanBeRemovedWithoutTouchingTheOthers() {
        assertEquals(listOf("eno", "ZUN"), ArtistFields.removeAt(listOf("eno", "ZUN", "第三"), 2))
        // The form always keeps one row: removing the last one clears it instead of emptying the form.
        assertEquals(listOf(""), ArtistFields.removeAt(listOf("eno"), 0))
    }

    @Test fun blankArtistRowsAreDroppedAndOrderIsKept() {
        assertEquals(listOf("eno", "ZUN"), ArtistFields.values(listOf(" eno ", "", "ZUN", "  ")))
    }

    @Test fun allInvalidFieldsAreReportedInOnePass() {
        val errors = BookmarkForm.validate("", "not a url")

        assertEquals(
            "both fields are reported together, in visual order",
            listOf(BookmarkForm.FIELD_TITLE, BookmarkForm.FIELD_URL),
            errors.keys.toList(),
        )
        assertEquals(BookmarkRules.ERROR_TITLE, errors[BookmarkForm.FIELD_TITLE])
        assertEquals(BookmarkRules.ERROR_HTTPS, errors[BookmarkForm.FIELD_URL])
        assertEquals(BookmarkForm.FIELD_TITLE, BookmarkForm.firstInvalid(errors))
    }

    @Test fun anEmptyTitleAndAnEmptyUrlAreBothReported() {
        val errors = BookmarkForm.validate("   ", "")

        assertEquals(2, errors.size)
        assertEquals(BookmarkRules.ERROR_URL_MISSING, errors[BookmarkForm.FIELD_URL])
        assertEquals(BookmarkRules.ERROR_TITLE, errors[BookmarkForm.FIELD_TITLE])
    }

    @Test fun fixingOneFieldKeepsTheOtherError() {
        val errors = BookmarkForm.validate("みつ", "")

        assertEquals(setOf(BookmarkForm.FIELD_URL), errors.keys)
        assertEquals(BookmarkForm.FIELD_URL, BookmarkForm.firstInvalid(errors))
    }

    @Test fun theUrlRulesAreExactlyThePreExistingOnes() {
        // Derived from BookmarkRules.validate (unchanged): https:// is required, and a host must parse.
        assertTrue(BookmarkForm.validate("みつ", "https://example.com/work").isEmpty())
        assertEquals(
            BookmarkRules.ERROR_HTTPS,
            BookmarkForm.validate("みつ", "http://example.com/work")[BookmarkForm.FIELD_URL],
        )
        assertEquals(
            BookmarkRules.ERROR_URL_SHAPE,
            BookmarkForm.validate("みつ", "https://")[BookmarkForm.FIELD_URL],
        )
    }

    @Test fun theFirstRowAlwaysCarriesThePlusButton() {
        for (count in 1..6) {
            assertEquals("row 0 of $count", ArtistFields.RowAction.ADD, ArtistFields.actionFor(0, count))
            for (index in 1 until count) {
                assertEquals(
                    "row $index of $count must have the remove control",
                    ArtistFields.RowAction.REMOVE,
                    ArtistFields.actionFor(index, count),
                )
            }
        }
    }

    @Test fun addingRowsNeverMovesThePlusButton() {
        var rows = ArtistFields.rows(listOf("eno"))
        rows = ArtistFields.add(rows)
        rows = ArtistFields.add(rows)

        assertEquals(listOf("eno", "", ""), rows)
        assertEquals(ArtistFields.RowAction.ADD, ArtistFields.actionFor(0, rows.size))
        assertEquals(ArtistFields.RowAction.REMOVE, ArtistFields.actionFor(1, rows.size))
        assertEquals(ArtistFields.RowAction.REMOVE, ArtistFields.actionFor(2, rows.size))
    }

    @Test fun removingAMiddleRowDropsOnlyThatValue() {
        val rows = ArtistFields.removeAt(listOf("eno", "ZUN", "第三"), 1)

        assertEquals(listOf("eno", "第三"), rows)
        assertEquals(ArtistFields.RowAction.ADD, ArtistFields.actionFor(0, rows.size))
        assertEquals(ArtistFields.RowAction.REMOVE, ArtistFields.actionFor(1, rows.size))
    }

    @Test fun removingTheLastRowKeepsTheEarlierValues() {
        assertEquals(listOf("eno", "ZUN"), ArtistFields.removeAt(listOf("eno", "ZUN", "第三"), 2))
    }

    @Test fun savedArtistsLoadInOrderWithTheSameArrangement() {
        val rows = ArtistFields.rows(listOf("eno", "ZUN"))

        assertEquals(listOf("eno", "ZUN"), rows)
        assertEquals(listOf("eno", "ZUN"), ArtistFields.values(rows))
        assertEquals(ArtistFields.RowAction.ADD, ArtistFields.actionFor(0, rows.size))
        assertEquals(ArtistFields.RowAction.REMOVE, ArtistFields.actionFor(1, rows.size))
    }

    @Test fun theFirstRowCanStayEmptyWhileLaterRowsHaveValues() {
        val rows = ArtistFields.add(ArtistFields.rows(emptyList()))
        val filled = rows.toMutableList().also { it[1] = "ZUN" }

        assertEquals(listOf("", "ZUN"), filled)
        assertEquals("a blank first row is not stored", listOf("ZUN"), ArtistFields.values(filled))
    }
}
