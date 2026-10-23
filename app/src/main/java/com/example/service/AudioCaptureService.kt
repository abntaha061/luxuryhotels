package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.dedup.TextDeduplicator
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Foreground Service for Real-Time Internal Audio Capture + Continuous Speech Recognition (ASR)
 * + Google ML Kit English-to-Arabic Translation + Floating WindowManager Subtitle Bubble.
 *
 * Captures internal media audio using AudioPlaybackCapture API (Android 10+ / API 29+),
 * transcribes speech continuously with SpeechRecognizer, translates English dialogue to Arabic,
 * and renders subtitles in a pass-through floating rectangular bubble over any video app.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "subtranslate_audio_channel"
        private const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.example.subtranslate.ACTION_START_AUDIO"
        const val ACTION_STOP = "com.example.subtranslate.ACTION_STOP_AUDIO"
        const val ACTION_PAUSE_RESUME = "com.example.subtranslate.ACTION_PAUSE_RESUME_AUDIO"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        // State flows observed by MainActivity and TranslatorTileService
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused = _isPaused.asStateFlow()

        private val _lastEnglish = MutableStateFlow("")
        val lastEnglish = _lastEnglish.asStateFlow()

        private val _lastArabic = MutableStateFlow("")
        val lastArabic = _lastArabic.asStateFlow()

        private val _audioLevel = MutableStateFlow(0f)
        val audioLevel = _audioLevel.asStateFlow()

        private const val AUTO_HIDE_DELAY_MS = 4000L
        private const val SAMPLE_RATE = 16000
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private var audioRecord: AudioRecord? = null
    private var audioCaptureJob: Job? = null
    private var autoHideJob: Job? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerListening = false
    private var isRecognizerAvailable = false

    private var overlayManager: FloatingOverlayManager? = null
    private val translator = MlKitSubtitleTranslator()

    private var screenWidth = 1080
    private var screenHeight = 2400

    private var lastSpokenEnglish: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        createNotificationChannel()
        initSpeechRecognizer()
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
                    initMediaProjection(resultCode, resultData)
                    initOverlays()
                    startInternalAudioCapture()
                    startSpeechRecognitionLoop()
                    _isRunning.value = true
                    _isPaused.value = false
                } else if (!_isRunning.value) {
                    Log.w(TAG, "AudioCaptureService started without valid MediaProjection token")
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
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) {
            "Audio Translation Paused"
        } else {
            "Listening to video playback audio & translating to Arabic"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SubTranslate (Live Audio)")
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
                "SubTranslate Audio Translation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for real-time video audio capture and translation"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection for audio capture", e)
            Toast.makeText(this, "Failed to initialize audio capture", Toast.LENGTH_SHORT).show()
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

    /**
     * Initializes internal audio playback capture via AudioPlaybackCaptureConfiguration (API 29+).
     * Reads PCM audio stream and calculates volume energy levels for speech activity detection.
     */
    @SuppressLint("MissingPermission")
    private fun startInternalAudioCapture() {
        val projection = mediaProjection ?: return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10 (API 29) or higher")
            return
        }

        val hasRecordAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordAudio) {
            Log.w(TAG, "RECORD_AUDIO permission missing; cannot start AudioRecord")
            return
        }

        audioCaptureJob?.cancel()
        audioCaptureJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val minBufSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = max(minBufSize, SAMPLE_RATE * 2)

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioRecord = record

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize with playback capture config")
                    return@launch
                }

                record.startRecording()
                Log.d(TAG, "AudioPlaybackCapture started recording successfully")

                val audioBuffer = ShortArray(1024)
                while (isActive) {
                    if (_isPaused.value) {
                        delay(200)
                        continue
                    }

                    val readCount = record.read(audioBuffer, 0, audioBuffer.size)
                    if (readCount > 0) {
                        // Calculate RMS audio energy
                        var sumSquare = 0.0
                        for (i in 0 until readCount) {
                            val sample = audioBuffer[i]
                            sumSquare += (sample * sample).toDouble()
                        }
                        val rms = sqrt(sumSquare / readCount)
                        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        _audioLevel.value = normalized
                    } else {
                        delay(50)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in internal audio playback capture loop", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Initializes continuous SpeechRecognizer for English dialogue transcription.
     */
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer is not available on this device")
            isRecognizerAvailable = false
            return
        }

        isRecognizerAvailable = true
        mainHandler.post {
            createAndConfigureRecognizer()
        }
    }

    private fun createAndConfigureRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isRecognizerListening = true
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                if (audioRecord == null) {
                    // Fallback RMS to audio level state
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _audioLevel.value = normalized
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isRecognizerListening = false
            }

            override fun onError(error: Int) {
                isRecognizerListening = false
                Log.d(TAG, "SpeechRecognizer error code: $error")
                // Restart listening after a brief delay if service is still running
                if (_isRunning.value && !_isPaused.value) {
                    mainHandler.postDelayed({
                        startSpeechRecognitionLoop()
                    }, 400)
                }
            }

            override fun onResults(results: Bundle?) {
                isRecognizerListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val topMatch = matches?.firstOrNull()
                if (!topMatch.isNullOrBlank()) {
                    handleTranscribedText(topMatch, isFinal = true)
                }

                // Restart recognition for continuous stream
                if (_isRunning.value && !_isPaused.value) {
                    mainHandler.postDelayed({
                        startSpeechRecognitionLoop()
                    }, 200)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val topMatch = matches?.firstOrNull()
                if (!topMatch.isNullOrBlank()) {
                    handleTranscribedText(topMatch, isFinal = false)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun startSpeechRecognitionLoop() {
        if (!_isRunning.value || _isPaused.value || !isRecognizerAvailable) return

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    createAndConfigureRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }

                speechRecognizer?.startListening(intent)
                isRecognizerListening = true
            } catch (e: Exception) {
                Log.e(TAG, "Error starting SpeechRecognizer", e)
                // Retry after delay
                mainHandler.postDelayed({
                    if (_isRunning.value && !_isPaused.value) {
                        createAndConfigureRecognizer()
                        startSpeechRecognitionLoop()
                    }
                }, 1000)
            }
        }
    }

    /**
     * Filters, deduplicates, and translates recognized English speech to Arabic in real time.
     */
    private fun handleTranscribedText(rawText: String, isFinal: Boolean) {
        val cleaned = TextDeduplicator.cleanAndFilter(rawText) ?: return

        // Deduplication: Avoid re-translating unchanged speech
        if (!TextDeduplicator.isSignificantlyDifferent(cleaned, lastSpokenEnglish)) {
            return
        }

        lastSpokenEnglish = cleaned
        _lastEnglish.value = cleaned

        serviceScope.launch {
            val translationResult = translator.translate(cleaned)
            if (translationResult.isSuccess) {
                val arabicText = translationResult.getOrDefault("")
                if (arabicText.isNotBlank()) {
                    _lastArabic.value = arabicText
                    withContext(Dispatchers.Main) {
                        overlayManager?.displayCenterBottomSubtitle(arabicText, cleaned)
                        scheduleAutoHide()
                    }
                }
            }
        }
    }

    /**
     * Resets and starts the silence countdown. If no speech is recognized for 4 seconds,
     * the floating subtitle bubble is smoothly hidden.
     */
    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = serviceScope.launch {
            delay(AUTO_HIDE_DELAY_MS)
            withContext(Dispatchers.Main) {
                overlayManager?.hideBubble()
            }
        }
    }

    private fun togglePause() {
        val newPaused = !_isPaused.value
        _isPaused.value = newPaused

        if (newPaused) {
            autoHideJob?.cancel()
            overlayManager?.hideBubble()
            overlayManager?.setPausedUIState(true)
            stopListening()
            Toast.makeText(this, "SubTranslate Paused", Toast.LENGTH_SHORT).show()
        } else {
            overlayManager?.setPausedUIState(false)
            startSpeechRecognitionLoop()
            Toast.makeText(this, "SubTranslate Resumed", Toast.LENGTH_SHORT).show()
        }

        val notification = buildForegroundNotification(isPaused = newPaused)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                isRecognizerListening = false
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying AudioCaptureService...")

        _isRunning.value = false
        _isPaused.value = false
        _lastEnglish.value = ""
        _lastArabic.value = ""
        _audioLevel.value = 0f

        audioCaptureJob?.cancel()
        autoHideJob?.cancel()
        serviceScope.cancel()

        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}

        overlayManager?.removeOverlays()
        overlayManager = null

        translator.close()
    }
}
