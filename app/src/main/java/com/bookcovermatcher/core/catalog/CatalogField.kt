package com.bookcovermatcher.core.catalog

/**
 * The canonical fields a workbook column can be mapped to.
 *
 * These are the fields the app already stores for a saved item, so a mapped workbook record and a
 * bookmark describe the same things. [CIRCLE] and [ARTIST] are deliberately two fields: a shop's circle
 * is not an artist, and nothing in this package merges them.
 *
 * [aliases] are *presentation hints* only: they let a workbook work with no configuration when its own
 * headers look like these. They are never used to guess a value, and a profile can map any header to any
 * field.
 */
enum class CatalogField(val label: String, val required: Boolean, val aliases: List<String>) {

    TITLE("Title", true, listOf("タイトル", "書名", "ブック名", "作品名", "title", "book name")),

    CIRCLE("Circle", false, listOf("サークル", "社团", "社團", "circle")),

    ARTIST("Artist", false, listOf("作者", "著者", "artist", "author", "writer")),

    IDENTIFIER("Product ID", false, listOf("product id", "identifier", "品番", "作品番号", "型番", "sku", "id")),

    URL("URL", false, listOf("url", "リンク", "link", "商品url")),

    PRICE("Price", false, listOf("価格", "値段", "price", "金額")),

    DESCRIPTION("Description", false, listOf("説明", "備考", "description", "コメント")),

    PUBLISHED("Publication date", false, listOf("発行", "発売", "出版", "日付", "年月", "date", "year", "release")),

    TAGS("Tags", false, listOf("タグ", "ジャンル", "分類", "tags", "genre", "类型", "類型")),
    ;

    companion object {
        /** Every field in a stable order, which is the order the mapping screen lists them in. */
        val ordered: List<CatalogField> get() = entries

        /** The fields whose absence is a finding: a record without a title cannot be identified. */
        val requiredFields: List<CatalogField> get() = entries.filter { it.required }
    }
}

/**
 * How one canonical field of one record was established.
 *
 * The four states are kept apart on purpose:
 *  * [MAPPED] — the profile maps the field and the cell holds a value.
 *  * [MISSING] — the profile maps the field but the cell is blank. That is a real blank, not a zero.
 *  * [UNMAPPED] — the profile does not map the field (or its column is gone). Nothing is claimed.
 *  * [AMBIGUOUS] — the mapped header appears in more than one column, so the app refuses to pick one.
 */
enum class FieldState { MAPPED, MISSING, UNMAPPED, AMBIGUOUS }

/** One field value of one record, as the mapping established it. */
data class FieldValue(
    val field: CatalogField,
    val state: FieldState,
    /** The cell's text when [state] is MAPPED; null otherwise. Never a placeholder. */
    val text: String? = null,
    /** Header names when [state] is AMBIGUOUS; empty otherwise. */
    val candidates: List<String> = emptyList(),
) {
    val isMapped: Boolean get() = state == FieldState.MAPPED
}
