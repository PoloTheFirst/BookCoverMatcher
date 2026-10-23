package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.wishlist.ItemIdentity
import java.net.URLEncoder

/**
 * Reads a **Melonbooks** search page (`/search/search.php?mode=search&name=…`) into
 * [ProductSearchResult]s.
 *
 * Why this provider: it is one of the two shops the doujinshi workflow was built for. Its search page
 * is ordinary HTML served to the app's own user agent (measured 2026-10-23: HTTP 200, UTF-8, 100
 * products), and `robots.txt` answers **404**, which RFC 9309 defines as "no restrictions published".
 * The adapter therefore makes **one** request per user-initiated search, reads the first page only,
 * never paginates and never retries.
 *
 * Fields read, and only what the page prints: product id, title, circle, author, price and the
 * thumbnail. There is no stock count or description in the listing markup, so none is invented.
 *
 * Plain Kotlin/JVM: unit-tested against a captured page in `app/src/test/resources/fixtures`.
 */
object MelonbooksResponseParser {

    const val SOURCE_NAME = "Melonbooks"
    const val PROVIDER_ID = "mb"
    const val SITE = "https://www.melonbooks.co.jp"

    private const val MAX_ITEM_CHARS = 30_000
    private const val MAX_TITLE = 300
    private const val MAX_PERSON = 120

    /** Present on both a page with hits and a page with none. */
    private val RESULTS_PAGE = Regex("class=\"item-list\"")

    /** `<li class="product_3897050">` */
    private val ITEM_START = Regex("<li\\s+class=\"product_([0-9]{3,12})\"")

    private val TITLE_BLOCK = Regex("class=\"item-ttl product_title\"[^>]*>([\\s\\S]{0,400}?)</p>")
    private val ANCHOR_TITLE = Regex("<a\\b[^>]*\\btitle=\"([^\"]*)\"")
    private val DETAIL_HREF = Regex("href=\"(/detail/detail\\.php\\?product_id=([0-9]{3,12}))\"")
    private val PRICE = Regex("class=\"item-price\"[^>]*>([^<]{1,24})</p>")
    private val PERSON_BLOCK = Regex("<p\\s+class=\"search-item-author-author[^\"]*\"[^>]*>([\\s\\S]{0,700}?)</p>")
    private val PERSON_ANCHOR = Regex("<a\\b[^>]*>([\\s\\S]{0,200}?)</a>")
    private val COVER = Regex("data-src=\"(//[^\"]*?resize_image\\.php[^\"]*?)\"")

    class ParseException(message: String) : RuntimeException(message)

    /** The public search URL for [query]: UTF-8, `%20` for spaces, on the shop's own host. */
    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return "$SITE/search/search.php?mode=search&name=$encoded"
    }

    /**
     * @return at most [limit] listings in the order the shop shows them. An empty list means the shop
     *   really had no hit; a body that is not a search page throws [ParseException].
     */
    fun parse(html: String, limit: Int): List<ProductSearchResult> {
        if (limit <= 0) return emptyList()
        if (!RESULTS_PAGE.containsMatchIn(html)) {
            throw ParseException("The reply is not a Melonbooks search result page.")
        }
        val starts = ITEM_START.findAll(html).toList()
        if (starts.isEmpty()) return emptyList()

        val out = ArrayList<ProductSearchResult>(minOf(limit, starts.size))
        for ((index, start) in starts.withIndex()) {
            if (out.size >= limit) break
            val id = start.groupValues[1]
            val from = start.range.first
            val to = starts.getOrNull(index + 1)?.range?.first ?: minOf(html.length, from + MAX_ITEM_CHARS)
            parseItem(id, html.substring(from, to))?.let(out::add)
        }
        return out
    }

    private fun parseItem(id: String, block: String): ProductSearchResult? {
        val detail = DETAIL_HREF.find(block)?.takeIf { it.groupValues[2] == id }?.groupValues?.get(1)
            ?: return null
        val detailUrl = SITE + detail
        if (!isDetailUrl(detailUrl)) return null

        val title = HtmlText.clean(TITLE_BLOCK.find(block)?.groupValues?.get(1) ?: "")
            .ifEmpty { HtmlText.clean(ANCHOR_TITLE.find(block)?.groupValues?.get(1) ?: "") }
            .take(MAX_TITLE)
        if (title.isEmpty()) return null

        // Order on the page: circle, author/artist, category, tags. Only the first two are people.
        val people = PERSON_BLOCK.findAll(block).map { personOf(it.groupValues[1]) }.filterNotNull().toList()

        return ProductSearchResult(
            id = id,
            title = title,
            authors = people.distinct().take(2),
            publishedYear = null,
            publishers = emptyList(),
            pageCount = null,
            languages = emptyList(),
            coverUrl = coverOf(block),
            detailUrl = detailUrl,
            source = SOURCE_NAME,
            providerId = PROVIDER_ID,
            identifier = id,
            priceText = priceOf(block),
            isMarketplace = true,
        )
    }

    private fun personOf(block: String): String? =
        PERSON_ANCHOR.find(block)?.groupValues?.get(1)
            ?.let { HtmlText.clean(it) }
            ?.take(MAX_PERSON)
            ?.takeIf { it.isNotEmpty() }

    /** Only the shop's own product pages, only over HTTPS. */
    private fun isDetailUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = ItemIdentity.domainOf(url) ?: return false
        if (host != "melonbooks.co.jp" && !host.endsWith(".melonbooks.co.jp")) return false
        return "/detail/detail.php" in url && "product_id=" in url && ".." !in url
    }

    /** The real thumbnail (`data-src`), never the `now_printing.jpeg` placeholder in `src`. */
    private fun coverOf(block: String): String? {
        val raw = COVER.find(block)?.groupValues?.get(1) ?: return null
        val decoded = HtmlText.unescape(raw)
        if ("now_printing" in decoded) return null
        return "https:$decoded"
    }

    /** `¥484` — the shop's own price line, or null when it prints none. */
    private fun priceOf(block: String): String? =
        PRICE.find(block)?.groupValues?.get(1)?.let { HtmlText.clean(it) }?.takeIf { it.isNotEmpty() }
}
