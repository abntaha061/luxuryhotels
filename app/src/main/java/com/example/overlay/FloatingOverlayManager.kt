package com.example.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.R
import kotlin.math.abs
import kotlin.math.max

/**
 * Manages floating overlay windows with bounding box stabilization:
 * 1. Pass-Through Translation Bubble with vertical jitter damping (stops random jumping)
 * 2. Tiny Draggable Floating Edge Button (Single tap to Pause/Resume, Long press to Close)
 */
class FloatingOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onTogglePause: () -> Unit,
    private val onCloseService: () -> Unit
) {

    private var bubbleView: View? = null
    private var toggleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var toggleParams: WindowManager.LayoutParams? = null

    private var isManuallyHidden = false

    // Bounding Box Stabilization to eliminate jitter and erratic jumping
    private var lastStableX: Int? = null
    private var lastStableY: Int? = null
    private val jitterThresholdXPx = 24
    private val jitterThresholdYPx = 28

    fun showOverlays() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val inflater = LayoutInflater.from(context)

        // 1. Pass-Through Translation Bubble (Zero interference with video player touches)
        bubbleView = inflater.inflate(R.layout.layout_translation_bubble, null)
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 4
            y = (screenHeight * 0.78).toInt()
        }

        bubbleView?.visibility = View.GONE // Silent until English subtitles appear
        windowManager.addView(bubbleView, bubbleParams)

        // 2. Tiny Draggable Floating Edge Toggle Button
        toggleView = inflater.inflate(R.layout.layout_floating_toggle, null)
        toggleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 140
            y = (screenHeight * 0.30).toInt()
        }

        windowManager.addView(toggleView, toggleParams)
        attachToggleListeners()
    }

    private fun attachToggleListeners() {
        val view = toggleView ?: return
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        view.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchDownX = 0f
            private var touchDownY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val params = toggleParams ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        touchDownX = event.rawX
                        touchDownY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.rawX - touchDownX)
                        val dy = abs(event.rawY - touchDownY)
                        if (dx > touchSlop || dy > touchSlop) {
                            isDragging = true
                            params.x = (initX + (event.rawX - touchDownX)).toInt()
                            params.y = (initY + (event.rawY - touchDownY)).toInt()
                            windowManager.updateViewLayout(toggleView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            onTogglePause()
                        }
                        return true
                    }
                }
                return false
            }
        })

        view.setOnLongClickListener {
            Toast.makeText(context, "Stopping SubTranslate...", Toast.LENGTH_SHORT).show()
            onCloseService()
            true
        }
    }

    /**
     * Positions the rounded rectangular floating bubble directly below the English text
     * with hysteresis stabilization to stop random vertical jumping.
     */
    fun positionBubbleBelowText(
        detectedBox: Rect,
        arabicText: String,
        englishText: String,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return

        val tvArabic = view.findViewById<TextView>(R.id.tvArabicSubtitle)
        val tvEnglish = view.findViewById<TextView>(R.id.tvEnglishOriginal)

        tvArabic?.text = arabicText
        tvEnglish?.text = englishText

        // Map bitmap coordinates to display screen metrics
        val scaleX = screenWidth.toFloat() / max(1, bitmapWidth).toFloat()
        val scaleY = screenHeight.toFloat() / max(1, bitmapHeight).toFloat()
        val screenBox = Rect(
            (detectedBox.left * scaleX).toInt(),
            (detectedBox.top * scaleY).toInt(),
            (detectedBox.right * scaleX).toInt(),
            (detectedBox.bottom * scaleY).toInt()
        )

        // Measure bubble dimensions
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth - 48, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bubbleW = view.measuredWidth.coerceAtLeast(180)
        val bubbleH = view.measuredHeight.coerceAtLeast(60)

        // Center horizontally with detected subtitle text
        val rawTargetX = (screenBox.centerX() - (bubbleW / 2)).coerceIn(16, max(16, screenWidth - bubbleW - 16))

        // Position directly below text with 8dp offset
        val verticalOffsetPx = (8 * context.resources.displayMetrics.density).toInt()
        var rawTargetY = screenBox.bottom + verticalOffsetPx

        // Flip above if too close to navigation bar or screen bottom
        if (rawTargetY + bubbleH > screenHeight - 60) {
            rawTargetY = max(80, screenBox.top - bubbleH - verticalOffsetPx)
        }

        // Bounding Box Stabilization: Damping small pixel fluctuations to stop random jumping
        val stableX = if (lastStableX != null && abs(rawTargetX - lastStableX!!) <= jitterThresholdXPx) {
            lastStableX!!
        } else {
            lastStableX = rawTargetX
            rawTargetX
        }

        val stableY = if (lastStableY != null && abs(rawTargetY - lastStableY!!) <= jitterThresholdYPx) {
            lastStableY!!
        } else {
            lastStableY = rawTargetY
            rawTargetY
        }

        val needsLayoutUpdate = params.x != stableX || params.y != stableY || view.visibility != View.VISIBLE

        params.x = stableX
        params.y = stableY

        if (!isManuallyHidden) {
            view.visibility = View.VISIBLE
        }

        if (needsLayoutUpdate) {
            windowManager.updateViewLayout(view, params)
        }
    }

    /**
     * Hides the translation bubble when no English subtitles are on screen (Smart Auto-Sleep).
     */
    fun hideBubble() {
        bubbleView?.visibility = View.GONE
        lastStableX = null
        lastStableY = null
    }

    fun setPausedUIState(isPaused: Boolean) {
        val ivIcon = toggleView?.findViewById<ImageView>(R.id.ivToggleIcon)
        val dot = toggleView?.findViewById<View>(R.id.viewToggleStatusDot)

        ivIcon?.setImageResource(if (isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        dot?.setBackgroundColor(
            if (isPaused) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
        )

        if (isPaused) {
            hideBubble()
        }
    }

    fun removeOverlays() {
        try {
            bubbleView?.let { windowManager.removeView(it) }
            bubbleView = null
            toggleView?.let { windowManager.removeView(it) }
            toggleView = null
        } catch (_: Exception) {
            // View may already be detached
        }
    }
}
