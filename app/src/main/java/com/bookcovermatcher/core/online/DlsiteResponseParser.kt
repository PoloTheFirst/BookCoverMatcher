package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.wishlist.ItemIdentity

/**
 * Reads a **DLsite** books search page (`https://www.dlsite.com/books/fsr/=/keyword/…`) into
 * [ProductSearchResult]s.
 *
 * Why this provider: DLsite is where a large part of the Japanese doujinshi / digital-works
 * catalogue lives, which is exactly the use case the workbook workflow is built for. Its public
 * keyword search is a plain HTML page served to non-browser clients, so it can be read without
 * credentials, cookies or an API key — verified live (the page answers the app's own user agent).
 *
 * What is read, and only what the page really contains: the work id (`RJ…`), the title, the circle
 * name and the shop price. There is **no** stock status, description or rating in the listing
 * markup, so those fields stay null and the card shows `—` rather than an invented value.
 *
 * Access constraints: `robots.txt` asks crawlers for a 10-second delay and disallows the paged
 * result URLs. The app therefore issues **one** request per user-initiated search, never paginates
 * and never retries on its own.
 *
 * Plain Kotlin/JVM: unit-tested against a captured page (`app/src/test/resources/fixtures`).
 */
object DlsiteResponseParser {

    const val SOURCE_NAME = "DLsite"
    const val PROVIDER_ID = "dl"

    const val SITE = "https://www.dlsite.com"

    /** The section the app searches: DLsite's books / doujinshi catalogue. */
    const val SECTION = "books"

    /** A work id: two letters and digits, e.g. `RJ01730150`. */
    private val WORK_ID = Regex("^[A-Z]{2}\\d{4,12}$")

    private const val MAX_TITLE = 300
    private const val MAX_ITEM_CHARS = 24_000
    private const val MAX_MAKER_CHARS = 120

    /**
     * Either marker proves the body really is a search-results page. Without one, an empty parse
     * would be indistinguishable from an age gate, a block page or a JS-only shell, and reporting
     * "no results" for those would be a lie.
     */
    private val RESULTS_PAGE = Regex("id=\"search_result_(?:list|img_box)\"")

    /** Start of one item: `<li … data-list_item_product_id="RJ01730150" …>`. */
    private val ITEM_START = Regex("<li\\b[^>]*data-list_item_product_id=\"([A-Za-z]{2}[0-9]{4,12})\"")

