package com.example.attendancemanagementsystem

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face

/**
 * Analyzer that uses ML Kit face detector. For each frame it:
 *  - builds an InputImage from mediaImage + rotation
 *  - runs detector
 *  - converts Media.Image -> rotated Bitmap (same orientation as detection)
 *  - for the first detected face, creates a cropped-aligned face bitmap via FaceAligner
 *  - invokes the callback with:
 *      (alignedFaceBitmap, face.boundingBox (in bitmap coords), bitmapWidth, bitmapHeight)
 */
class CameraAnalyzer(
    private val context: Context,
    private val detectorHelper: FaceDetectorHelper,
    private val onFaceDetected: (faceBitmap: Bitmap, faceBoundingBox: android.graphics.Rect, bitmapWidth: Int, bitmapHeight: Int) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    private var active = true

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (!active) {
            imageProxy.close()
            return
        }

        val mediaImage: Image? = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        // run ML Kit detection (async)
        detectorHelper.detect(inputImage) { faces ->
            try {
                if (faces.isNotEmpty()) {
                    val face = chooseLargestFace(faces)
                    // Convert mediaImage -> rotated bitmap in the same orientation ML Kit used
                    val rotatedBitmap = detectorHelper.mediaImageToBitmap(mediaImage, rotation)
                    // Align & crop the face (returns resized square bitmap, default 160x160)
                    val aligned = FaceAligner.alignFace(rotatedBitmap, face)
                    // bounding box is relative to rotatedBitmap coordinates (face.boundingBox)
                    onFaceDetected(aligned, face.boundingBox, rotatedBitmap.width, rotatedBitmap.height)
                } else {
                    // no face: notify caller with null by not calling; caller can clear overlay
                }
            } catch (e: Exception) {
                Log.e("CameraAnalyzer", "analyze error: ${e.message}", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun chooseLargestFace(faces: List<Face>): Face {
        var best = faces[0]
        var bestArea = best.boundingBox.width() * best.boundingBox.height()
        for (f in faces) {
            val area = f.boundingBox.width() * f.boundingBox.height()
            if (area > bestArea) {
                best = f
                bestArea = area
            }
        }
        return best
    }

    fun stop() { active = false }
}
