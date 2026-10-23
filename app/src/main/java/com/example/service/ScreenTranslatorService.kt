package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.capture.ScreenCapturer
import com.example.dedup.TextDeduplicator
import com.example.ocr.SubtitleOcrEngine
import com.example.overlay.FloatingOverlayManager
import com.example.translation.MlKitSubtitleTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real-time Auto-Tracking Screen Translator Foreground Service.
 * Works seamlessly over any video player with pass-through touch overlays
 * and adaptive Idle/Fast battery-saving scanning.
 */
class ScreenTranslatorService : Service() {

    companion object {
        private const val TAG = "ScreenTransService"
        private const val NOTIFICATION_CHANNEL_ID = "subtranslate_foreground_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.subtranslate.ACTION_START"
        const val ACTION_STOP = "com.example.subtranslate.ACTION_STOP"
        const val ACTION_PAUSE_RESUME = "com.example.subtranslate.ACTION_PAUSE_RESUME"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        // State flows observed by UI and TileService
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused = _isPaused.asStateFlow()

        private val _lastEnglish = MutableStateFlow("")
        val lastEnglish = _lastEnglish.asStateFlow()

        private val _lastArabic = MutableStateFlow("")
        val lastArabic = _lastArabic.asStateFlow()

        // Adaptive intervals: Fast (active subtitles) vs Idle (auto-sleep)
        private const val FAST_SCAN_INTERVAL_MS = 600L
        private const val IDLE_SCAN_INTERVAL_MS = 2000L
        private const val CONSECUTIVE_EMPTY_IDLE_THRESHOLD = 5
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopJob: Job? = null

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var screenCapturer: ScreenCapturer? = null
    private var overlayManager: FloatingOverlayManager? = null

    private val ocrEngine = SubtitleOcrEngine()
    private val translator = MlKitSubtitleTranslator()

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensityDpi = 420

    private var previousEnglishText: String? = null
    private var lastArabicTranslation: String = ""
    private var consecutiveEmptyCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensityDpi = metrics.densityDpi

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_RESUME -> {
                togglePause()
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && resultData != null) {
                    startForegroundWithNotification()
                    initMediaProjectionAndCapture(resultCode, resultData)
                    initOverlays()
                    startAdaptiveTranslationLoop()
                    _isRunning.value = true
                } else if (!_isRunning.value) {
                    Log.w(TAG, "Start requested without valid MediaProjection token")
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildForegroundNotification(isPaused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(isPaused: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenTranslatorService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenTranslatorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) "Translation Paused" else "Auto-tracking screen subtitles over video apps"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SubTranslate Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                if (isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumeIntent
            )
            .addAction(R.drawable.ic_close, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SubTranslate Auto-Tracker Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for real-time video subtitle translation"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun initMediaProjectionAndCapture(resultCode: Int, resultData: Intent) {
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            screenCapturer = ScreenCapturer(
                mediaProjection = mediaProjection!!,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                screenDensityDpi = screenDensityDpi
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            Toast.makeText(this, "Failed to initialize screen capture", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun initOverlays() {
        overlayManager = FloatingOverlayManager(
            context = this,
            windowManager = windowManager,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            onTogglePause = { togglePause() },
            onCloseService = { stopSelf() }
        ).apply {
            showOverlays()
        }
    }

    private fun togglePause() {
        val newPaused = !_isPaused.value
        _isPaused.value = newPaused

        overlayManager?.setPausedUIState(newPaused)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, buildForegroundNotification(isPaused = newPaused))

        Toast.makeText(
            this,
            if (newPaused) "Translation Paused" else "Translation Resumed",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Adaptive Scanning Loop:
     * - Fast Mode (every 600ms): When active English subtitles are on screen.
     * - Idle Mode (every 2000ms): Auto-sleeps after 5 consecutive empty checks to save battery & CPU.
     */
    private fun startAdaptiveTranslationLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch(Dispatchers.Default) {
            var currentIntervalMs = FAST_SCAN_INTERVAL_MS

            while (isActive) {
                if (_isPaused.value) {
                    delay(1000)
                    continue
                }

                val hasSubtitle = processScreenFrame()

                if (hasSubtitle) {
                    consecutiveEmptyCount = 0
                    currentIntervalMs = FAST_SCAN_INTERVAL_MS // Wake to Fast Mode (600ms)
                } else {
                    consecutiveEmptyCount++
                    if (consecutiveEmptyCount >= CONSECUTIVE_EMPTY_IDLE_THRESHOLD) {
                        currentIntervalMs = IDLE_SCAN_INTERVAL_MS // Enter Auto-Sleep Mode (2000ms)
                    }
                }

                delay(currentIntervalMs)
            }
        }
    }

    /**
     * Captures full screen, detects English dialogue, and translates to Arabic.
     * Returns true if a valid English subtitle sentence was found on screen.
     */
    private suspend fun processScreenFrame(): Boolean {
        val capturer = screenCapturer ?: return false
        val overlays = overlayManager ?: return false

        // 1. Capture full screen frame
        val fullBitmap = capturer.captureFullScreen() ?: return false
        val bitmapWidth = fullBitmap.width
        val bitmapHeight = fullBitmap.height

        // 2. Run OCR & detect English dialogue subtitle with bounding box
        val detectionResult = ocrEngine.detectSubtitleSentence(fullBitmap, screenWidth, screenHeight)
        fullBitmap.recycle()

        val candidate = detectionResult.getOrNull()

        // 3. No subtitle detected: Hide the floating bubble
        if (candidate == null) {
            withContext(Dispatchers.Main) {
                overlays.hideBubble()
            }
            return false
        }

        val detectedText = candidate.text
        val detectedBox = candidate.boundingBox

        // 4. Anti-flicker deduplication: Check if text actually changed
        val isDifferent = TextDeduplicator.isSignificantlyDifferent(detectedText, previousEnglishText)

        if (!isDifferent && lastArabicTranslation.isNotBlank()) {
            // Text is identical: simply adjust position without re-translating or flickering
            withContext(Dispatchers.Main) {
                overlays.positionBubbleBelowText(
                    detectedBox = detectedBox,
                    arabicText = lastArabicTranslation,
                    englishText = detectedText,
                    bitmapWidth = bitmapWidth,
                    bitmapHeight = bitmapHeight
                )
            }
            return true
        }

        previousEnglishText = detectedText

        // 5. Translate new English sentence to Arabic via on-device ML Kit
        val translationResult = translator.translate(detectedText)
        val arabicText = translationResult.getOrDefault("")

        if (arabicText.isNotBlank()) {
            lastArabicTranslation = arabicText
            withContext(Dispatchers.Main) {
                overlays.positionBubbleBelowText(
                    detectedBox = detectedBox,
                    arabicText = arabicText,
                    englishText = detectedText,
                    bitmapWidth = bitmapWidth,
                    bitmapHeight = bitmapHeight
                )
                _lastArabic.value = arabicText
                _lastEnglish.value = detectedText
            }
            return true
        }

        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        serviceScope.cancel()

        overlayManager?.removeOverlays()
        overlayManager = null

        screenCapturer?.release()
        ocrEngine.close()
        translator.close()

        _isRunning.value = false
        _isPaused.value = false
        _lastArabic.value = ""
        _lastEnglish.value = ""

        Log.d(TAG, "ScreenTranslatorService destroyed")
    }
}
