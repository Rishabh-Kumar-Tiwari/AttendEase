package com.example.attendancemanagementsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceDetectorHelper(private val context: Context) {

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)
    }

    // Detect faces in InputImage and forward result to callback.
    fun detect(inputImage: InputImage, cb: (List<Face>) -> Unit) {
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                cb(faces)
            }
            .addOnFailureListener { e ->
                Log.w("FaceDetectorHelper", "detect failed: ${e.message}")
                cb(emptyList())
            }
    }

    fun mediaImageToBitmap(mediaImage: Image, rotationDegrees: Int): Bitmap {
        val nv21 = yuv420ToNv21(mediaImage)
        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, mediaImage.width, mediaImage.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, mediaImage.width, mediaImage.height), 100, out)
        val yuv = out.toByteArray()
        var bmp = android.graphics.BitmapFactory.decodeByteArray(yuv, 0, yuv.size)
        if (rotationDegrees != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        return bmp
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        var pos = ySize

        val u = ByteArray(uSize)
        val v = ByteArray(vSize)
        uBuffer.get(u)
        vBuffer.get(v)

        var i = 0
        while (i < uSize) {
            nv21[pos++] = v[i]
            nv21[pos++] = u[i]
            i++
        }
        return nv21
    }
}
