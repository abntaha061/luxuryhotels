package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.dedup.TextDeduplicator
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encapsulates detected subtitle text alongside its screen bounding box.
 */
data class DetectedSubtitle(
    val text: String,
    val boundingBox: Rect
)

/**
 * Optical Character Recognition (OCR) Engine powered by Google ML Kit.
 * Implements STRICT spatial ROI filtering (bottom 35% of screen, center-aligned)
 * to ignore video watermarks, player headers, and corner timecodes.
 */
class SubtitleOcrEngine {

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Scans the full-screen bitmap and strictly extracts ONLY subtitles
     * in the bottom 35% of the screen, discarding top 65% watermarks,
     * timecodes, and edge icons.
     */
    suspend fun detectSubtitleSentence(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): Result<DetectedSubtitle?> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) {
            return@withContext Result.failure(IllegalStateException("Bitmap is recycled"))
        }

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(inputImage))

            if (visionText.textBlocks.isEmpty()) {
                return@withContext Result.success(null)
            }

            // 1. STRICT ROI: ONLY accept text in the bottom 35% of the screen (top >= 65% of screen height)
            val roiTopY = (screenHeight * 0.65f).toInt()
            val navBarBottomCutoff = screenHeight - 36

            // Subtitle horizontal center-alignment constraints
            val centerMinX = (screenWidth * 0.12f).toInt()
            val centerMaxX = (screenWidth * 0.88f).toInt()

            var bestCandidate: DetectedSubtitle? = null
            var bestScore = -1.0

            for (block in visionText.textBlocks) {
                val box = block.boundingBox ?: continue

                // STRICT ROI CHECK: Completely ignore any text in the top 65% of the screen!
                if (box.top < roiTopY) {
                    continue
                }

                // Ignore extreme bottom nav bar / gesture pill
                if (box.bottom > navBarBottomCutoff) {
                    continue
                }

                // Ignore text pushed to the extreme left/right margins (timecodes, player controls, share icons)
                if (box.centerX() < centerMinX || box.centerX() > centerMaxX) {
                    continue
                }

                // Must overlap with the central region of the screen
                if (box.right < screenWidth * 0.20f || box.left > screenWidth * 0.80f) {
                    continue
                }

                // Clean and apply text filters (Arabic self-capture, timecodes, URLs, watermarks, word count)
                val rawText = block.text.trim()
                val cleaned = TextDeduplicator.cleanAndFilter(rawText) ?: continue

                // Score candidate: favor lower placement and wider centered bounding boxes (typical of subtitles)
                val verticalScore = ((box.bottom - roiTopY).toDouble() / (screenHeight - roiTopY).toDouble()) * 100.0
                val widthScore = (box.width().toDouble() / screenWidth.toDouble()) * 120.0
                val wordScore = cleaned.split(Regex("\\s+")).size * 10.0

                val totalScore = verticalScore + widthScore + wordScore

                if (totalScore > bestScore) {
                    bestScore = totalScore
                    bestCandidate = DetectedSubtitle(cleaned, box)
                }
            }

            Result.success(bestCandidate)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Legacy crop-based recognition.
     */
    suspend fun recognizeText(bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) {
            return@withContext Result.failure(IllegalStateException("Bitmap is recycled"))
        }

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(inputImage))

            if (visionText.textBlocks.isEmpty()) {
                return@withContext Result.success("")
            }

            val sortedLines = visionText.textBlocks
                .flatMap { it.lines }
                .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                .map { it.text.trim() }
                .filter { it.isNotBlank() }

            val joinedText = sortedLines.joinToString(" ")
            Result.success(joinedText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        recognizer.close()
    }
}
