package com.example.attendancemanagementsystem

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private var box: RectF? = null

    fun setBoundingBox(faceRect: Rect, bmpWidth: Int, bmpHeight: Int, pvWidth: Int, pvHeight: Int, front: Boolean) {
        val scaleX = pvWidth.toFloat() / bmpWidth
        val scaleY = pvHeight.toFloat() / bmpHeight
        val left = faceRect.left * scaleX
        val top = faceRect.top * scaleY
        val right = faceRect.right * scaleX
        val bottom = faceRect.bottom * scaleY
        box = RectF(left, top, right, bottom)
        invalidate()
    }

    fun clear() {
        box = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        box?.let { canvas.drawRect(it, paint) }
    }
}
