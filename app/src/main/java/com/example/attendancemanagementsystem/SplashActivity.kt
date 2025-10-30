package com.example.attendancemanagementsystem

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private val tag = "SplashActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // simple transparent/no-layout splash — keep minimal
        // setContentView(R.layout.activity_splash) // optional if you have a layout

        // Try to warm up the model and embeddings in background; use safe calls
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Only attempt to create if the model exists; wrap in try-catch
                val embedder = try {
                    TFLiteEmbedder.createFromAssets(this@SplashActivity, "facenet.tflite")
                } catch (e: Exception) {
                    Log.w(tag, "Could not load tflite at splash: ${e.message}")
                    null
                }

                // If an embedder was created, close it after warm up (we'll reopen later in MainActivity)
                embedder?.close()
            } catch (e: Exception) {
                Log.w(tag, "Splash warmup error: ${e.message}")
            }

            // show spinner for ~2 seconds (replace with actual splash UI if you want)
            delay(2000)

            // navigate to MainActivity
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    Log.w(tag, "Navigation failed: ${e.message}")
                }
            }
        }
    }
}
