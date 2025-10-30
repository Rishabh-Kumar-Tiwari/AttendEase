package com.example.attendancemanagementsystem

import kotlin.math.sqrt

/**
 * Simple in-memory recognition manager that stores embeddings and performs
 * brute-force cosine-similarity matching.
 *
 * This matches the usage in MainActivity: recognize(embedding: FloatArray) -> MatchResult?
 */
data class MatchResult(val id: String, val confidence: Float)

object RecognitionManager {
    // map studentId -> list of embeddings
    private val store: MutableMap<String, MutableList<FloatArray>> = mutableMapOf()

    /** Enroll one or more embeddings for a student id */
    fun enroll(studentId: String, embeddings: List<FloatArray>) {
        val list = store.getOrPut(studentId) { mutableListOf() }
        list.addAll(embeddings)
    }

    /** Clear all enrolled data (for debug) */
    fun clear() {
        store.clear()
    }

    /**
     * Recognize an embedding by brute-force cosine similarity.
     * Returns MatchResult with best id and similarity value (0..1) or null if no match above threshold.
     */
    fun recognize(query: FloatArray, threshold: Float = 0.75f): MatchResult? {
        var bestId: String? = null
        var bestScore = -1f
        for ((id, vectors) in store) {
            for (v in vectors) {
                val sim = cosine(query, v)
                if (sim > bestScore) {
                    bestScore = sim
                    bestId = id
                }
            }
        }
        return if (bestId != null && bestScore >= threshold) MatchResult(bestId, bestScore) else null
    }

    /** cosine similarity between two float arrays */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
