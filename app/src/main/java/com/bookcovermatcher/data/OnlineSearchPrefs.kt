// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import android.content.Context
import com.bookcovermatcher.core.online.SearchLimits
import com.bookcovermatcher.core.online.SearchRouting

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

    /**
     * How many online results to ask for (it is a maximum, never a promise).
     *
     * Validated by [SearchLimits]: an unknown or corrupted value falls back to the default rather
     * than being sent to a provider. The **offline** workbook comparison has its own fixed limit
     * (`Config.TOP_MATCHES = 5`) and is not affected by this preference.
     */
    var resultLimit: Int
        get() = SearchLimits.sanitize(sp.getInt(KEY_LIMIT, SearchLimits.DEFAULT))
        set(value) { sp.edit().putInt(KEY_LIMIT, SearchLimits.sanitize(value)).apply() }

    /**
     * The platforms the user selected, as `ProviderCatalog` ids.
     *
     * Never written → the automated providers (what v2.2.1 always searched). An empty set is kept as
     * empty: "search nothing" is a real choice and must not silently revert to the defaults. Unknown
     * ids are dropped by [SearchRouting.sanitize], so a provider removed in a later build cannot linger.
     */
    var selectedProviders: Set<String>
        get() = SearchRouting.sanitize(sp.getStringSet(KEY_PROVIDERS, null))
        set(value) {
            sp.edit().putStringSet(KEY_PROVIDERS, SearchRouting.sanitize(value)).apply()
        }

    /** Run the catalogue search straight after a successful OCR pass. */
    var autoSearch: Boolean
        get() = sp.getBoolean(KEY_AUTO_SEARCH, true)
        set(value) { sp.edit().putBoolean(KEY_AUTO_SEARCH, value).apply() }

    /**
     * Which OCR engine to use.
     *
     * [ENGINE_PYTHON] is the shipped path and the default: `chrome-lens-py` through Chaquopy.
     * [ENGINE_RUST] is the native PP-OCRv6 engine, which is only exposed in debug builds while the
     * migration is unfinished. Any unknown stored value falls back to Python, so a stray preference
     * can never silently change what reads the user's covers.
     */
    var ocrEngine: String
        get() = if (sp.getString(KEY_OCR_ENGINE, ENGINE_PYTHON) == ENGINE_RUST) ENGINE_RUST else ENGINE_PYTHON
        set(value) {
            val kept = if (value == ENGINE_RUST) ENGINE_RUST else ENGINE_PYTHON
            sp.edit().putString(KEY_OCR_ENGINE, kept).apply()
        }

    /**
     * Whether the supplementary general-book sources (Open Library, Google Books) take part in a search.
     *
     * On by default, because they are genuinely useful for general books; off is a real choice, because for
     * doujin discovery they can only muddy a result list. When this is off their requests are **not issued**
     * — the toggle is not a display filter, so a switched-off provider can never appear as "searched, nothing
     * found". DLsite (the doujin anchor) is never affected.
     */
    var secondarySources: Boolean
        get() = sp.getBoolean(KEY_SECONDARY_SOURCES, true)
        set(value) { sp.edit().putBoolean(KEY_SECONDARY_SOURCES, value).apply() }

    companion object {
        /** `chrome-lens-py` through Chaquopy — the shipped engine. */
        const val ENGINE_PYTHON = "python"

        /** The native PP-OCRv6 Rust engine — development only. */
        const val ENGINE_RUST = "rust"

        private const val STORE = "bookcover_online"
        private const val KEY_ENABLED = "online_enabled"
        private const val KEY_OCR_LANGUAGE = "online_ocr_language"
        private const val KEY_TARGET_LANGUAGE = "online_target_language"
        private const val KEY_LIMIT = "online_limit"
        private const val KEY_PROVIDERS = "online_providers"
        private const val KEY_AUTO_SEARCH = "online_auto_search"
        private const val KEY_SECONDARY_SOURCES = "online_secondary_sources"
        private const val KEY_OCR_ENGINE = "online_ocr_engine"
    }
}
