package pl.rysium.kameraai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class DetectionDisplayMode {
    MINIMAL,
    AUTOMATIC,
    FULL
}

class DetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val detectionColors = intArrayOf(
        Color.rgb(77, 166, 168),  // muted teal
        Color.rgb(224, 174, 90),  // warm amber
        Color.rgb(218, 121, 113), // soft coral
        Color.rgb(151, 132, 190), // lavender
        Color.rgb(100, 150, 198), // soft blue
        Color.rgb(203, 133, 98)   // terracotta
    )
    private val boxShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(165, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 250, 252)
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 20, 24, 29)
        style = Paint.Style.FILL
    }
    private val labelAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    @Volatile
    private var result: YoloResult? = null
    private var displayMode: DetectionDisplayMode = DetectionDisplayMode.AUTOMATIC

    fun setResult(newResult: YoloResult?) {
        result = newResult
        postInvalidateOnAnimation()
    }

    fun setDisplayMode(newMode: DetectionDisplayMode) {
        displayMode = newMode
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

        val sortedDetections = current.detections.sortedByDescending { it.score }
        val crowded = sortedDetections.size > 4
        val visibleDetections = when (displayMode) {
            DetectionDisplayMode.MINIMAL -> sortedDetections.take(4)
            DetectionDisplayMode.AUTOMATIC -> {
                if (crowded) sortedDetections.take(6) else sortedDetections
            }
            DetectionDisplayMode.FULL -> sortedDetections
        }
        val labelLimit = when (displayMode) {
            DetectionDisplayMode.MINIMAL -> 1
            DetectionDisplayMode.AUTOMATIC -> when {
                sortedDetections.size <= 4 -> sortedDetections.size
                sortedDetections.size <= 8 -> 3
                else -> 2
            }
            DetectionDisplayMode.FULL -> visibleDetections.size
        }
        val useCornerFrames = displayMode == DetectionDisplayMode.MINIMAL ||
            (displayMode == DetectionDisplayMode.AUTOMATIC && crowded)
        val occupiedLabels = mutableListOf<RectF>()

        visibleDetections.forEachIndexed { index, detection ->
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

            if (useCornerFrames) {
                drawCornerFrame(canvas, mapped, boxShadowPaint)
                drawCornerFrame(canvas, mapped, boxPaint)
            } else {
                canvas.drawRoundRect(mapped, 10f * density, 10f * density, boxShadowPaint)
                canvas.drawRoundRect(mapped, 10f * density, 10f * density, boxPaint)
            }

            if (index >= labelLimit) return@forEachIndexed

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
            if (occupiedLabels.any { RectF.intersects(it, background) }) {
                return@forEachIndexed
            }
            occupiedLabels += RectF(background)

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

    private fun drawCornerFrame(canvas: Canvas, box: RectF, paint: Paint) {
        val shortestSide = min(box.width(), box.height())
        val cornerLength = min(
            28f * density,
            max(6f * density, shortestSide * 0.24f)
        ).coerceAtMost(shortestSide * 0.45f)

        canvas.drawLine(box.left, box.top, box.left + cornerLength, box.top, paint)
        canvas.drawLine(box.left, box.top, box.left, box.top + cornerLength, paint)
        canvas.drawLine(box.right, box.top, box.right - cornerLength, box.top, paint)
        canvas.drawLine(box.right, box.top, box.right, box.top + cornerLength, paint)
        canvas.drawLine(box.left, box.bottom, box.left + cornerLength, box.bottom, paint)
        canvas.drawLine(box.left, box.bottom, box.left, box.bottom - cornerLength, paint)
        canvas.drawLine(box.right, box.bottom, box.right - cornerLength, box.bottom, paint)
        canvas.drawLine(box.right, box.bottom, box.right, box.bottom - cornerLength, paint)
    }
}
