package pl.rysium.kameraai

import android.graphics.RectF

data class Detection(
    val box: RectF,
    val classIndex: Int,
    val label: String,
    val score: Float
)

data class YoloResult(
    val detections: List<Detection>,
    val imageWidth: Int,
    val imageHeight: Int,
    val inferenceMs: Long
)
