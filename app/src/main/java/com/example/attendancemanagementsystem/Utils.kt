package com.example.attendancemanagementsystem

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Utility helpers for the project.
 * NOTE: OverlayView has its own file (OverlayView.kt). Keep Utils focused on helpers.
 */

object Utils {
    /**
     * Convert a Bitmap to Base64 string (useful for sending small images to server).
     */
    fun bitmapToBase64(bmp: Bitmap, compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 80): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(compressFormat, quality, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
