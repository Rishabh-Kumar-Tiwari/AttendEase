package com.example.attendancemanagementsystem

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SplashActivity : AppCompatActivity() {

    private val tag = "SplashActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Launch warmup + timing concurrently
        lifecycleScope.launch {
            val warmup = async(Dispatchers.IO) {
                try {
                    // Try to open the model briefly to warm native libs / caches.
                    // If model missing, catch and return null.
                    val ed = try {
                        TFLiteEmbedder.createFromAssets(this@SplashActivity, "facenet.tflite")
                    } catch (e: Exception) {
                        Log.w(tag, "TFLite not found/warmup skipped: ${e.message}")
                        null
                    }

                    // Close immediately if created
                    try {
                        ed?.close()
                    } catch (_: Exception) {}

                    true
                } catch (e: Exception) {
                    Log.w(tag, "Warmup failed: ${e.message}")
                    false
                }
            }

            // Ensure splash is visible at least this long
            val minDelayMs = TimeUnit.MILLISECONDS.toMillis(1200)
            val delayJob = async { delay(minDelayMs) }

            // wait for both to complete (warmup may finish earlier)
            warmup.await()
            delayJob.await()

            // Move to MainActivity on main thread
            withContext(Dispatchers.Main) {
                try {
                    val i = Intent(this@SplashActivity, MainActivity::class.java)
                    // ensure splash isn't kept in backstack
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(i)
                    finish()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to start MainActivity: ${e.message}")
                }
            }
        }
    }
}
