package com.example.attendancemanagementsystem

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import kotlin.math.max

object FaceAligner {

    fun alignFace(source: Bitmap, face: Face, outputSize: Int = 160): Bitmap {
        val bbox: Rect = face.boundingBox

        val margin = (0.4f * max(bbox.width(), bbox.height())).toInt()
        val left = (bbox.left - margin).coerceAtLeast(0)
        val top = (bbox.top - margin).coerceAtLeast(0)
        val right = (bbox.right + margin).coerceAtMost(source.width)
        val bottom = (bbox.bottom + margin).coerceAtMost(source.height)
        val width = right - left
        val height = bottom - top
        val size = max(width, height)

        var cx = left + width / 2
        var cy = top + height / 2
        var sqLeft = (cx - size / 2).coerceAtLeast(0)
        var sqTop = (cy - size / 2).coerceAtLeast(0)
        if (sqLeft + size > source.width) sqLeft = (source.width - size).coerceAtLeast(0)
        if (sqTop + size > source.height) sqTop = (source.height - size).coerceAtLeast(0)

        val cropped = Bitmap.createBitmap(source, sqLeft, sqTop, size, size)
        val resized = Bitmap.createScaledBitmap(cropped, outputSize, outputSize, true)
        return resized
    }
}
