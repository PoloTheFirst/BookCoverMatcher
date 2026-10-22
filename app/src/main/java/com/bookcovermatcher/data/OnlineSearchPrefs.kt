package com.bookcovermatcher.data

import android.content.Context

/**
 * User-controlled settings for the online-search feature.
 *
 * Same mechanism as [Prefs] (`SharedPreferences`, applied synchronously), but a separate file and
 * a separate store so the offline app keeps its own keys untouched.
 *
 * [enabled] defaults to **false**. BookCover Matcher shipped with no `INTERNET` permission at all,
 * so sending a cover to a third-party service has to be something the user turns on, never
 * something that happens because they installed a new version.
 */
class OnlineSearchPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Master switch for the whole online feature. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) { sp.edit().putBoolean(KEY_ENABLED, value).apply() }

    /**
     * Language Lens should assume when reading the cover. Empty means "let Lens decide", which is
     * what the offline-first user is expected to want.
     */
    var ocrLanguage: String
        get() = sp.getString(KEY_OCR_LANGUAGE, "") ?: ""
        set(value) { sp.edit().putString(KEY_OCR_LANGUAGE, value.trim().lowercase().take(2)).apply() }

    /**
     * Translate the recognised text into this language before searching. Empty means "no
     * translation" — the cover is searched as it was printed.
     */
    var targetLanguage: String
        get() = sp.getString(KEY_TARGET_LANGUAGE, "") ?: ""
        set(value) { sp.edit().putString(KEY_TARGET_LANGUAGE, value.trim().lowercase().take(2)).apply() }

    /** How many catalogue results to ask for. Clamped by [OpenLibraryBooksSource.MAX_LIMIT]. */
    var resultLimit: Int
        get() = sp.getInt(KEY_LIMIT, DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        set(value) { sp.edit().putInt(KEY_LIMIT, value.coerceIn(1, MAX_LIMIT)).apply() }

    /** Run the catalogue search straight after a successful OCR pass. */
    var autoSearch: Boolean
        get() = sp.getBoolean(KEY_AUTO_SEARCH, true)
        set(value) { sp.edit().putBoolean(KEY_AUTO_SEARCH, value).apply() }

    private companion object {
        const val STORE = "bookcover_online"
        const val KEY_ENABLED = "online_enabled"
        const val KEY_OCR_LANGUAGE = "online_ocr_language"
        const val KEY_TARGET_LANGUAGE = "online_target_language"
        const val KEY_LIMIT = "online_limit"
        const val KEY_AUTO_SEARCH = "online_auto_search"

        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 10
    }
}
