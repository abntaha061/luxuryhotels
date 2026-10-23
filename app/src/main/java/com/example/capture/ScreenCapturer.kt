package com.example.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Manages MediaProjection, VirtualDisplay, and ImageReader for low-latency,
 * efficient screen region capturing and full-frame bitmap acquisition.
 */
class ScreenCapturer(
    private val mediaProjection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensityDpi: Int
) {
    companion object {
        private const val TAG = "ScreenCapturer"
        private const val VIRTUAL_DISPLAY_NAME = "SubTranslate_CaptureDisplay"
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    init {
        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        try {
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )
            Log.d(TAG, "VirtualDisplay created: ${screenWidth}x${screenHeight} @ ${screenDensityDpi}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
        }
    }

    /**
     * Captures and returns the full-screen Bitmap frame for OCR detection and boundingBox calculation.
     */
    suspend fun captureFullScreen(): Bitmap? = withContext(Dispatchers.Default) {
        val reader = imageReader ?: return@withContext null

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return@withContext null
            val planes = image.planes
            if (planes.isEmpty()) return@withContext null

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullWidth = screenWidth + (rowPadding / pixelStride)
            val fullBitmap = Bitmap.createBitmap(
                fullWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            if (fullWidth > screenWidth) {
                val exactBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    0,
                    screenWidth,
                    screenHeight
                )
                fullBitmap.recycle()
                return@withContext exactBitmap
            }

            return@withContext fullBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing full screen frame", e)
            return@withContext null
        } finally {
            image?.close()
        }
    }

    /**
     * Captures and returns a cropped Bitmap corresponding to the screen coordinates [targetRect].
     */
    suspend fun captureCroppedRegion(targetRect: Rect): Bitmap? = withContext(Dispatchers.Default) {
        val fullBitmap = captureFullScreen() ?: return@withContext null

        try {
            val left = max(0, min(targetRect.left, screenWidth - 1))
            val top = max(0, min(targetRect.top, screenHeight - 1))
            val right = max(left + 1, min(targetRect.right, screenWidth))
            val bottom = max(top + 1, min(targetRect.bottom, screenHeight))

            val cropWidth = right - left
            val cropHeight = bottom - top

            if (cropWidth <= 0 || cropHeight <= 0) {
                fullBitmap.recycle()
                return@withContext null
            }

            val croppedBitmap = Bitmap.createBitmap(
                fullBitmap,
                left,
                top,
                cropWidth,
                cropHeight
            )
            fullBitmap.recycle()
            return@withContext croppedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping region", e)
            fullBitmap.recycle()
            return@withContext null
        }
    }

    /**
     * Releases VirtualDisplay and ImageReader resources.
     */
    fun release() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection.stop()
            Log.d(TAG, "ScreenCapturer released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ScreenCapturer", e)
        }
    }
}
