package com.bookcovermatcher.ocr

import com.bookcovermatcher.data.OnlineSearchPrefs

/**
 * The OCR engines the Online tab can use for **READ THE COVER**.
 *
 * This is the single place that turns a stored preference value into something the app can act on
 * and display, so the UI, the controller and the prefs file cannot drift apart:
 *
 * | Enum | Stored id | Shown as |
 * |---|---|---|
 * | [CHROME_LENS] | `python` (legacy value, kept for upgrades) | Chrome Lens |
 * | [PADDLE_OCR] | `rust` | PaddleOCR (PP-OCRv6 Medium) |
 *
 * ### Aliases and unknown values
 *
 * An unknown or empty stored value resolves to [DEFAULT] (Chrome Lens), which is also the value a
 * fresh install gets. That is deliberate and safe, not a silent engine switch: Chrome Lens is the
 * engine the app shipped with, the UI always shows which engine is selected, and no value is ever
 * rewritten merely by reading it.
 *
 * `"paddle"`/`"paddleocr"` are accepted as aliases for the native engine because this feature is
 * named PaddleOCR in the UI; `"python"`/`"chrome_lens"`/`"lens"` all mean Chrome Lens, so a
 * preference written by an older or newer build still resolves to the engine it named.
 */
enum class OcrEngineChoice(
    /** The value stored in `OnlineSearchPrefs.ocrEngine`. */
    val id: String,
    /** The full, human-readable name used wherever there is room for it. */
    val label: String,
    /** The short name used in sentences ("Reading the cover with …"). */
    val shortLabel: String,
) {
    CHROME_LENS(OnlineSearchPrefs.ENGINE_PYTHON, "Chrome Lens", "Chrome Lens"),
    PADDLE_OCR(OnlineSearchPrefs.ENGINE_RUST, "PaddleOCR (PP-OCRv6 Medium)", "PaddleOCR");

    /** True for the native engine, false for the shipped Python one. */
    val isPaddle: Boolean get() = this == PADDLE_OCR

    companion object {
        /** Existing installations and new installations both start here. */
        val DEFAULT: OcrEngineChoice = CHROME_LENS

        /** Every choice, in the order the selector shows them (default first). */
        val ALL: List<OcrEngineChoice> = entries.toList()

        /** Resolves a stored value; see the class documentation for the alias rules. */
        fun fromId(id: String?): OcrEngineChoice = when (id?.trim()?.lowercase()) {
            "rust", "paddle", "paddleocr", "paddle_ocr", "ppocr" -> PADDLE_OCR
            else -> DEFAULT
        }
    }
}
