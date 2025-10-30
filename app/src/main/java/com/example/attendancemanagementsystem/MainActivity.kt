package com.example.attendancemanagementsystem

import android.Manifest
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.example.attendancemanagementsystem.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val tag = "MainActivity"

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: CameraAnalyzer? = null
    private var embedder: TFLiteEmbedder? = null
    private val detectorHelper by lazy { FaceDetectorHelper(this) }
    private val recognitionManager = RecognitionManager

    // Single-threaded executor specifically for inference to avoid concurrent Interpreter.run calls.
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    // smoothing + debounce
    private var autoMarkEnabled = true
    private val consecutiveNeeded = 3
    private val recognitionCounters = mutableMapOf<String, Int>()
    private val recentlyMarked = mutableMapOf<String, Long>()
    private val recognitionCooldownMs = 10_000L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showToast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // initial UI
        binding.txtStatus.text = "Status: Idle"
        binding.txtRecognizedName.text = "No one detected"
        binding.txtRecognizedId.text = ""
        binding.btnMarkPresent.isEnabled = false
        binding.txtMode.text = "Mode: Auto Mark"
        binding.switchAutoMark.isChecked = true

        // listeners
        binding.btnStart.setOnClickListener {
            if (hasCameraPermission()) startCamera() else requestCameraPermission()
        }
        binding.btnStop.setOnClickListener { stopCamera() }
        binding.btnEnroll.setOnClickListener { startActivity(Intent(this, EnrollmentActivity::class.java)) }
        binding.btnAttendance.setOnClickListener { startActivity(Intent(this, AttendanceActivity::class.java)) }
        binding.fabAttendance.setOnClickListener { startActivity(Intent(this, AttendanceActivity::class.java)) }

        binding.switchAutoMark.setOnCheckedChangeListener { _, checked ->
            autoMarkEnabled = checked
            binding.txtMode.text = if (checked) "Mode: Auto Mark" else "Mode: Manual Mark"
            recognitionCounters.clear()
            binding.btnMarkPresent.isEnabled = !checked && binding.txtRecognizedId.tag != null
        }

        binding.btnMarkPresent.setOnClickListener {
            val id = binding.txtRecognizedId.tag as? String
            if (id != null) markStudentManually(id) else showToast("No recognized student")
        }

        binding.btnUndoLast.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val removed = UndoHelper.removeLastRecord(this@MainActivity)
                runOnUiThread {
                    if (removed) {
                        showToast("Last attendance removed")
                        refreshAttendanceCount()
                    } else showToast("Nothing to undo")
                }
            }
        }

        // load enrollments + model in background
        lifecycleScope.launch(Dispatchers.IO) {
            try { EmbeddingStorage.loadIntoRecognitionManager(this@MainActivity) } catch (_: Exception) {}
            try { embedder = TFLiteEmbedder.createFromAssets(this@MainActivity, "facenet.tflite") } catch (e: Exception) {
                Log.w(tag, "Embedder load failed: ${e.message}")
            }
        }

        refreshAttendanceCount()

        // auto-start if permission already granted
        if (hasCameraPermission()) startCamera() else requestCameraPermission()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cp = ProcessCameraProvider.getInstance(this)
        cp.addListener({
            val cameraProvider = cp.get()
            try {
                cameraProvider.unbindAll()

                val previewUseCase = androidx.camera.core.Preview.Builder().build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                val selector = CameraSelector.DEFAULT_FRONT_CAMERA

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analyzer = CameraAnalyzer(this, detectorHelper) { faceBitmap: Bitmap, faceRect: Rect, bmpW: Int, bmpH: Int ->
                    // Update overlay on UI thread
                    runOnUiThread {
                        val pvW = binding.previewView.width
                        val pvH = binding.previewView.height
                        binding.overlayView.setBoundingBox(faceRect, bmpW, bmpH, pvW, pvH, true)
                    }

                    val ed = embedder
                    if (ed == null) {
                        runOnUiThread { binding.txtStatus.text = "Face detected (model not loaded)" }
                        return@CameraAnalyzer
                    }

                    // Use dedicated single-thread inference dispatcher to serialize inference calls
                    lifecycleScope.launch(inferenceDispatcher) {
                        try {
                            val emb = ed.getEmbedding(faceBitmap)
                            val match = recognitionManager.recognize(emb)
                            if (match != null) {
                                handleMatch(match.id, match.confidence)
                            } else {
                                runOnUiThread { showRecognized(null, 0f) }
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Recognition error: ${e.message}")
                            runOnUiThread { binding.txtStatus.text = "Recognition error" }
                        }
                    }
                }

                analysis.setAnalyzer(cameraExecutor, analyzer!!)
                cameraProvider.bindToLifecycle(this, selector, previewUseCase, analysis)
                runOnUiThread { binding.txtStatus.text = "Camera started" }
            } catch (e: Exception) {
                showToast("Camera start failed: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleMatch(studentId: String, confidence: Float) {
        val now = System.currentTimeMillis()
        val last = recentlyMarked[studentId] ?: 0L

        if (autoMarkEnabled) {
            if (now - last < recognitionCooldownMs) {
                runOnUiThread { binding.txtStatus.text = "Recently marked: ${displayFromId(studentId)}" }
                return
            }
            val cnt = (recognitionCounters[studentId] ?: 0) + 1
            recognitionCounters[studentId] = cnt
            runOnUiThread { showRecognized(studentId, confidence, cnt) }
            if (cnt >= consecutiveNeeded) {
                recognitionCounters[studentId] = 0
                recentlyMarked[studentId] = now
                lifecycleScope.launch(Dispatchers.IO) {
                    val (roll, name) = parseId(studentId)
                    val marked = AttendanceManager.markIfNotMarked(this@MainActivity, studentId, name, roll)
                    runOnUiThread {
                        if (marked) {
                            showToast("Marked present: $name")
                            binding.txtStatus.text = "Auto-marked: $name"
                            refreshAttendanceCount()
                        } else {
                            binding.txtStatus.text = "Already marked: $name"
                        }
                    }
                }
            }
        } else {
            runOnUiThread { showRecognized(studentId, confidence, 0) }
        }
    }

    // give consecutiveCount a default so older calls don't need to pass it
    private fun showRecognized(studentId: String?, confidence: Float, consecutiveCount: Int = 0) {
        if (studentId == null) {
            binding.txtRecognizedName.text = "No one detected"
            binding.txtRecognizedId.text = ""
            binding.txtRecognizedId.tag = null
            binding.btnMarkPresent.isEnabled = false
            binding.txtStatus.text = "Unknown"
            return
        }
        val (roll, name) = parseId(studentId)
        binding.txtRecognizedName.text = name
        binding.txtRecognizedId.text = "Roll: $roll | Conf: ${"%.2f".format(confidence)}"
        binding.txtRecognizedId.tag = studentId
        binding.btnMarkPresent.isEnabled = !autoMarkEnabled
        binding.txtStatus.text = if (consecutiveCount > 0) "Recognizing $name ($consecutiveCount/$consecutiveNeeded)" else "Detected: $name"
    }

    private fun markStudentManually(studentId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (roll, name) = parseId(studentId)
            val marked = AttendanceManager.markIfNotMarked(this@MainActivity, studentId, name, roll)
            runOnUiThread {
                if (marked) {
                    showToast("Marked present: $name")
                    binding.txtStatus.text = "Marked present: $name"
                    refreshAttendanceCount()
                } else {
                    showToast("$name already marked")
                    binding.txtStatus.text = "Already marked: $name"
                }
            }
        }
    }

    private fun parseId(studentId: String): Pair<String, String> {
        val idx = studentId.indexOf('_')
        return if (idx <= 0) Pair("", studentId) else {
            val roll = studentId.substring(0, idx)
            val name = studentId.substring(idx + 1).replace("_", " ")
            Pair(roll, name)
        }
    }

    private fun displayFromId(studentId: String): String {
        val (roll, name) = parseId(studentId)
        return if (roll.isBlank()) name else "$name ($roll)"
    }

    private fun stopCamera() {
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
            analyzer?.stop()
            binding.overlayView.clear()
            binding.txtStatus.text = "Camera stopped"
        } catch (e: Exception) {
            showToast("Stop camera error: ${e.localizedMessage}")
        }
    }

    private fun refreshAttendanceCount() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = AttendanceManager.getTodayRecords(this@MainActivity)
                runOnUiThread {
                    // Safely update if layout has this view (it will)
                    try {
                        binding.txtAttendanceCount.text = "Today's marked: ${list.size}"
                    } catch (_: Exception) {
                        binding.txtStatus.text = "Marked: ${list.size}"
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "refreshAttendanceCount error: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAttendanceCount()
        lifecycleScope.launch(Dispatchers.IO) { EmbeddingStorage.loadIntoRecognitionManager(this@MainActivity) }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        try { analyzer?.stop() } catch (_: Exception) {}
        try {
            embedder?.close()
        } catch (_: Exception) {}

        // shut down inference executor
        try {
            inferenceExecutor.shutdownNow()
        } catch (e: Exception) {
            Log.w(tag, "inferenceExecutor shutdown error: ${e.message}")
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }
}
