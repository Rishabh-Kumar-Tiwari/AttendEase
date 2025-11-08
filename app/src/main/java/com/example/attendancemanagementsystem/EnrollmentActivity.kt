package com.example.attendancemanagementsystem

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
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

    private var classId: String = ""
    private var editingStudentId: String = ""

    // camera lens state for enrollment
    private var lensSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var isFrontFacing: Boolean = true

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val scrollView: View = binding.scrollEnroll

        InsetsUtil.applyEdgeToEdge(
            window = window,
            root = binding.root,
            toolbar = binding.toolbarEnroll,
            contentContainer = scrollView,
            navAnchoredView = scrollView
        )

        setSupportActionBar(binding.toolbarEnroll)

        classId = intent.getStringExtra("classId") ?: ""
        editingStudentId = intent.getStringExtra("studentId") ?: ""

        binding.btnBack.setOnClickListener { finish() }

        // load model
        CoroutineScope(Dispatchers.IO).launch {
            try {
                embedder = TFLiteEmbedder.createFromAssets(this@EnrollmentActivity, "facenet.tflite")
                Log.i("EnrollmentActivity", "Embedder loaded")
            } catch (e: Exception) {
                Log.w("EnrollmentActivity", "Embedder not loaded: ${e.message}")
            }
        }

        if (editingStudentId.isNotBlank()) {
            val s = StudentStorage.getStudent(this, editingStudentId)
            if (s != null) {
                binding.inputName.setText(s.name)
                binding.inputRoll.setText(s.roll)
                binding.txtInstruction.text = "Editing student: ${s.name}"
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

        // flip camera button
        binding.btnSwitchCameraEnroll.setOnClickListener {
            isFrontFacing = !isFrontFacing
            lensSelector = if (isFrontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                val cp = ProcessCameraProvider.getInstance(this)
                cp.addListener({
                    val provider = cp.get()
                    try {
                        provider.unbindAll()
                        startCamera()
                    } catch (e: Exception) {
                        Log.w("EnrollmentActivity", "rebind after flip failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                Log.w("EnrollmentActivity", "flip camera failed: ${e.message}")
            }
        }

        binding.btnSubmit.setOnClickListener {
            val name = binding.inputName.text.toString().trim()
            val roll = binding.inputRoll.text.toString().trim()
            if (name.isEmpty() || roll.isEmpty()) {
                Toast.makeText(this, "Enter name and roll", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val id = if (editingStudentId.isNotBlank()) editingStudentId else "${roll}_${name.replace("\\s+".toRegex(), "_")}"

                    val student = Student(id = id, roll = roll, name = name, classId = classId)
                    StudentStorage.createOrUpdate(applicationContext, student)

                    if (collectedEmbeddings.isNotEmpty()) {
                        EmbeddingStorage.saveEnrollment(applicationContext, id, collectedEmbeddings)
                        RecognitionManager.enroll(id, collectedEmbeddings)
                    }

                    if (classId.isNotBlank()) {
                        ClassStorage.addStudentToClass(applicationContext, classId, id)

                        try {
                            AttendanceStorage.ensureMasterCsvHasRoster(applicationContext, classId)
                        } catch (e: Exception) {
                            Log.w("EnrollmentActivity", "ensureMasterCsvHasRoster failed: ${e.message}")
                        }
                    }

                    runOnUiThread {
                        Toast.makeText(this@EnrollmentActivity, "Saved $name ($id) successfully", Toast.LENGTH_LONG).show()
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
                val selector = lensSelector
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer?.stop()
                analyzer = CameraAnalyzer(detectorHelper) { alignedFaceBmp, faceRect, bmpW, bmpH ->
                    lastAlignedFaceBitmap = alignedFaceBmp
                    runOnUiThread {
                        val pvW = previewView.width
                        val pvH = previewView.height
                        overlayView.setBoundingBox(faceRect, bmpW, bmpH, pvW, pvH, isFrontFacing)
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
