// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import android.content.Context
import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.CandidateDecisionRecord
import com.bookcovermatcher.core.wishlist.MiniJson
import java.io.File

/**
 * The durable record of what the user decided about candidates, one JSON file **per wishlist profile**.
 *
 * Design notes:
 *
 *  * **Profile isolation** is by file: `candidates-<profile>.json`. A profile's rejections are that
 *    profile's business, exactly like its bookmarks.
 *  * **No schema change** to the wishlist store. Confirming an item and adding it to a wishlist stay two
 *    separate actions: a confirmation records an identity judgement, a wishlist entry records wanting it.
 *  * **A suggestion is not a decision.** Only [CandidateDecision.CONFIRMED] and [CandidateDecision.REJECTED]
 *    are written; a candidate nobody looked at has no record, so the file cannot grow with noise.
 *  * **Unreadable file → empty map**, never a crash and never a half-parse: a corrupt decision log must not
 *    stop the app from starting.
 *
 * Reads and writes are small (tens of entries) and synchronous; callers run them off the main thread, like
 * the other stores in this package.
 */
class CandidateDecisionStore(
    context: Context,
    /** Wishlist profile id; `_local` when the user has not created one. */
    profile: String = DEFAULT_PROFILE,
) {
    private val file: File = run {
        val dir = File(context.applicationContext.filesDir, "decisions").apply { mkdirs() }
        File(dir, "candidates-${sanitize(profile)}.json")
    }

    fun load(): Map<String, CandidateDecisionRecord> {
        if (!file.isFile) return emptyMap()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return emptyMap()
        }
        val parsed = try {
            MiniJson.parse(text)
        } catch (e: Exception) {
            return emptyMap()
        }
        val root = parsed as? Map<*, *> ?: return emptyMap()
        val decisions = root[KEY_DECISIONS] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, CandidateDecisionRecord>()
        for ((rawKey, rawValue) in decisions) {
            val key = rawKey as? String ?: continue
            val entry = rawValue as? Map<*, *> ?: continue
            val decision = when ((entry[KEY_DECISION] as? String)?.lowercase()) {
                "confirmed" -> CandidateDecision.CONFIRMED
                "rejected" -> CandidateDecision.REJECTED
                else -> continue // a suggestion is never stored
            }
            out[key] = CandidateDecisionRecord(
                key = key,
                decision = decision,
                decidedAtMillis = (entry[KEY_AT] as? Number)?.toLong() ?: 0L,
                title = entry[KEY_TITLE] as? String ?: "",
                provider = entry[KEY_PROVIDER] as? String ?: "",
                url = (entry[KEY_URL] as? String)?.takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    /** Writes the whole map atomically-ish (temp file then rename), so a crash cannot truncate it. */
    fun save(records: Map<String, CandidateDecisionRecord>): Boolean {
        val decisions = LinkedHashMap<String, Any?>()
        for (record in records.values) {
            if (record.decision == CandidateDecision.SUGGESTED) continue
            decisions[record.key] = mapOf(
                KEY_DECISION to record.decision.name.lowercase(),
                KEY_AT to record.decidedAtMillis,
                KEY_TITLE to record.title,
                KEY_PROVIDER to record.provider,
                KEY_URL to (record.url ?: ""),
            )
        }
        val text = MiniJson.write(mapOf(KEY_VERSION to 1, KEY_DECISIONS to decisions))
        return try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun file(): File = file

    private fun sanitize(profile: String): String {
        val clean = profile.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
        return clean.joinToString("").take(64).ifEmpty { DEFAULT_PROFILE }
    }

    companion object {
        const val DEFAULT_PROFILE = "_local"
        private const val KEY_VERSION = "version"
        private const val KEY_DECISIONS = "decisions"
        private const val KEY_DECISION = "decision"
        private const val KEY_AT = "decidedAtMillis"
        private const val KEY_TITLE = "title"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_URL = "url"
    }
}
