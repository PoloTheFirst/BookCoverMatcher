// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

import java.text.Normalizer

/**
 * Folds the width variants a Japanese/Chinese OCR pass produces before the text is **searched**.
 *
 * The recogniser is trained on print, and print on a Japanese cover mixes widths: a cover title
 * `じれったい、2` came back with a full-width `２`, and `Ｒ-１８` is common on doujinshi covers. A
 * catalogue indexed with half-width digits does not match the full-width form, so the query is
 * normalised while the raw recognised text stays exactly as the model produced it (`OcrText.text`,
 * the on-screen result and the log all keep the raw string — this is evidence, not presentation).
 *
 * Deliberately only Unicode NFKC, which is the compatibility folding the Unicode standard defines:
 * full-width ASCII and the ideographic space become their half-width forms, nothing is transliterated,
 * no character is dropped, and Japanese punctuation (`、` `。` `「` `」`) and kanji/kana are untouched.
 * No case folding and no diacritic stripping either, because `É` and `é` are different titles.
 *
 * Plain Kotlin/JVM (no `android.*`).
 */
object SearchText {

    /** NFKC-normalised copy of [text]; a blank string stays blank. */
    fun normalise(text: String): String {
        if (text.isBlank()) return ""
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return folded.replace('\t', ' ').replace('\u3000', ' ').trim()
    }
}
