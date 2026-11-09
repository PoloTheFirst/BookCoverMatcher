// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

/**
 * GitHub emoji shortcodes, expanded to the Unicode emoji GitHub shows (v2.6.0 crash-fix 1).
 *
 * The author's profile README writes `## :computer: Tech Stack`, and GitHub draws that as `💻 Tech Stack`.
 * This app drew the literal text, because a shortcode is only an emoji to a renderer that knows the mapping.
 *
 * Design rules:
 *
 *  * **Only known names are replaced.** A lookup table cannot turn `12:30:45`, `key:value` or a Windows path
 *    into a picture, so text that merely looks like a shortcode stays text.
 *  * **Only plain text is expanded.** The caller applies this to prose runs; a code span, a fenced block and a
 *    link target never pass through here, so `:computer:` inside code stays code and a URL keeps its colons.
 *  * **Unknown names survive.** `:not_a_real_emoji:` is left exactly as written rather than deleted.
 *
 * The table is deliberately a small, curated subset rather than a dependency on a multi-megabyte emoji database:
 * it covers the shortcodes the shipped README uses and the handful a GitHub profile README is normally written
 * with. Adding a library for the 13 mappings this document needs would cost far more than it explains.
 */
object EmojiShortcodes {

    /** `:name:` where `name` is a plausible shortcode: lowercase letters, digits, `_`, `+` and `-`. */
    private val PATTERN = Regex(":([a-z0-9_+-]+):")

    /**
     * The shortcodes this app renders, mapped to the emoji GitHub renders them as.
     *
     * Names are exactly as GitHub spells them (no surrounding colons). This is the whole vocabulary the renderer
     * knows; anything else is preserved verbatim.
     */
    val BY_NAME: Map<String, String> = mapOf(
        // Used by the bundled profile README.
        "computer" to "\uD83D\uDCBB", // 💻
        // The standard profile-README set (the template's own suggestions and common headings).
        "wave" to "\uD83D\uDC4B", // 👋
        "tada" to "\uD83C\uDF89", // 🎉
        "sparkles" to "\u2728", // ✨
        "fire" to "\uD83D\uDD25", // 🔥
        "rocket" to "\uD83D\uDE80", // 🚀
        "bulb" to "\uD83D\uDCA1", // 💡
        "wrench" to "\uD83D\uDD27", // 🔧
        "hammer" to "\uD83D\uDD28", // 🔨
        "gear" to "\u2699\uFE0F", // ⚙️
        "package" to "\uD83D\uDCE6", // 📦
        "books" to "\uD83D\uDCDA", // 📚
        "book" to "\uD83D\uDCD6", // 📖
        "memo" to "\uD83D\uDCDD", // 📝
        "mailbox" to "\uD83D\uDCEB", // 📫
        "email" to "\u2709\uFE0F", // ✉️
        "link" to "\uD83D\uDD17", // 🔗
        "globe_with_meridians" to "\uD83C\uDF10", // 🌐
        "zap" to "\u26A1", // ⚡
        "bug" to "\uD83D\uDC1B", // 🐛
        "seedling" to "\uD83C\uDF31", // 🌱
        "mag" to "\uD83D\uDD0D", // 🔍
        "eyes" to "\uD83D\uDC40", // 👀
        "thumbsup" to "\uD83D\uDC4D", // 👍
        "+1" to "\uD83D\uDC4D", // 👍
        "handshake" to "\uD83E\uDD1D", // 🤝
        "thinking" to "\uD83E\uDD14", // 🤔
        "question" to "\u2753", // ❓
        "speech_balloon" to "\uD83D\uDCAC", // 💬
        "star" to "\u2B50", // ⭐
        "star2" to "\uD83C\uDF1F", // 🌟
        "heart" to "\u2764\uFE0F", // ❤️
        "coffee" to "\u2615", // ☕
        "calendar" to "\uD83D\uDCC5", // 📅
        "chart_with_upwards_trend" to "\uD83D\uDCC8", // 📈
        "bar_chart" to "\uD83D\uDCCA", // 📊
        "triangular_flag_on_post" to "\uD83D\uDEA9", // 🚩
        "checkered_flag" to "\uD83C\uDFC1", // 🏁
        "white_check_mark" to "\u2705", // ✅
        "x" to "\u274C", // ❌
        "warning" to "\u26A0\uFE0F", // ⚠️
        "information_source" to "\u2139\uFE0F", // ℹ️
        "lock" to "\uD83D\uDD12", // 🔒
        "key" to "\uD83D\uDD11", // 🔑
        "test_tube" to "\uD83E\uDDEA", // 🧪
        "robot" to "\uD83E\uDD16", // 🤖
        "art" to "\uD83C\uDFA8", // 🎨
        "musical_note" to "\uD83C\uDFB5", // 🎵
        "camera" to "\uD83D\uDCF7", // 📷
        "video_game" to "\uD83C\uDFAE", // 🎮
    )

    /**
     * Replaces every recognised `:name:` in [text] with its emoji.
     *
     * Unknown names, single colons, times, and colon-delimited identifiers are returned unchanged because only
     * names present in [BY_NAME] match a replacement.
     */
    fun expand(text: String): String {
        if (text.indexOf(':') < 0) return text
        if (text.indexOf(':', text.indexOf(':') + 1) < 0) return text
        return PATTERN.replace(text) { match ->
            BY_NAME[match.groupValues[1].lowercase()] ?: match.value
        }
    }
}
