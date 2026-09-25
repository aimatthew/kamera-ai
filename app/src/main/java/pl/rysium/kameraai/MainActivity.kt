package pl.rysium.kameraai

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.Window
import android.view.WindowManager
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: View
    private lateinit var statusPanel: View
    private lateinit var resultPanel: View
    private lateinit var resultText: TextView
    private lateinit var permissionPanel: LinearLayout
    private lateinit var updateManager: GitHubUpdateManager

    private var updateDialog: Dialog? = null
    private var updateStatusText: TextView? = null
    private var pendingUpdateApk: File? = null
    private var statusHiddenForDetections = false
    private var detectionDisplayMode = DetectionDisplayMode.AUTOMATIC

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val isAnalyzing = AtomicBoolean(false)
    private var detector: OnnxYoloDetector? = null
    private var inferenceErrorShown = false
    private var lastAnalysisStartedNs = 0L

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            showCameraUi(granted)
            if (granted) {
                startCamera()
            }
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apkFile = pendingUpdateApk ?: return@registerForActivityResult
            if (packageManager.canRequestPackageInstalls()) {
                launchInstaller(apkFile)
            } else {
                updateStatusText?.text = getString(R.string.install_permission_needed)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.detectionOverlay)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusPanel = findViewById(R.id.statusPanel)
        resultPanel = findViewById(R.id.resultPanel)
        resultText = findViewById(R.id.resultText)
        permissionPanel = findViewById(R.id.permissionPanel)
        updateManager = GitHubUpdateManager(applicationContext)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        restoreDetectionDisplayMode()

        findViewById<Button>(R.id.permissionButton).apply {
            backgroundTintList = null
            setOnClickListener {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            showUpdateMenu()
        }

        loadModel()
        ensureCameraPermission()
    }

    private fun loadModel() {
        setStatus(getString(R.string.status_loading), R.color.accent)
        analysisExecutor.execute {
            try {
                val loadedDetector = OnnxYoloDetector(applicationContext)
                detector = loadedDetector
                runOnUiThread {
                    setStatus(
                        "YOLO11s  •  ${loadedDetector.backendName}  •  gotowy",
                        R.color.success
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setStatus("Błąd modelu", R.color.error)
                    resultText.text =
                        "Dodaj app/src/main/assets/${OnnxYoloDetector.MODEL_FILE}"
                }
            }
        }
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        ) {
            showCameraUi(true)
            startCamera()
        } else {
            showCameraUi(false)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor, ::analyzeFrame)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (error: Exception) {
                setStatus("Nie udało się uruchomić aparatu", R.color.error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        val currentDetector = detector
        val nowNs = System.nanoTime()
        val analysisTooSoon = nowNs - lastAnalysisStartedNs < ANALYSIS_INTERVAL_NS
        if (
            currentDetector == null ||
            analysisTooSoon ||
            !isAnalyzing.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastAnalysisStartedNs = nowNs

        var uprightBitmap: Bitmap? = null
        try {
            val cameraBitmap = imageProxyToBitmap(image)
            uprightBitmap = rotateBitmap(cameraBitmap, image.imageInfo.rotationDegrees)
            if (uprightBitmap !== cameraBitmap) cameraBitmap.recycle()

            val result = currentDetector.detect(uprightBitmap)
            runOnUiThread {
                overlay.setResult(result)
                updateDetectionStatusVisibility(result.detections.isNotEmpty())
                setStatus(
                    "YOLO11s  •  ${currentDetector.backendName}  •  " +
                        "${result.inferenceMs} ms  •  ${result.detections.size}",
                    R.color.success
                )
                resultText.text = summarize(result.detections)
            }
        } catch (error: Exception) {
            if (!inferenceErrorShown) {
                inferenceErrorShown = true
                runOnUiThread {
                    showStatusPanelImmediately()
                    setStatus("Błąd analizy obrazu", R.color.error)
                    resultText.text = error.message ?: "Nieznany b\u0142\u0105d modelu"
                }
            }
        } finally {
            uprightBitmap?.recycle()
            image.close()
            isAnalyzing.set(false)
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)

        if (paddedWidth == image.width) return padded

        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
            padded.recycle()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun summarize(detections: List<Detection>): String {
        if (detections.isEmpty()) return getString(R.string.no_objects)

        return detections
            .groupingBy { it.label }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key })
            .joinToString("  |  ") { (label, count) ->
                if (count == 1) label else "$label x $count"
            }
    }

    private fun showCameraUi(show: Boolean) {
        permissionPanel.visibility = if (show) View.GONE else View.VISIBLE
        statusPanel.visibility = if (show) View.VISIBLE else View.GONE
        resultPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setStatus(message: String, colorRes: Int) {
        statusText.text = message
        statusIndicator.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, colorRes)
        )
    }

    private fun updateDetectionStatusVisibility(hasDetections: Boolean) {
        if (statusHiddenForDetections == hasDetections) return
        statusHiddenForDetections = hasDetections
        statusPanel.animate().cancel()

        if (hasDetections) {
            statusPanel.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction {
                    if (statusHiddenForDetections) statusPanel.visibility = View.GONE
                }
                .start()
        } else {
            statusPanel.alpha = 0f
            statusPanel.visibility = View.VISIBLE
            statusPanel.animate()
                .alpha(1f)
                .setDuration(180L)
                .start()
        }
    }

    private fun showStatusPanelImmediately() {
        statusHiddenForDetections = false
        statusPanel.animate().cancel()
        statusPanel.alpha = 1f
        statusPanel.visibility = View.VISIBLE
    }

    private fun showUpdateMenu() {
        if (updateDialog?.isShowing == true) return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update)
        dialog.setCanceledOnTouchOutside(true)

        val status = dialog.findViewById<TextView>(R.id.updateStatusText)
        val progress = dialog.findViewById<ProgressBar>(R.id.updateProgress)
        val checkButton = dialog.findViewById<Button>(R.id.checkUpdateButton)
        val githubButton = dialog.findViewById<Button>(R.id.openGithubButton)

        updateStatusText = status
        configureDisplayModeButtons(dialog)
        dialog.findViewById<TextView>(R.id.currentVersionText).text =
            getString(R.string.current_version, BuildConfig.VERSION_NAME)

        checkButton.backgroundTintList = null
        githubButton.backgroundTintList = null

        if (updateManager.isConfigured) {
            checkButton.setOnClickListener {
                checkForUpdate(status, progress, checkButton)
            }
            githubButton.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateManager.repositoryUrl)))
            }
        } else {
            status.text = getString(R.string.github_repository_missing)
            checkButton.isEnabled = false
            checkButton.alpha = 0.45f
            githubButton.isEnabled = false
            githubButton.alpha = 0.45f
        }

        dialog.findViewById<ImageButton>(R.id.closeMenuButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            updateDialog = null
            updateStatusText = null
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.58f }
        }
        dialog.show()

        val horizontalMargin = (32 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - horizontalMargin,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        updateDialog = dialog
    }

    private fun restoreDetectionDisplayMode() {
        val savedMode = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(PREFERENCE_DISPLAY_MODE, DetectionDisplayMode.AUTOMATIC.name)
        detectionDisplayMode = DetectionDisplayMode.values()
            .firstOrNull { it.name == savedMode }
            ?: DetectionDisplayMode.AUTOMATIC
        overlay.setDisplayMode(detectionDisplayMode)
    }

    private fun configureDisplayModeButtons(dialog: Dialog) {
        val buttons = mapOf(
            DetectionDisplayMode.MINIMAL to
                dialog.findViewById<TextView>(R.id.minimalModeButton),
            DetectionDisplayMode.AUTOMATIC to
                dialog.findViewById<TextView>(R.id.automaticModeButton),
            DetectionDisplayMode.FULL to
                dialog.findViewById<TextView>(R.id.fullModeButton)
        )

        fun refreshSelection() {
            buttons.forEach { (mode, button) ->
                button.isActivated = mode == detectionDisplayMode
            }
        }

        buttons.forEach { (mode, button) ->
            button.setOnClickListener {
                detectionDisplayMode = mode
                overlay.setDisplayMode(mode)
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREFERENCE_DISPLAY_MODE, mode.name)
                    .apply()
                refreshSelection()
            }
        }
        refreshSelection()
    }

    private fun checkForUpdate(
        status: TextView,
        progress: ProgressBar,
        button: Button
    ) {
        status.text = getString(R.string.checking_update)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        button.isEnabled = false
        button.alpha = 0.65f

        updateManager.checkForUpdate(
            onSuccess = { release ->
                runOnUiThread {
                    if (updateDialog?.isShowing != true) return@runOnUiThread
                    progress.visibility = View.GONE
                    button.isEnabled = true
                    button.alpha = 1f

                    if (updateManager.isNewerVersion(
                            release.version,
                            BuildConfig.VERSION_NAME
                        )
                    ) {
                        status.text = getString(
                            R.string.new_version_available,
                            release.version
                        )
                        button.text = getString(R.string.download_and_install)
                        button.setOnClickListener {
                            downloadUpdate(release, status, progress, button)
                        }
                    } else {
                        status.text = getString(R.string.latest_version)
                        button.text = getString(R.string.check_again)
                        button.setOnClickListener {
                            checkForUpdate(status, progress, button)
                        }
                    }
                }
            },
            onError = { message ->
                runOnUiThread {
                    if (updateDialog?.isShowing != true) return@runOnUiThread
                    progress.visibility = View.GONE
                    status.text = message
                    button.text = getString(R.string.check_again)
                    button.isEnabled = true
                    button.alpha = 1f
                }
            }
        )
    }

    private fun downloadUpdate(
        release: GitHubRelease,
        status: TextView,
        progress: ProgressBar,
        button: Button
    ) {
        button.isEnabled = false
        button.alpha = 0.65f
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progress.max = 100
        progress.progress = 0
        status.text = getString(R.string.downloading_update, 0)

        updateManager.downloadApk(
            release = release,
            onProgress = { value ->
                runOnUiThread {
                    progress.isIndeterminate = false
                    progress.progress = value
                    status.text = getString(R.string.downloading_update, value)
                }
            },
            onSuccess = { apkFile ->
                runOnUiThread {
                    pendingUpdateApk = apkFile
                    progress.visibility = View.GONE
                    status.text = getString(R.string.opening_installer)
                    launchInstaller(apkFile)
                }
            },
            onError = { message ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = message
                    button.text = getString(R.string.download_and_install)
                    button.isEnabled = true
                    button.alpha = 1f
                }
            }
        )
    }

    private fun launchInstaller(apkFile: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            updateStatusText?.text = getString(R.string.install_permission_needed)
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            installPermissionLauncher.launch(permissionIntent)
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (error: Exception) {
            updateStatusText?.text =
                error.message ?: "Nie udało się otworzyć instalatora Androida."
        }
    }

    override fun onDestroy() {
        updateDialog?.dismiss()
        updateManager.close()
        detector?.close()
        detector = null
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES_NAME = "kamera_ai_preferences"
        const val PREFERENCE_DISPLAY_MODE = "detection_display_mode"
        const val ANALYSIS_INTERVAL_NS = 100_000_000L
    }
}

