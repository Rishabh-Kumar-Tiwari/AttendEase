package com.example.attendancemanagementsystem

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.attendancemanagementsystem.databinding.ActivityEnrollBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class EnrollmentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnrollBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val detectorHelper by lazy { FaceDetectorHelper(this) }
    private var analyzer: CameraAnalyzer? = null
    private var embedder: TFLiteEmbedder? = null
    private val collectedEmbeddings = mutableListOf<FloatArray>()
    private var lastAlignedFaceBitmap: Bitmap? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.previewView)
        val overlayView = findViewById<OverlayView>(R.id.overlayView)

        binding.btnBack.setOnClickListener { finish() }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                embedder = TFLiteEmbedder.createFromAssets(this@EnrollmentActivity, "facenet.tflite")
                Log.i("EnrollmentActivity", "Embedder loaded")
            } catch (e: Exception) {
                Log.w("EnrollmentActivity", "Embedder not loaded: ${e.message}")
            }
        }

        binding.btnCapture.setOnClickListener {
            lastAlignedFaceBitmap?.let { bmp ->
                val ed = embedder
                if (ed == null) {
                    Toast.makeText(this, "Model not loaded. Put facenet.tflite into assets.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val emb = ed.getEmbedding(bmp)
                        collectedEmbeddings.add(emb)
                        runOnUiThread {
                            binding.txtInstruction.text = "Captured ${collectedEmbeddings.size} samples"
                            Toast.makeText(this@EnrollmentActivity, "Captured sample ${collectedEmbeddings.size}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@EnrollmentActivity, "Embedding failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } ?: run {
                Toast.makeText(this, "No face available to capture. Align your face in preview.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSubmit.setOnClickListener {
            val name = binding.inputName.text.toString().trim()
            val roll = binding.inputRoll.text.toString().trim()
            if (name.isEmpty() || roll.isEmpty()) {
                Toast.makeText(this, "Enter name and roll", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (collectedEmbeddings.isEmpty()) {
                Toast.makeText(this, "Capture at least one sample", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val id = "${roll}_${name.replace("\\s+".toRegex(), "_")}"
                    EmbeddingStorage.saveEnrollment(applicationContext, id, collectedEmbeddings)
                    RecognitionManager.enroll(id, collectedEmbeddings)
                    runOnUiThread {
                        Toast.makeText(this@EnrollmentActivity, "Enrolled $name ($id) successfully", Toast.LENGTH_LONG).show()
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@EnrollmentActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.previewView)
        val overlayView = findViewById<OverlayView>(R.id.overlayView)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            try {
                provider.unbindAll()
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer = CameraAnalyzer(this, detectorHelper) { alignedFaceBmp, faceRect, bmpW, bmpH ->
                    lastAlignedFaceBitmap = alignedFaceBmp
                    runOnUiThread {
                        val pvW = previewView.width
                        val pvH = previewView.height
                        overlayView.setBoundingBox(faceRect, bmpW, bmpH, pvW, pvH, true)
                    }
                }
                analysis.setAnalyzer(cameraExecutor, analyzer!!)
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera start failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.stop()
        cameraExecutor.shutdown()
        embedder?.close()
    }
}
