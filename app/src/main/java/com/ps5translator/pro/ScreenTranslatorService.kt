package com.ps5translator.pro

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

class ScreenTranslatorService : Service() {

    companion object {
        const val ACTION_START = "com.ps5translator.pro.START"
        const val ACTION_STOP = "com.ps5translator.pro.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "ps5_translator_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var config: AppConfig
    private lateinit var windowManager: WindowManager
    private lateinit var voiceReader: ArabicVoiceReader
    private val translator = GeminiTranslator()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var scanJob: Job? = null

    private var subtitleBarView: View? = null
    private var subtitleBarParams: WindowManager.LayoutParams? = null
    private var tvTranslated: TextView? = null
    private var tvStatus: TextView? = null
    private var btnDialect: TextView? = null
    private var btnTts: TextView? = null
    private var btnPause: TextView? = null

    private var scanBoxView: View? = null
    private var scanBoxParams: WindowManager.LayoutParams? = null

    @Volatile
    private var isPaused = false

    @Volatile
    private var isEditingArea = false

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 320

    private var lastLumaSignature: IntArray? = null
    private var lastOcrText = ""
    private var lastStableCandidate = ""
    private var candidateRepeatCount = 0

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        voiceReader = ArabicVoiceReader(this).apply {
            enabled = config.ttsEnabled
            speechRate = config.ttsSpeed
        }
        updateDisplayMetrics()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        setupMediaProjection(resultCode, resultData)
        showSubtitleOverlay()
        showScanBoxOverlay()
        startCaptureLoop()

        return START_STICKY
    }

