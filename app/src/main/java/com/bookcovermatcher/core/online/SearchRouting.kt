package com.bookcovermatcher.core.online

/**
 * Which platforms the user selected, and what that selection means for a search.
 *
 * Only **automated** platforms can be selected. Browser-only platforms (Melonbooks, Suruga-ya,
 * DoujinMori, E-Hentai) are always visible, always open their own search page, and are never part of
 * a selection: a card that cannot be searched in the app must not look like one that can.
 *
 * The rules live here, away from Compose and from the controller, so they can be tested as plain
 * Kotlin:
 *
 *  * [automated] — the selected platforms the app may query itself;
 *  * [shortcuts] — every browser-only platform, for the hand-off cards;
 *  * [hasAutomated] — whether a search can run in the app at all.
 *
 * Default: every automated provider is selected; the browser-only ones never are. The selection is
 * persisted by `OnlineSearchPrefs`.
 */
object SearchRouting {

    /** Selected on a fresh install / when the preference has never been written. */
    val DEFAULT_SELECTION: Set<String> = ProviderCatalog.SELECTABLE.map { it.id }.toSet()

    /**
     * A safe selection. `null` means "never set" → [DEFAULT_SELECTION]; unknown ids are dropped
     * (a provider removed in a later build must not linger); a browser-only id is dropped even if an
     * older build stored it (v2.2.2 made Melonbooks browser-only), so a stored selection can never
     * resurrect an in-app search for it. An empty set is kept as empty, because "the user deselected
     * everything" is a real choice that must not silently revert to defaults.
     */
    fun sanitize(ids: Collection<String>?): Set<String> {
        if (ids == null) return DEFAULT_SELECTION
        return ids.asSequence()
            .map { it.trim().lowercase() }
            .filter { ProviderCatalog.byId(it)?.selectable == true }
            .toSet()
    }

    /** Toggles [id]; an unknown or browser-only id changes nothing. */
    fun toggle(selected: Set<String>, id: String): Set<String> {
        val clean = id.trim().lowercase()
        val spec = ProviderCatalog.byId(clean) ?: return selected
        if (!spec.selectable) return selected
        return if (clean in selected) selected - clean else selected + clean
    }

    /** Selected platforms the app queries itself, in catalogue order. */
    fun automated(selected: Set<String>): List<ProviderSpec> =
        ProviderCatalog.AUTOMATED.filter { it.id in selected }

    /** Every browser-only platform: visible, openable, never searched in the app and never selected. */
    fun shortcuts(): List<ProviderSpec> = ProviderCatalog.BROWSER_ONLY

    /** True when at least one selected platform supports an in-app search. */
    fun hasAutomated(selected: Set<String>): Boolean = automated(selected).isNotEmpty()

    /** Order-independent key of a selection, used for the session cache and for change detection. */
    fun key(selected: Set<String>): String = selected.sorted().joinToString(",")
}
