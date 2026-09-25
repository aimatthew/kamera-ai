package pl.rysium.kameraai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class DetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val detectionColors = intArrayOf(
        Color.rgb(34, 211, 238),  // cyan
        Color.rgb(250, 204, 21),  // yellow
        Color.rgb(251, 113, 133), // coral
        Color.rgb(167, 139, 250), // violet
        Color.rgb(96, 165, 250),  // blue
        Color.rgb(251, 146, 60)   // orange
    )
    private val boxShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 18, 22, 27)
        style = Paint.Style.FILL
    }
    private val labelAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    @Volatile
    private var result: YoloResult? = null

    fun setResult(newResult: YoloResult?) {
        result = newResult
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = result ?: return
        if (current.imageWidth <= 0 || current.imageHeight <= 0) return

        val scale = max(
            width.toFloat() / current.imageWidth,
            height.toFloat() / current.imageHeight
        )
        val offsetX = (width - current.imageWidth * scale) / 2f
        val offsetY = (height - current.imageHeight * scale) / 2f

        current.detections.forEach { detection ->
            val detectionColor = detectionColors[
                Math.floorMod(detection.label.hashCode(), detectionColors.size)
            ]
            val mapped = RectF(
                detection.box.left * scale + offsetX,
                detection.box.top * scale + offsetY,
                detection.box.right * scale + offsetX,
                detection.box.bottom * scale + offsetY
            )
            boxPaint.color = detectionColor
            labelAccent.color = detectionColor
            canvas.drawRoundRect(mapped, 8f * density, 8f * density, boxShadowPaint)
            canvas.drawRoundRect(mapped, 8f * density, 8f * density, boxPaint)

            val caption = "${detection.label} ${(detection.score * 100).toInt()}%"
            val textWidth = labelPaint.measureText(caption)
            val textHeight = labelPaint.fontMetrics.run { bottom - top }
            val labelTop = max(0f, mapped.top - textHeight - 10f * density)
            val backgroundWidth = textWidth + 24f * density
            val backgroundLeft = mapped.left.coerceIn(
                0f,
                max(0f, width.toFloat() - backgroundWidth)
            )
            val background = RectF(
                backgroundLeft,
                labelTop,
                backgroundLeft + backgroundWidth,
                labelTop + textHeight + 10f * density
            )
            canvas.drawRoundRect(background, 6f * density, 6f * density, labelBackground)
            canvas.drawRoundRect(
                RectF(
                    background.left,
                    background.top,
                    background.left + 4f * density,
                    background.bottom
                ),
                2f * density,
                2f * density,
                labelAccent
            )
            canvas.drawText(
                caption,
                background.left + 12f * density,
                background.bottom - 5f * density - labelPaint.fontMetrics.bottom,
                labelPaint
            )
        }
    }
}