    private fun startForegroundCompat() {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenTranslatorService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PS5 Translator Pro يعمل الآن")
            .setContentText("الترجمة الفورية والقارئ الصوتي نشطان فوق اللعبة")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", pendingStop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            stopEverything()
            return
        }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopEverything() }
            }
        }, mainHandler)

        mediaProjection = projection
        recreateVirtualDisplay()
    }

    private fun recreateVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()

        updateDisplayMetrics()
        val reader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PS5TranslatorCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreateVirtualDisplay()
        if (scanBoxView != null) {
            applySavedRatiosToScanBox()
        }
    }

    private fun updateDisplayMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(1)
            screenHeight = bounds.height().coerceAtLeast(1)
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels.coerceAtLeast(1)
            screenHeight = dm.heightPixels.coerceAtLeast(1)
            screenDensity = dm.densityDpi
        }
    }

    private fun showSubtitleOverlay() {
        if (subtitleBarView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle_bar, null)
        val params = WindowManager.LayoutParams(
            (screenWidth * 0.92f).toInt().coerceAtMost(1400),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 40
        }

        tvTranslated = view.findViewById(R.id.tvTranslatedText)
        tvStatus = view.findViewById(R.id.tvStatusBadge)
        btnDialect = view.findViewById(R.id.btnToggleDialect)
        btnTts = view.findViewById(R.id.btnToggleTts)
        btnPause = view.findViewById(R.id.btnPauseResume)

        refreshToolbarUI()

        btnDialect?.setOnClickListener {
            config.dialect = if (config.dialect == DialectMode.ORIGINAL_MSA) {
                DialectMode.IRAQI
            } else {
                DialectMode.ORIGINAL_MSA
            }
            lastOcrText = ""
            refreshToolbarUI()
        }

        btnTts?.setOnClickListener {
            config.ttsEnabled = !config.ttsEnabled
            voiceReader.enabled = config.ttsEnabled
            if (!config.ttsEnabled) voiceReader.stop()
            refreshToolbarUI()
        }

        view.findViewById<View>(R.id.btnEditArea).setOnClickListener {
            if (scanBoxView == null) showScanBoxOverlay() else hideScanBoxOverlay()
        }

        btnPause?.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                translator.cancelActive()
                voiceReader.stop()
            }
            refreshToolbarUI()
        }

        view.findViewById<View>(R.id.btnCloseOverlay).setOnClickListener {
            stopEverything()
        }

        val dragHandle = view.findViewById<View>(R.id.dragHandle)
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        subtitleBarView = view
        subtitleBarParams = params
        windowManager.addView(view, params)
    }

    private fun refreshToolbarUI() {
        btnDialect?.text = if (config.dialect == DialectMode.IRAQI) "عراقي 🇮🇶" else "فصحى"
        btnTts?.text = if (config.ttsEnabled) "🔊" else "🔇"
        btnPause?.text = if (isPaused) "▶" else "⏸"
        if (isPaused) {
            tvStatus?.text = "⏸ متوقف مؤقتاً"
        }
    }

    private fun showScanBoxOverlay() {
        if (scanBoxView != null) return
        isEditingArea = true

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_scan_box, null)
        val params = WindowManager.LayoutParams(
            (screenWidth * config.boxWidthRatio).toInt().coerceAtLeast(180),
            (screenHeight * config.boxHeightRatio).toInt().coerceAtLeast(80),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * config.boxLeftRatio).toInt()
            y = (screenHeight * config.boxTopRatio).toInt()
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - touchX).toInt()).coerceIn(0, screenWidth - params.width)
                    params.y = (startY + (event.rawY - touchY).toInt()).coerceIn(0, screenHeight - params.height)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        val resizeHandle = view.findViewById<View>(R.id.resizeHandle)
        var startW = 0
        var startH = 0
        var rTouchX = 0f
        var rTouchY = 0f
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = params.height
                    rTouchX = event.rawX
                    rTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (event.rawX - rTouchX).toInt()).coerceIn(180, screenWidth - params.x)
                    params.height = (startH + (event.rawY - rTouchY).toInt()).coerceIn(70, screenHeight - params.y)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.btnConfirmArea).setOnClickListener {
            config.boxLeftRatio = params.x.toFloat() / screenWidth.toFloat()
            config.boxTopRatio = params.y.toFloat() / screenHeight.toFloat()
            config.boxWidthRatio = params.width.toFloat() / screenWidth.toFloat()
            config.boxHeightRatio = params.height.toFloat() / screenHeight.toFloat()
            hideScanBoxOverlay()
            lastOcrText = ""
            lastLumaSignature = null
            mainHandler.post {
                tvStatus?.text = "⚡ المنطقة مؤكدة • جاري مراقبة السابتايتل"
            }
        }

        scanBoxView = view
        scanBoxParams = params
        windowManager.addView(view, params)
    }

    private fun applySavedRatiosToScanBox() {
        val view = scanBoxView ?: return
        val params = scanBoxParams ?: return
        params.x = (screenWidth * config.boxLeftRatio).toInt()
        params.y = (screenHeight * config.boxTopRatio).toInt()
        params.width = (screenWidth * config.boxWidthRatio).toInt().coerceAtLeast(180)
        params.height = (screenHeight * config.boxHeightRatio).toInt().coerceAtLeast(70)
        windowManager.updateViewLayout(view, params)
    }

    private fun hideScanBoxOverlay() {
        scanBoxView?.let { runCatching { windowManager.removeView(it) } }
        scanBoxView = null
        scanBoxParams = null
        isEditingArea = false
    }

    private fun startCaptureLoop() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            while (isActive) {
                delay(config.scanIntervalMs)
                if (isPaused || isEditingArea) continue

                val cropBitmap = captureSubtitleCrop() ?: continue
                try {
                    val sig = computeQuickLumaSignature(cropBitmap)
                    if (isSignatureSimilar(lastLumaSignature, sig)) {
                        continue
                    }
                    lastLumaSignature = sig

                    val rawOcr = runMlKitOcr(cropBitmap)
                    val cleanEn = OcrPostProcessor.cleanSubtitle(rawOcr)
                    if (cleanEn.isEmpty()) continue

                    if (OcrPostProcessor.similarity(cleanEn, lastOcrText) >= 0.86) {
                        continue
                    }

                    if (OcrPostProcessor.similarity(cleanEn, lastStableCandidate) >= 0.90) {
                        candidateRepeatCount++
                    } else {
                        lastStableCandidate = cleanEn
                        candidateRepeatCount = 1
                        continue
                    }

                    if (candidateRepeatCount < 2) continue

                    lastOcrText = cleanEn
                    candidateRepeatCount = 0

                    mainHandler.post {
                        tvStatus?.text = "⚡ جاري الترجمة..."
                    }

                    translator.translateStreaming(
                        apiKey = config.apiKey,
                        model = config.modelName,
                        englishText = cleanEn,
                        dialect = config.dialect,
                        gamePreset = config.gamePreset,
                        onPartial = { partialArabic ->
                            mainHandler.post {
                                tvTranslated?.text = partialArabic
                            }
                        },
                        onComplete = { finalArabic, latencyMs ->
                            mainHandler.post {
                                tvTranslated?.text = finalArabic
                                val modeTag = if (config.dialect == DialectMode.IRAQI) "عراقي" else "فصحى"
                                tvStatus?.text = "⚡ ${latencyMs}ms • $modeTag"
                            }
                            voiceReader.speak(finalArabic)
                        },
                        onError = { err ->
                            mainHandler.post {
                                tvStatus?.text = "⚠️ $err"
                            }
                        }
                    )
                } finally {
                    cropBitmap.recycle()
                }
            }
        }
    }

    private fun captureSubtitleCrop(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBmp.copyPixelsFromBuffer(buffer)

            val cropX = (image.width * config.boxLeftRatio).toInt().coerceIn(0, image.width - 2)
            val cropY = (image.height * config.boxTopRatio).toInt().coerceIn(0, image.height - 2)
            val cropW = (image.width * config.boxWidthRatio).toInt().coerceIn(2, image.width - cropX)
            val cropH = (image.height * config.boxHeightRatio).toInt().coerceIn(2, image.height - cropY)

            val cropped = Bitmap.createBitmap(fullBmp, cropX, cropY, cropW, cropH)
            fullBmp.recycle()
            cropped
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun computeQuickLumaSignature(bmp: Bitmap): IntArray {
        val cols = 12
        val rows = 5
        val sig = IntArray(cols * rows)
        var idx = 0
        for (r in 0 until rows) {
            val y = ((r + 0
