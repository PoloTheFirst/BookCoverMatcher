package com.bookcovermatcher.core.search

import com.bookcovermatcher.core.excel.ImageSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

enum class MatchLevel { HIGH, MID, LOW }

/**
 * Turns a cosine similarity into the 0-100 % figure and the High/Mid/Low colour shown in the UI.
 *
 * Raw cosine values depend on the model (some embedding spaces keep unrelated images at 0.2,
 * others at 0.6), so the percentage is measured **relative to the catalogue's own baseline** -
 * the median cosine between two different images of the same workbook:
 *
 *     percent = (cos - baseline) / (1 - baseline)        clamped to 0..1
 *
 * `HIGH_PERCENT` / `MID_PERCENT` are starting points. `tools/benchmark.py` prints the genuine vs
 * impostor score distributions of your own covers so you can tune them.
 */
object ScoreScale {
    const val DEFAULT_BASELINE = 0.5f
    const val HIGH_PERCENT = 70f
    const val MID_PERCENT = 45f

    fun percent(cosine: Float, baseline: Float): Float {
        val b = baseline.coerceIn(0f, 0.95f)
        val p = (cosine - b) / (1f - b)
        return (p.coerceIn(0f, 1f)) * 100f
    }

    fun level(percent: Float): MatchLevel = when {
        percent >= HIGH_PERCENT -> MatchLevel.HIGH
        percent >= MID_PERCENT -> MatchLevel.MID
        else -> MatchLevel.LOW
    }
}

/** One workbook picture with its embedding. */
class IndexedImage(
    val cell: String,
    val row: Int,
    val col: Int,
    val source: ImageSource,
    val imageId: String,
    val thumbJpeg: ByteArray,
    val embedding: FloatArray,
)

class RankedMatch(
    val image: IndexedImage,
    val cosine: Float,
    val percent: Float,
    val level: MatchLevel,
)

/**
 * All embeddings of one workbook range plus a [FlatIpIndex] over them.
 * Port of `rankMatches` from `js/matcher.js`: best score first, ties broken by cell address.
 */
class CatalogIndex(
    val dim: Int,
    val items: List<IndexedImage>,
    val baseline: Float = estimateBaseline(items.map { it.embedding }),
) {
    private val index = FlatIpIndex(dim).also { idx -> items.forEach { idx.add(it.embedding) } }

    val size: Int get() = items.size

    fun rank(query: FloatArray, limit: Int): List<RankedMatch> {
        if (items.isEmpty() || limit <= 0) return emptyList()
        val q = FlatIpIndex.l2Normalize(query)
        val all = index.search(q, items.size)
        return all
            .sortedWith(compareByDescending<FlatIpIndex.Hit> { it.score }.thenBy { items[it.id].cell })
            .take(limit)
            .map { hit ->
                val pct = ScoreScale.percent(hit.score, baseline)
                RankedMatch(items[hit.id], hit.score, pct, ScoreScale.level(pct))
            }
    }

    companion object {
        private const val MAX_PAIRS = 50_000

        /** Median cosine between distinct catalogue images (deterministic pair sampling if huge). */
        fun estimateBaseline(vectors: List<FloatArray>): Float {
            val n = vectors.size
            if (n < 3) return ScoreScale.DEFAULT_BASELINE
            val total = n.toLong() * (n - 1) / 2
            val sims = ArrayList<Float>(minOf(total, MAX_PAIRS.toLong()).toInt())
            if (total <= MAX_PAIRS) {
                for (i in 0 until n) for (j in i + 1 until n) sims.add(dot(vectors[i], vectors[j]))
            } else {
                var state = 0x9E3779B97F4A7C15uL.toLong()
                var drawn = 0
                while (drawn < MAX_PAIRS) {
                    state = state * 6364136223846793005L + 1442695040888963407L
                    val i = ((state ushr 33) % n).toInt()
                    state = state * 6364136223846793005L + 1442695040888963407L
                    val j = ((state ushr 33) % n).toInt()
                    if (i == j) continue
                    sims.add(dot(vectors[i], vectors[j]))
                    drawn++
                }
            }
            sims.sort()
            val mid = sims.size / 2
            val median = if (sims.size % 2 == 1) sims[mid] else (sims[mid - 1] + sims[mid]) / 2f
            return median.coerceIn(0f, 0.9f)
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}

/**
 * On-disk cache of a [CatalogIndex] (the equivalent of FAISS `write_index` / `read_index`).
 * One self-contained binary file per (workbook content, range, model, extraction options).
 */
object CatalogIndexStore {
    private const val MAGIC = 0x42434D49 // "BCMI"
    private const val VERSION = 1
    private const val MAX_ITEMS = 100_000
    private const val MAX_DIM = 8192
    private const val MAX_THUMB = 4 * 1024 * 1024

    fun cacheKey(workbookSha256: String, rangeKey: String, modelFingerprint: String, includeFloating: Boolean): String {
        val raw = "v$VERSION|$workbookSha256|$rangeKey|$modelFingerprint|floating=$includeFloating"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun write(catalog: CatalogIndex, out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeInt(catalog.dim)
        d.writeInt(catalog.items.size)
        d.writeFloat(catalog.baseline)
        for (it in catalog.items) {
            d.writeUTF(it.cell)
            d.writeInt(it.row)
            d.writeInt(it.col)
            d.writeByte(it.source.ordinal)
            d.writeUTF(it.imageId)
            d.writeInt(it.thumbJpeg.size)
            d.write(it.thumbJpeg)
            for (x in it.embedding) d.writeFloat(x)
        }
        d.flush()
    }

    /** @throws IOException if the file is truncated, from another version or otherwise invalid. */
    fun read(input: InputStream): CatalogIndex {
        try {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != MAGIC) throw IOException("not an index file")
            if (d.readInt() != VERSION) throw IOException("unsupported index version")
            val dim = d.readInt()
            val count = d.readInt()
            val baseline = d.readFloat()
            if (dim !in 1..MAX_DIM || count !in 0..MAX_ITEMS) throw IOException("corrupt header")
            val sources = ImageSource.values()
            val items = ArrayList<IndexedImage>(count)
            repeat(count) {
                val cell = d.readUTF()
                val row = d.readInt()
                val col = d.readInt()
                val src = d.readUnsignedByte()
                if (src >= sources.size) throw IOException("corrupt source")
                val imageId = d.readUTF()
                val tl = d.readInt()
                if (tl !in 0..MAX_THUMB) throw IOException("corrupt thumbnail")
                val thumb = ByteArray(tl)
                d.readFully(thumb)
                val emb = FloatArray(dim) { d.readFloat() }
                items.add(IndexedImage(cell, row, col, sources[src], imageId, thumb, emb))
            }
            return CatalogIndex(dim, items, baseline)
        } catch (e: EOFException) {
            throw IOException("truncated index file", e)
        }
    }
}
