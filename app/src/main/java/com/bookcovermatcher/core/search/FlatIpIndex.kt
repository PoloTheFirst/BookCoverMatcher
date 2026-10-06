package com.bookcovermatcher.core.search

import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * Exact (brute-force) inner-product index with the semantics of FAISS `IndexFlatIP`:
 * vectors are stored as given, `search` returns the `k` highest inner products, best first.
 *
 * Embeddings are L2-normalised before they are added, so inner product == cosine similarity.
 *
 * The app does **not** link libfaiss: FAISS has no official Android build, and a flat index over
 * a few hundred vectors is a single matrix-vector product anyway. `tools/benchmark.py` runs the
 * same search through real FAISS and checks the rankings agree. If a catalogue ever grows to
 * 10^5+ vectors, swap this class for an HNSW/IVF implementation behind the same two methods.
 */
class FlatIpIndex(val dim: Int) {
    private var data = FloatArray(0)

    var size: Int = 0
        private set

    class Hit(val id: Int, val score: Float)

    init {
        require(dim > 0) { "dim must be positive" }
    }

    fun add(vector: FloatArray) {
        require(vector.size == dim) { "expected $dim dims, got ${vector.size}" }
        val need = (size + 1) * dim
        if (need > data.size) data = data.copyOf(maxOf(need, data.size * 2, dim * 16))
        System.arraycopy(vector, 0, data, size * dim, dim)
        size++
    }

    fun vector(id: Int): FloatArray = data.copyOfRange(id * dim, (id + 1) * dim)

    fun dot(id: Int, other: FloatArray): Float {
        val base = id * dim
        var s = 0f
        for (i in 0 until dim) s += data[base + i] * other[i]
        return s
    }

    /** Top-[k] hits by inner product (ties broken by lower id). */
    fun search(query: FloatArray, k: Int): List<Hit> {
        require(query.size == dim) { "expected $dim dims, got ${query.size}" }
        if (k <= 0 || size == 0) return emptyList()
        val limit = minOf(k, size)

        // min-heap on (score, -id): the head is the current worst of the kept hits
        val worstFirst = Comparator<Hit> { a, b ->
            val c = a.score.compareTo(b.score)
            if (c != 0) c else b.id.compareTo(a.id)
        }
        val heap = PriorityQueue(limit + 1, worstFirst)
        for (id in 0 until size) {
            val hit = Hit(id, dot(id, query))
            if (heap.size < limit) heap.add(hit)
            else if (worstFirst.compare(hit, heap.peek()!!) > 0) { heap.poll(); heap.add(hit) }
        }
        return heap.toList().sortedWith { a, b ->
            val c = b.score.compareTo(a.score)
            if (c != 0) c else a.id.compareTo(b.id)
        }
    }

    companion object {
        fun l2Normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s)
            if (n < 1e-12) return v.copyOf()
            val inv = (1.0 / n).toFloat()
            return FloatArray(v.size) { v[it] * inv }
        }
    }
}
