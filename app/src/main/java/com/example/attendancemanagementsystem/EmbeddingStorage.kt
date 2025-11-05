package com.example.attendancemanagementsystem

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Handles saving/loading of embeddings for enrolled faces.
 * Stored as JSON in app's internal filesDir.
 */
object EmbeddingStorage {
    private const val FILE_NAME = "enrollments.json"
    private val gson = Gson()

    /**
     * Save or update one student's embeddings.
     * Each embedding = FloatArray.
     */
    fun saveEnrollment(context: Context, studentId: String, embeddings: List<FloatArray>) {
        val all = loadRaw(context).toMutableMap()

        // Convert each FloatArray -> List<Double> for Gson consistency
        val listOfLists = embeddings.map { arr ->
            arr.map { it.toDouble() }
        }

        all[studentId] = listOfLists
        val json = gson.toJson(all)
        File(context.filesDir, FILE_NAME).writeText(json)
    }

    /**
     * Remove a student's enrollment (embeddings) from storage.
     * If the student id doesn't exist, this is a no-op.
     */
    fun removeEnrollment(context: Context, studentId: String) {
        val raw = loadRaw(context).toMutableMap()
        if (raw.remove(studentId) != null) {
            // write back
            val json = gson.toJson(raw)
            File(context.filesDir, FILE_NAME).writeText(json)
        }
    }

    /**
     * Load all enrollments into memory (Map of id -> list of FloatArray)
     */
    fun loadAll(context: Context): Map<String, List<FloatArray>> {
        val raw = loadRaw(context)
        val result = mutableMapOf<String, List<FloatArray>>()

        for ((id, embLists) in raw) {
            val converted = embLists.map { inner ->
                FloatArray(inner.size) { i -> inner[i].toFloat() }
            }
            result[id] = converted
        }
        return result
    }

    /**
     * Load only the specified student ids into RecognitionManager.
     *
     * This clears RecognitionManager first, then registers only the embeddings
     * for the requested ids. If an id is missing from storage it is skipped.
     */
    fun loadIntoRecognitionManager(context: Context, ids: List<String>) {
        // clear first to prevent cross-class leftover embeddings
        RecognitionManager.clear()

        if (ids.isEmpty()) return

        val raw = loadRaw(context)
        for (id in ids) {
            val embLists = raw[id] ?: continue
            val converted = embLists.map { inner ->
                FloatArray(inner.size) { i -> inner[i].toFloat() }
            }
            RecognitionManager.enroll(id, converted)
        }
    }

    /**
     * Load the raw JSON as Map<String, List<List<Double>>>
     */
    private fun loadRaw(context: Context): Map<String, List<List<Double>>> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyMap()

        return try {
            val json = file.readText()
            val type = object : TypeToken<Map<String, List<List<Double>>>>() {}.type
            gson.fromJson<Map<String, List<List<Double>>>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
