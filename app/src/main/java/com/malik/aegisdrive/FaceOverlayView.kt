package com.malik.aegisdrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var leftEye: List<NormalizedLandmark>? = null
    private var rightEye: List<NormalizedLandmark>? = null
    private var lips: List<NormalizedLandmark>? = null

    private var frameWidth: Float = 480f
    private var frameHeight: Float = 640f

    private val eyePaint = Paint().apply { color = Color.GREEN; style = Paint.Style.FILL; isAntiAlias = true }
    private val lipPaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.FILL; isAntiAlias = true }

    fun updateData(left: List<NormalizedLandmark>, right: List<NormalizedLandmark>, mouth: List<NormalizedLandmark>, fWidth: Int, fHeight: Int) {
        leftEye = left
        rightEye = right
        lips = mouth
        frameWidth = fWidth.toFloat()
        frameHeight = fHeight.toFloat()
        postInvalidate()
    }

    fun clear() {
        leftEye = null
        rightEye = null
        lips = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val overlayWidth = width.toFloat()
        val overlayHeight = height.toFloat()

        if (overlayWidth == 0f || overlayHeight == 0f) return

        // CenterCrop Math
        val scaleFactor = max(overlayWidth / frameWidth, overlayHeight / frameHeight)
        val scaledWidth = frameWidth * scaleFactor
        val scaledHeight = frameHeight * scaleFactor

        val offsetX = (overlayWidth - scaledWidth) / 2f
        val offsetY = (overlayHeight - scaledHeight) / 2f

        fun drawPoints(points: List<NormalizedLandmark>?, paint: Paint) {
            points?.forEach {
                val mirroredX = 1f - it.x()
                val cx = (mirroredX * scaledWidth) + offsetX
                val cy = (it.y() * scaledHeight) + offsetY
                canvas.drawCircle(cx, cy, 8f, paint)
            }
        }

        drawPoints(leftEye, eyePaint)
        drawPoints(rightEye, eyePaint)
        drawPoints(lips, lipPaint)
    }
}
