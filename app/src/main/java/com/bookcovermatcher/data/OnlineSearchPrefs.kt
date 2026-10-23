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

    private companion object {
        const val STORE = "bookcover_online"
        const val KEY_ENABLED = "online_enabled"
        const val KEY_OCR_LANGUAGE = "online_ocr_language"
        const val KEY_TARGET_LANGUAGE = "online_target_language"
        const val KEY_LIMIT = "online_limit"
        const val KEY_PROVIDERS = "online_providers"
        const val KEY_AUTO_SEARCH = "online_auto_search"
    }
}
