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
     * Each embedding = FloatArray (e.g. 128-dim from FaceNet).
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

    /**
     * Load all enrollments and push into RecognitionManager.
     */
    fun loadIntoRecognitionManager(context: Context) {
        val all = loadAll(context)
        for ((id, embeddings) in all) {
            RecognitionManager.enroll(id, embeddings)
        }
    }
}
