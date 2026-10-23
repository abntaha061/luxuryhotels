package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.service.AudioCaptureService
import com.example.translation.MlKitSubtitleTranslator
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkNavyBg
import com.example.ui.theme.DarkNavySurface
import com.example.ui.theme.DarkNavySurfaceVariant
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START = "com.example.subtranslate.EXTRA_AUTO_START"
    }

    private val translator = MlKitSubtitleTranslator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainScreen(
                    translator = translator,
                    onStartCapture = { resultCode, resultData ->
                        startAudioCaptureService(resultCode, resultData)
                    },
                    onStopCapture = {
                        stopAudioCaptureService()
                    },
                    onTogglePause = {
                        togglePauseService()
                    }
                )
            }
        }
    }

    private fun startAudioCaptureService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopAudioCaptureService() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun togglePauseService() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_PAUSE_RESUME
        }
        startService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        translator.close()
    }
}

@Composable
fun MainScreen(
    translator: MlKitSubtitleTranslator,
    onStartCapture: (Int, Intent) -> Unit,
    onStopCapture: () -> Unit,
    onTogglePause: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // AudioCaptureService observables
    val isRunning by AudioCaptureService.isRunning.collectAsState()
    val isPaused by AudioCaptureService.isPaused.collectAsState()
    val lastEnglish by AudioCaptureService.lastEnglish.collectAsState()
    val lastArabic by AudioCaptureService.lastArabic.collectAsState()
    val audioLevel by AudioCaptureService.audioLevel.collectAsState()

    // Permissions state
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Refresh permission states on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Model Download State
    var isModelDownloaded by remember { mutableStateOf(false) }
    var isDownloadingModel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isModelDownloaded = translator.isModelDownloaded()
    }

    // Overlay Permission launcher
    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    // Audio Permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    // Notification Permission launcher (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    // MediaProjection capture consent launcher
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartCapture(result.resultCode, result.data!!)
        } else {
            Toast.makeText(context, "Audio capture permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    // All prerequisites check
    val canStart = hasOverlayPermission && hasAudioPermission && hasNotificationPermission && isModelDownloaded
    val shouldAutoStart = (context as? Activity)?.intent?.getBooleanExtra(MainActivity.EXTRA_AUTO_START, false) == true

    LaunchedEffect(canStart, shouldAutoStart, isRunning) {
        if (canStart && shouldAutoStart && !isRunning) {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            mediaProjectionLauncher.launch(captureIntent)
        }
    }

    Scaffold(
        containerColor = DarkNavyBg,
        topBar = {
            TopAppBarHeader(isRunning = isRunning, isPaused = isPaused)
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Status Card
            item {
                HeroStatusCard(
                    isRunning = isRunning,
                    isPaused = isPaused,
                    canStart = canStart,
                    onStartClick = {
                        if (!hasOverlayPermission) {
                            Toast.makeText(context, "Please grant Overlay permission first", Toast.LENGTH_SHORT).show()
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayLauncher.launch(intent)
                            return@HeroStatusCard
                        }
                        if (!hasAudioPermission) {
                            Toast.makeText(context, "Please allow Audio permission first", Toast.LENGTH_SHORT).show()
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@HeroStatusCard
                        }
                        if (!isModelDownloaded) {
                            Toast.makeText(context, "Please download the Arabic model first", Toast.LENGTH_SHORT).show()
                            return@HeroStatusCard
                        }
                        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                        mediaProjectionLauncher.launch(captureIntent)
                    },
                    onStopClick = onStopCapture,
                    onPauseClick = onTogglePause
                )
            }

            // Real-time Translation Live Preview & Audio Level
            item {
                LiveTranslationPreviewCard(
                    isRunning = isRunning,
                    lastEnglish = lastEnglish,
                    lastArabic = lastArabic,
                    audioLevel = audioLevel
                )
            }

            // Model Download Card
            item {
                ModelDownloadCard(
                    isDownloaded = isModelDownloaded,
                    isDownloading = isDownloadingModel,
                    onDownloadClick = {
                        coroutineScope.launch {
                            isDownloadingModel = true
                            val result = translator.downloadModel()
                            isDownloadingModel = false
                            if (result.isSuccess) {
                                isModelDownloaded = true
                                Toast.makeText(context, "Arabic model downloaded successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Model download failed. Check internet.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // Permission Checklist
            item {
                PermissionChecklistCard(
                    hasOverlay = hasOverlayPermission,
                    hasAudio = hasAudioPermission,
                    hasNotification = hasNotificationPermission,
                    onRequestOverlay = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayLauncher.launch(intent)
                    },
                    onRequestAudio = {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            // Built-in Interactive Subtitle Test Simulator
            item {
                SubtitleSimulatorCard()
            }

            // User Guide & Tips
            item {
                GuideCard()
            }

            // Quick Settings Tile Shortcut Card
            item {
                QuickSettingsTileCard()
            }

            // Bottom spacer for navigation insets
            item {
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
fun TopAppBarHeader(isRunning: Boolean, isPaused: Boolean) {
    val statusColor by animateColorAsState(
        targetValue = when {
            isRunning && !isPaused -> StatusGreen
            isRunning && isPaused -> StatusAmber
            else -> TextMuted
        },
        label = "status_color"
    )

    Surface(
        color = DarkNavySurface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(CyanPrimary, SkyAccent))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "App Logo",
                        tint = DarkNavyBg,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "SubTranslate",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )
                    Text(
                        text = "Live Video Audio to Arabic Subtitles",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            // Status chip
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = statusColor.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isRunning && !isPaused -> "Active"
                            isRunning && isPaused -> "Paused"
                            else -> "Idle"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }
        }
    }
}

@Composable
fun HeroStatusCard(
    isRunning: Boolean,
    isPaused: Boolean,
    canStart: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkNavySurface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isRunning) CyanPrimary.copy(alpha = 0.5f) else Color(0xFF1E293B)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_status_card")
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isRunning) "Audio Translation Active" else "Ready to Translate",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isRunning)
                    "Capturing internal video playback audio, transcribing English speech, and displaying Arabic subtitles in the floating center-bottom bubble."
                else
                    "Plays seamlessly over YouTube, Netflix, or media players. Captures internal audio via AudioPlaybackCapture, transcribing and translating speech into Arabic subtitles.",
                fontSize = 13.sp,
                color = TextMuted,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            if (!isRunning) {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_translator_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        tint = DarkNavyBg,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start Audio Subtitle Translator",
                        color = DarkNavyBg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPauseClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pause_resume_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaused) StatusGreen else StatusAmber
                        )
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Pause/Resume",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPaused) "Resume" else "Pause",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onStopClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("stop_translator_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Stop",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTranslationPreviewCard(
    isRunning: Boolean,
    lastEnglish: String,
    lastArabic: String,
    audioLevel: Float
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkNavySurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Audio Level",
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Live Audio & Subtitle Monitor",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkyAccent
                    )
                }
                if (isRunning) {
                    Text(
                        text = "● Audio Capture Active",
                        fontSize = 11.sp,
                        color = StatusGreen
                    )
                }
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Audio Activity:",
                        fontSize = 11.sp,
                        color = TextMuted,
                        modifier = Modifier.width(85.dp)
                    )
                    LinearProgressIndicator(
                        progress = { audioLevel },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = CyanPrimary,
                        trackColor = Color(0xFF1E293B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // English speech recognized box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Transcribed English Speech:",
                    fontSize = 11.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (lastEnglish.isNotBlank()) lastEnglish else if (isRunning) "Listening to video speech..." else "Service inactive",
                    fontSize = 14.sp,
                    fontStyle = FontStyle.Italic,
                    color = if (lastEnglish.isNotBlank()) TextLight else TextMuted
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Arabic translated box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Arabic Translation (الترجمة الفورية):",
                    fontSize = 11.sp,
                    color = CyanPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (lastArabic.isNotBlank()) lastArabic else if (isRunning) "في انتظار الكلام الإنجليزي..." else "غير نشط",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ModelDownloadCard(
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkNavySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Model",
                        tint = if (isDownloaded) StatusGreen else CyanPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Offline Arabic Translation Model",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                        Text(
                            text = if (isDownloaded) "Downloaded & Ready (~30MB)" else "Required for zero-latency offline translation",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isDownloading) {
                Column {
                    Text(
                        text = "Downloading Google ML Kit model...",
                        fontSize = 12.sp,
                        color = CyanPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanPrimary,
                        trackColor = Color(0xFF1E293B)
                    )
                }
            } else if (!isDownloaded) {
                Button(
                    onClick = onDownloadClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    modifier = Modifier.fillMaxWidth().testTag("download_model_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Download",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Download Model (One-Time)", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = StatusGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Model installed and verified for instant offline translation",
                        fontSize = 12.sp,
                        color = StatusGreen
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionChecklistCard(
    hasOverlay: Boolean,
    hasAudio: Boolean,
    hasNotification: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAudio: () -> Unit,
    onRequestNotification: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkNavySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Required Permissions",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = SkyAccent
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 1. Overlay Permission
            PermissionItem(
                title = "Display Over Other Apps (Overlay)",
                description = "Enables floating pass-through Arabic subtitle bubble",
                isGranted = hasOverlay,
                icon = Icons.Default.Layers,
                onRequest = onRequestOverlay
            )

            HorizontalDivider(
                color = Color(0xFF1E293B),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            // 2. Audio Permission
            PermissionItem(
                title = "Internal Audio & Speech Capture",
                description = "Allows capturing media playback audio and transcribing speech",
                isGranted = hasAudio,
                icon = Icons.Default.Mic,
                onRequest = onRequestAudio
            )

            HorizontalDivider(
                color = Color(0xFF1E293B),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            // 3. Notification Permission
            PermissionItem(
                title = "Foreground Notification",
                description = "Keeps audio translation service active while you watch videos",
                isGranted = hasNotification,
                icon = Icons.Default.Notifications,
                onRequest = onRequestNotification
            )

            HorizontalDivider(
                color = Color(0xFF1E293B),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            // 4. MediaProjection Audio Capture Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ScreenShare,
                    contentDescription = "Audio Playback Capture",
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Audio Playback Capture (MediaProjection)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
                    Text(
                        text = "Requested automatically when you tap 'Start Audio Subtitle Translator'",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: ImageVector,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isGranted) StatusGreen else StatusAmber,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextLight
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = StatusGreen,
                modifier = Modifier.size(20.dp)
            )
        } else {
            OutlinedButton(
                onClick = onRequest,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary)
            ) {
                Text(text = "Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Built-in Subtitle Simulator allows testing without opening external video players.
 */
@Composable
fun SubtitleSimulatorCard() {
    val sampleSubtitles = listOf(
        "I'm going to make him an offer he can't refuse.",
        "May the Force be with you, always and everywhere.",
        "Here's looking at you, kid.",
        "Life is like a box of chocolates, you never know what you're gonna get.",
        "Houston, we have a problem.",
        "It's not who I am underneath, but what I do that defines me.",
        "Elementary, my dear Watson."
    )

    var currentSubtitleIndex by remember { mutableIntStateOf(0) }
    var isSimulating by remember { mutableStateOf(false) }

    LaunchedEffect(isSimulating) {
        while (isSimulating) {
            delay(3500)
            currentSubtitleIndex = (currentSubtitleIndex + 1) % sampleSubtitles.size
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkNavySurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Built-in Dialogue Test Simulator",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                    Text(
                        text = "Simulates movie dialogue so you can test audio & subtitles here",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                Button(
                    onClick = { isSimulating = !isSimulating },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSimulating) StatusAmber else CyanPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isSimulating) "Pause" else "Test Play",
                        color = DarkNavyBg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Simulated Video Player Display Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF030712))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = "CINEMATIC PLAYER AUDIO SIMULATOR",
                    fontSize = 11.sp,
                    color = Color(0xFF334155),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Subtitle dialogue line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sampleSubtitles[currentSubtitleIndex],
                        color = Color(0xFFFFFF00),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tip: When SubTranslate is running, the translation bubble appears centered at the bottom of the screen with touch pass-through!",
                fontSize = 11.sp,
                color = SkyAccent,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

@Composable
fun GuideCard() {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkNavySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = "Help",
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "How to Use SubTranslate",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )
                }
                Text(
                    text = if (isExpanded) "Hide" else "Show",
                    fontSize = 12.sp,
                    color = SkyAccent
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    GuideStep(number = "1", title = "Grant Permissions", desc = "Allow Overlay, Audio Recording, and Foreground Notifications.")
                    GuideStep(number = "2", title = "Download Model", desc = "Download the offline Google ML Kit model (~30MB) once.")
                    GuideStep(number = "3", title = "Tap Start Translator", desc = "Tap Start to launch the foreground audio translation service.")
                    GuideStep(number = "4", title = "Play Any Video", desc = "Open YouTube, Netflix, or media player. English speech is transcribed and translated directly to Arabic at the bottom center of the screen!")
                    GuideStep(number = "5", title = "Floating Edge Widget", desc = "Use the tiny draggable icon on the screen edge to pause or close anytime.")
                }
            }
        }
    }
}

@Composable
fun GuideStep(number: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(CyanPrimary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = CyanPrimary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextLight)
            Text(text = desc, fontSize = 11.sp, color = TextMuted)
        }
    }
}

@Composable
fun QuickSettingsTileCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkNavySurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Quick Settings",
                    tint = CyanPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Quick Settings Tile Available",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextLight
                )
                Text(
                    text = "Swipe down from top of the screen over any foreign video to toggle SubTranslate instantly without leaving your app.",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }
    }
}
