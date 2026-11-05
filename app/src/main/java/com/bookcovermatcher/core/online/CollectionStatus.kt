// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.catalog.CatalogEntry
import com.bookcovermatcher.core.catalog.CatalogField

/**
 * Whether the open workbook already contains a record for a candidate.
 *
 * [ABSENT] is a real finding ("the catalogue was searched and this is not in it"), and it is deliberately
 * different from [UNKNOWN] ("the catalogue has no records to search", or the candidate carries too little
 * to compare). Collapsing those two is how an app ends up telling a user they do not own something it never
 * looked for.
 */
enum class CataloguePresence { MATCHED, AMBIGUOUS, ABSENT, UNKNOWN }

/**
 * The three facts about one item, kept **independent** on purpose:
 *
 *  * [owned] — the user says they have it. Never inferred from the catalogue.
 *  * [catalogued] — a matching record exists in the open workbook. Never inferred from ownership.
 *  * [wanted] — the user wants it. A wanted item may already be catalogued, and a catalogued item may
 *    never be wanted at all.
 *
 * Every field is nullable/defaulted to unknown, because "not stated" is the honest value for a fresh
 * candidate: a result found online says nothing about whether the user owns it.
 */
data class CollectionStatus(
    val owned: Boolean? = null,
    val catalogued: CataloguePresence = CataloguePresence.UNKNOWN,
    val wanted: Boolean? = null,
) {
    /** `Owned: not stated · In catalogue: matched · Wanted: yes` — nothing guessed. */
    fun describe(): String = buildString {
        append("Owned: ").append(triState(owned))
        append(" \u00B7 In catalogue: ").append(
            when (catalogued) {
                CataloguePresence.MATCHED -> "matched"
                CataloguePresence.AMBIGUOUS -> "several possible records"
                CataloguePresence.ABSENT -> "no record"
                CataloguePresence.UNKNOWN -> "unknown"
            },
        )
        append(" \u00B7 Wanted: ").append(triState(wanted))
    }

    private fun triState(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "not stated"
    }
}

/**
 * Turns a candidate's comparison against the open workbook into a [CollectionStatus].
 *
 * Only the catalogue dimension is derived here; ownership and wanted status are passed in **by the caller**
 * from the user's own declarations and are never computed from the match.
 */
object CollectionStatusOf {

    /**
     * @param matches the ranked comparisons of one candidate against the workbook's records
     * @param catalogueHasRecords false when the open workbook produced no records at all — then nothing can
     *        be claimed about presence, and the answer is [CataloguePresence.UNKNOWN], not absent
     */
    fun of(
        matches: List<ProductMatch>,
        catalogueHasRecords: Boolean = true,
        owned: Boolean? = null,
        wanted: Boolean? = null,
    ): CollectionStatus {
        if (!catalogueHasRecords) {
            return CollectionStatus(owned = owned, catalogued = CataloguePresence.UNKNOWN, wanted = wanted)
        }
        val best = matches.firstOrNull()
        val ambiguous = CandidateMatcher.ambiguous(matches)
        val presence = when {
            best == null -> CataloguePresence.ABSENT
            // Two records the app cannot tell apart are reported as ambiguous, even when both look likely:
            // silently picking one would be a guess dressed up as a fact.
            ambiguous.size > 1 -> CataloguePresence.AMBIGUOUS
            best.verdict == MatchVerdict.LIKELY_MATCH -> CataloguePresence.MATCHED
            best.verdict == MatchVerdict.POSSIBLE_MATCH -> CataloguePresence.AMBIGUOUS
            best.verdict == MatchVerdict.NO_MATCH -> CataloguePresence.ABSENT
            else -> CataloguePresence.UNKNOWN
        }
        return CollectionStatus(owned = owned, catalogued = presence, wanted = wanted)
    }
}

/**
 * The bridge from the workbook screens to the matcher: one [CatalogEntry] becomes one [LocalProduct].
 *
 * Only **mapped, present** cells are read ([CatalogEntry.text] returns null for an unmapped or blank field),
 * so a record with no circle is not treated as a record whose circle disagrees with the candidate — that
 * difference is what keeps "this provider has no circle field" from looking like evidence against a match.
 *
 * Plain Kotlin/JVM; unit-tested.
 */
object LocalProducts {

    fun of(entry: CatalogEntry): LocalProduct = LocalProduct(
        key = entry.id,
        title = entry.text(CatalogField.TITLE).orEmpty(),
        circle = entry.text(CatalogField.CIRCLE).orEmpty(),
        author = entry.text(CatalogField.ARTIST).orEmpty(),
        identifier = entry.text(CatalogField.IDENTIFIER).orEmpty(),
    )

    fun ofAll(entries: List<CatalogEntry>): List<LocalProduct> = entries.map { of(it) }
}
