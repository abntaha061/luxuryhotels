package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Quick Settings TileService.
 * Allows toggling SubTranslate ON/OFF directly from the system Quick Settings panel
 * over any video app (YouTube, Netflix, VLC, browser, etc.).
 */
class TranslatorTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()

        // Observe service state changes and update the Quick Settings tile in real-time
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            ScreenTranslatorService.isRunning.collect {
                updateTileState()
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        stateObserverJob?.cancel()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = ScreenTranslatorService.isRunning.value

        if (isRunning) {
            // Stop service directly from Quick Settings
            val stopIntent = Intent(this, ScreenTranslatorService::class.java).apply {
                action = ScreenTranslatorService.ACTION_STOP
            }
            startService(stopIntent)
            updateTileState(forcedRunning = false)
        } else {
            // Screen capture requires user consent token via MediaProjection dialog
            // Launch MainActivity to automatically trigger capture flow and collapse notification shade
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_AUTO_START, true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
        }
    }

    private fun updateTileState(forcedRunning: Boolean? = null) {
        val tile = qsTile ?: return
        val isRunning = forcedRunning ?: ScreenTranslatorService.isRunning.value
        val isPaused = ScreenTranslatorService.isPaused.value

        when {
            isRunning && !isPaused -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "SubTranslate"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Translating"
                }
            }
            isRunning && isPaused -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "SubTranslate"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Paused"
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "SubTranslate"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to Launch"
                }
            }
        }

        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