    private val NAME_BLOCK = Regex("<dd\\s+class=\"work_name\"[^>]*>([\\s\\S]{0,1200}?)</dd>")
    private val ANCHOR = Regex("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
    private val TITLE_ATTR = Regex("\\btitle=\"([^\"]*)\"")
    private val MAKER_BLOCK = Regex("<dd\\s+class=\"maker_name\"[^>]*>([\\s\\S]{0,600}?)</dd>")
    private val PRICE_BASE = Regex("<span\\s+class=\"work_price_base\"[^>]*>\\s*([0-9][0-9,]*)\\s*</span>")
    private val PRICE_SUFFIX = Regex("<span\\s+class=\"work_price_suffix\"[^>]*>([^<]{0,12})</span>")

    /** The listing carries a 240×240 JPEG of the real cover; the full-size one is the fallback. */
    private val COVER_THUMB = Regex("(?:https?:)?//img\\.dlsite\\.jp/[^\"'\\s]+?_img_main_240x240\\.jpg")
    private val COVER_FULL = Regex("(?:https?:)?//img\\.dlsite\\.jp/[^\"'\\s]+?_img_main\\.jpg")

    class ParseException(message: String) : RuntimeException(message)

    /**
     * The public search URL for [query].
     *
     * The keyword is a **path segment** on DLsite, and the site only accepts its own form encoding
     * there: a space must be `+`. Measured 2026-10-23 with the app's user agent —
     * `keyword/Blue+Archive` → 200 with 30 works, while `keyword/Blue%20Archive` → **403**. This used
     * to translate spaces to `%20`, which is why any multi-word query failed.
     *
     * Characters that are not safe in a path are still percent-encoded by the encoder (`+` → `%2B`,
     * `&` → `%26`, `#` → `%23`), so the `+` produced here only ever means "space".
     */
    fun searchUrl(query: String): String {
        val encoded = ProviderLinks.encode(query.trim(), QueryForm.PLUS)
        return "$SITE/$SECTION/fsr/=/keyword/$encoded"
    }

    /**
     * @return at most [limit] results in the order the shop lists them. An empty list means the shop
     *   genuinely had no hit; a body that is not a result page throws [ParseException].
     */
    fun parse(html: String, limit: Int): List<ProductSearchResult> {
        if (limit <= 0) return emptyList()
        if (!RESULTS_PAGE.containsMatchIn(html)) {
            throw ParseException("The reply is not a DLsite search result page.")
        }
        val starts = ITEM_START.findAll(html).toList()
        if (starts.isEmpty()) return emptyList()

        val out = ArrayList<ProductSearchResult>(minOf(limit, starts.size))
        for ((index, start) in starts.withIndex()) {
            if (out.size >= limit) break
            val id = start.groupValues[1].uppercase()
            if (!WORK_ID.matches(id)) continue
            val from = start.range.first
            val to = starts.getOrNull(index + 1)?.range?.first ?: minOf(html.length, from + MAX_ITEM_CHARS)
            parseItem(id, html.substring(from, to))?.let(out::add)
        }
        return out
    }

    /** Null for a block without a usable work link, title or id — it cannot be shown honestly. */
    private fun parseItem(id: String, block: String): ProductSearchResult? {
        val nameBlock = NAME_BLOCK.find(block)?.groupValues?.get(1) ?: return null
        val anchor = ANCHOR.find(nameBlock) ?: return null
        val href = HtmlText.unescape(anchor.groupValues[1]).trim()
        if (!isDetailUrl(href)) return null

        val title = HtmlText.clean(TITLE_ATTR.find(anchor.value)?.groupValues?.get(1) ?: anchor.groupValues[2])
            .take(MAX_TITLE)
        if (title.isEmpty()) return null

        val maker = MAKER_BLOCK.find(block)?.groupValues?.get(1)
            ?.let { inner -> ANCHOR.find(inner)?.groupValues?.get(2) ?: inner }
            ?.let { HtmlText.clean(it) }
            ?.take(MAX_MAKER_CHARS)
            ?.takeIf { it.isNotEmpty() }

        return ProductSearchResult(
            id = id,
            title = title,
            // The circle is its own field: a shop listing has no author, so authors stays empty.
            authors = emptyList(),
            circle = maker,
            publishedYear = null,
            // The listing has no publisher field; the circle is not a publisher, so it is not copied.
            publishers = emptyList(),
            pageCount = null,
            languages = emptyList(),
            coverUrl = coverOf(block),
            detailUrl = href,
            source = SOURCE_NAME,
            providerId = PROVIDER_ID,
            identifier = id,
            priceText = priceOf(block),
            isMarketplace = true,
        )
    }

    /** Only the shop's own product pages, only over HTTPS, only with a work id in the path. */
    private fun isDetailUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = ItemIdentity.domainOf(url) ?: return false
        if (host != "dlsite.com" && !host.endsWith(".dlsite.com")) return false
        return "/product_id/" in url && url.contains(".html") && ".." !in url
    }

    private fun coverOf(block: String): String? {
        val raw = (COVER_THUMB.find(block) ?: COVER_FULL.find(block))?.value ?: return null
        val url = if (raw.startsWith("//")) "https:$raw" else raw
        return url.takeIf { it.startsWith("https://img.dlsite.jp/") }
    }

    /** `924円` — the page's own digits and its own currency suffix, or null when it shows none. */
    private fun priceOf(block: String): String? {
        val amount = PRICE_BASE.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val suffix = PRICE_SUFFIX.find(block)?.groupValues?.get(1)?.let { HtmlText.clean(it) }.orEmpty()
        return amount + suffix
    }
}
