package com.example.attendancemanagementsystem

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object Utils {
    fun bitmapToBase64(bmp: Bitmap, compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 80): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(compressFormat, quality, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
