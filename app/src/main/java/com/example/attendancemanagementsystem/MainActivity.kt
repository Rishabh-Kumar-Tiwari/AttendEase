package com.example.attendancemanagementsystem

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    private var analyzer: CameraAnalyzer? = null
    private var embedder: TFLiteEmbedder? = null
    private val detectorHelper by lazy { FaceDetectorHelper(this) }
    private val recognitionManager = RecognitionManager

    private var autoMarkEnabled = true
    private val consecutiveNeeded = 3
    private val recognitionCounters = mutableMapOf<String, Int>()
    private val recentlyMarked = mutableMapOf<String, Long>()
    private val recognitionCooldownMs = 10_000L

    private var selectedClassId: String = ""
    private var selectedClassName: String = ""

    // camera lens state
    private var lensSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var isFrontFacing: Boolean = true

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showToast("Camera permission is required")
        }

    private val selectClassLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                selectedClassId = result.data!!.getStringExtra("classId") ?: ""
                selectedClassName = result.data!!.getStringExtra("className") ?: ""
                if (selectedClassId.isNotBlank()) {
                    showToast("Selected class: $selectedClassName")
                    loadEmbeddingsForSelectedClass()
                    if (hasCameraPermission()) startCamera() else requestCameraPermission()
                } else {
                    showToast("No class selected")
                }
            } else if (selectedClassId.isBlank()) {
                showToast("Please select a class to start")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        InsetsUtil.applyEdgeToEdge(
            window = window,
            root = binding.root,
            toolbar = binding.topAppBar,
            contentContainer = binding.contentRoot,
            navAnchoredView = binding.contentRoot
        )
        setSupportActionBar(binding.topAppBar)

        initUI()
        loadModelAsync()
        promptSelectClassAlways()
    }

    private fun initUI() = with(binding) {
        txtStatus.text = "Status: Idle"
        txtRecognizedName.text = "No one detected"
        txtRecognizedId.text = ""
        btnMarkPresent.isEnabled = false
        txtMode.text = "Mode: Auto Mark"
        switchAutoMark.isChecked = true

        btnStart.setOnClickListener {
            if (selectedClassId.isNotBlank()) {
                if (hasCameraPermission()) startCamera() else requestCameraPermission()
            } else promptSelectClassAlways()
        }

        btnStop.setOnClickListener { stopCamera() }

        btnEnroll.setOnClickListener {
            val i = Intent(this@MainActivity, EnrollmentActivity::class.java)
            if (selectedClassId.isNotBlank()) i.putExtra("classId", selectedClassId)
            startActivity(i)
        }

        fabAttendance.setOnClickListener {
            val i = Intent(this@MainActivity, AttendanceActivity::class.java)
            if (selectedClassId.isNotBlank()) i.putExtra("classId", selectedClassId)
            startActivity(i)
        }

        // Camera flip: toggle lens and restart camera if already running
        btnSwitchCamera.setOnClickListener {
            toggleCameraLens()
        }

        switchAutoMark.setOnCheckedChangeListener { _, checked ->
            autoMarkEnabled = checked
            txtMode.text = if (checked) "Mode: Auto Mark" else "Mode: Manual Mark"
            recognitionCounters.clear()
            btnMarkPresent.isEnabled = !checked && txtRecognizedId.tag != null
        }

        btnMarkPresent.setOnClickListener {
            val id = txtRecognizedId.tag as? String
            if (id != null) markStudentManually(id) else showToast("No recognized student")
        }

        refreshAttendanceCount()
    }

    private fun toggleCameraLens() {
        isFrontFacing = !isFrontFacing
        lensSelector = if (isFrontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        try {
            val cp = ProcessCameraProvider.getInstance(this)
            cp.addListener({
                val cameraProvider = cp.get()
                try {
                    cameraProvider.unbindAll()
                    startCamera()
                } catch (e: Exception) {
                    Log.w(tag, "toggleCameraLens rebind failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.w(tag, "toggleCameraLens failed: ${e.message}")
        }
    }

    private fun promptSelectClassAlways() {
        val i = Intent(this, ClassSelectionActivity::class.java)
        selectClassLauncher.launch(i)
    }

    private fun loadModelAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                embedder = TFLiteEmbedder.createFromAssets(this@MainActivity, "facenet.tflite")
            } catch (e: Exception) {
                Log.w(tag, "Embedder load failed: ${e.message}")
            }
        }
    }

    private fun loadEmbeddingsForSelectedClass() {
        if (selectedClassId.isBlank()) {
            RecognitionManager.clear()
            return
        }
        val room = ClassStorage.getClass(this, selectedClassId)
        val ids = room?.studentIds ?: emptyList()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                EmbeddingStorage.loadIntoRecognitionManager(this@MainActivity, ids)
            } catch (e: Exception) {
                Log.w(tag, "Failed to load embeddings: ${e.message}")
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (selectedClassId.isBlank()) {
            showToast("Select a class first")
            return
        }

        val cp = ProcessCameraProvider.getInstance(this)
        cp.addListener({
            val cameraProvider = cp.get()
            try {
                cameraProvider.unbindAll()
                val preview = androidx.camera.core.Preview.Builder().build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                val selector = lensSelector
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analyzer?.stop()
                analyzer = CameraAnalyzer(detectorHelper) { faceBitmap, faceRect, bmpW, bmpH ->
                    runOnUiThread {
                        val pvW = binding.previewView.width
                        val pvH = binding.previewView.height
                        binding.overlayView.setBoundingBox(faceRect, bmpW, bmpH, pvW, pvH, isFrontFacing)
                    }

                    val ed = embedder ?: return@CameraAnalyzer
                    lifecycleScope.launch(inferenceDispatcher) {
                        try {
                            val emb = ed.getEmbedding(faceBitmap)
                            val match = recognitionManager.recognize(emb)
                            if (match != null) handleMatch(match.id, match.confidence)
                            else runOnUiThread { showRecognized(null, 0f) }
                        } catch (e: Exception) {
                            Log.w(tag, "Recognition error: ${e.message}")
                            runOnUiThread { binding.txtStatus.text = "Recognition error" }
                        }
                    }
                }

                analysis.setAnalyzer(cameraExecutor, analyzer!!)
                cameraProvider.bindToLifecycle(this, selector, preview, analysis)
                runOnUiThread { binding.txtStatus.text = "Camera started (${if (isFrontFacing) "Front" else "Back"}) for $selectedClassName" }

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
                    val marked = ClassAttendanceManager.markIfNotMarked(
                        this@MainActivity, selectedClassId, studentId, name, roll
                    )
                    runOnUiThread {
                        if (marked) {
                            showToast("Marked present: $name")
                            binding.txtStatus.text = "Auto-marked: $name"
                            refreshAttendanceCount()
                        } else binding.txtStatus.text = "Already marked: $name"
                    }
                }
            }
        } else runOnUiThread { showRecognized(studentId, confidence) }
    }

    private fun showRecognized(studentId: String?, confidence: Float, count: Int = 0) {
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
        binding.txtStatus.text =
            if (count > 0) "Recognizing $name ($count/$consecutiveNeeded)" else "Detected: $name"
    }

    private fun markStudentManually(studentId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (roll, name) = parseId(studentId)
            val marked = ClassAttendanceManager.markIfNotMarked(
                this@MainActivity, selectedClassId, studentId, name, roll
            )
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
        return if (idx <= 0) Pair("", studentId)
        else Pair(studentId.substring(0, idx), studentId.substring(idx + 1).replace("_", " "))
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
                val list = ClassAttendanceManager.getRecordsForDate(
                    this@MainActivity, java.util.Date(), selectedClassId
                )
                runOnUiThread {
                    binding.txtAttendanceCount.text = "Today's marked: ${list.size}"
                }
            } catch (e: Exception) {
                Log.w(tag, "refreshAttendanceCount error: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAttendanceCount()
        loadEmbeddingsForSelectedClass()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        try { analyzer?.stop() } catch (_: Exception) {}
        try { embedder?.close() } catch (_: Exception) {}
        try { inferenceExecutor.shutdownNow() } catch (e: Exception) {
            Log.w(tag, "inferenceExecutor shutdown error: ${e.message}")
        }
    }

    @Deprecated("This method has been deprecated in favor of using the\n      {@link OnBackPressedDispatcher} via {@link #getOnBackPressedDispatcher()}.\n      The OnBackPressedDispatcher controls how back button events are dispatched\n      to one or more {@link OnBackPressedCallback} objects.")
    override fun onBackPressed() {
        val intent = Intent(this, ClassSelectionActivity::class.java)
        selectClassLauncher.launch(intent)
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
