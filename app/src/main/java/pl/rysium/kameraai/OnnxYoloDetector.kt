package pl.rysium.kameraai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OnnxYoloDetector(
    context: Context,
    private val confidenceThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.50f
) : AutoCloseable {

    private val labels = CocoLabels.PL
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val backendName: String
    private val inputName: String
    private val inputWidth: Int
    private val inputHeight: Int

    init {
        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val sessionSetup = createSession(modelBytes)
        session = sessionSetup.session
        backendName = sessionSetup.backendName

        inputName = session.inputNames.first()
        val tensorInfo = session.inputInfo.getValue(inputName).info as TensorInfo
        val shape = tensorInfo.shape
        require(shape.size == 4 && shape[1] == 3L) {
            "Oczekiwano wej\u015bcia YOLO NCHW, otrzymano: ${shape.contentToString()}"
        }
        inputHeight = shape[2].toInt()
        inputWidth = shape[3].toInt()
    }

    private fun createSession(modelBytes: ByteArray): SessionSetup {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val nnapiOptions = createSessionOptions(cpuThreads = 2)
            try {
                nnapiOptions.addNnapi(
                    EnumSet.of(
                        NNAPIFlags.CPU_DISABLED,
                        NNAPIFlags.USE_FP16
                    )
                )
                return SessionSetup(
                    session = environment.createSession(modelBytes, nnapiOptions),
                    backendName = "NNAPI"
                )
            } catch (_: Exception) {
                // Nie każdy sterownik NNAPI obsługuje wszystkie operacje modelu YOLO.
                // W takim przypadku aplikacja nadal uruchomi się na sprawdzonym backendzie CPU.
            } finally {
                nnapiOptions.close()
            }
        }

        val cpuOptions = createSessionOptions(
            cpuThreads = max(2, Runtime.getRuntime().availableProcessors() / 2)
        )
        return try {
            SessionSetup(
                session = environment.createSession(modelBytes, cpuOptions),
                backendName = "CPU"
            )
        } finally {
            cpuOptions.close()
        }
    }

    private fun createSessionOptions(cpuThreads: Int) =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(cpuThreads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

    fun detect(source: Bitmap): YoloResult {
        val prepared = letterbox(source)
        val inputData = bitmapToChwFloatArray(prepared.bitmap)
        prepared.bitmap.recycle()

        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        )

        val startedAt = System.nanoTime()
        val outputs = try {
            session.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }
        val inferenceMs = (System.nanoTime() - startedAt) / 1_000_000

        val detections = outputs.use { result ->
            val output = result[0] as OnnxTensor
            val shape = (output.info as TensorInfo).shape
            @Suppress("UNCHECKED_CAST")
            val matrix = (output.value as Array<Array<FloatArray>>)[0]
            decode(
                matrix = matrix,
                outputShape = shape,
                originalWidth = source.width,
                originalHeight = source.height,
                scale = prepared.scale,
                padX = prepared.padX,
                padY = prepared.padY
            )
        }

        return YoloResult(
            detections = detections,
            imageWidth = source.width,
            imageHeight = source.height,
            inferenceMs = inferenceMs
        )
    }

    private fun bitmapToChwFloatArray(bitmap: Bitmap): FloatArray {
        val pixelCount = inputWidth * inputHeight
        val pixels = IntArray(pixelCount)
        val result = FloatArray(pixelCount * 3)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        pixels.forEachIndexed { index, pixel ->
            result[index] = Color.red(pixel) / 255f
            result[pixelCount + index] = Color.green(pixel) / 255f
            result[pixelCount * 2 + index] = Color.blue(pixel) / 255f
        }
        return result
    }

    private fun letterbox(source: Bitmap): PreparedImage {
        val scale = min(
            inputWidth.toFloat() / source.width,
            inputHeight.toFloat() / source.height
        )
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val padX = (inputWidth - scaledWidth) / 2f
        val padY = (inputHeight - scaledHeight) / 2f

        val target = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        Canvas(target).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(
                source,
                null,
                RectF(padX, padY, padX + scaledWidth, padY + scaledHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }
        return PreparedImage(target, scale, padX, padY)
    }

    private fun decode(
        matrix: Array<FloatArray>,
        outputShape: LongArray,
        originalWidth: Int,
        originalHeight: Int,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<Detection> {
        require(outputShape.size == 3 && outputShape[0] == 1L) {
            "Nieobs\u0142ugiwane wyj\u015bcie YOLO: ${outputShape.contentToString()}"
        }

        val first = outputShape[1].toInt()
        val second = outputShape[2].toInt()
        val channelsFirst = first < second
        val features = if (channelsFirst) first else second
        val candidates = if (channelsFirst) second else first
        require(features > 6) {
            "Eksport modelu musi u\u017cywa\u0107 nms=False."
        }

        val classCount = min(labels.size, features - 4)
        val proposals = ArrayList<Detection>()

        fun value(candidate: Int, feature: Int): Float =
            if (channelsFirst) matrix[feature][candidate]
            else matrix[candidate][feature]

        for (candidate in 0 until candidates) {
            var bestClass = -1
            var bestScore = confidenceThreshold
            for (classIndex in 0 until classCount) {
                val score = value(candidate, classIndex + 4)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }
            if (bestClass < 0) continue

            var centerX = value(candidate, 0)
            var centerY = value(candidate, 1)
            var boxWidth = value(candidate, 2)
            var boxHeight = value(candidate, 3)

            val normalized = max(
                max(abs(centerX), abs(centerY)),
                max(abs(boxWidth), abs(boxHeight))
            ) <= 2f
            if (normalized) {
                centerX *= inputWidth
                boxWidth *= inputWidth
                centerY *= inputHeight
                boxHeight *= inputHeight
            }

            val left = ((centerX - boxWidth / 2f - padX) / scale)
                .coerceIn(0f, originalWidth.toFloat())
            val top = ((centerY - boxHeight / 2f - padY) / scale)
                .coerceIn(0f, originalHeight.toFloat())
            val right = ((centerX + boxWidth / 2f - padX) / scale)
                .coerceIn(0f, originalWidth.toFloat())
            val bottom = ((centerY + boxHeight / 2f - padY) / scale)
                .coerceIn(0f, originalHeight.toFloat())

            if (right - left < 2f || bottom - top < 2f) continue

            proposals += Detection(
                box = RectF(left, top, right, bottom),
                classIndex = bestClass,
                label = labels[bestClass],
                score = bestScore
            )
        }
        return nonMaximumSuppression(proposals)
    }

    private fun nonMaximumSuppression(proposals: List<Detection>): List<Detection> {
        val selected = ArrayList<Detection>()
        for (candidate in proposals.sortedByDescending { it.score }) {
            val overlaps = selected.any { chosen ->
                chosen.classIndex == candidate.classIndex &&
                    intersectionOverUnion(chosen.box, candidate.box) > iouThreshold
            }
            if (!overlaps) {
                selected += candidate
                if (selected.size == MAX_RESULTS) break
            }
        }
        return selected
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val intersectionWidth = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val intersectionHeight = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        session.close()
    }

    private data class PreparedImage(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private data class SessionSetup(
        val session: OrtSession,
        val backendName: String
    )

    companion object {
        const val MODEL_FILE = "yolo11s.onnx"
        private const val MAX_RESULTS = 100
    }
}
