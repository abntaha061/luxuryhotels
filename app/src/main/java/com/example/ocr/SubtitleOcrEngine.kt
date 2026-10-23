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
 * Extracts dialogue sentences and calculates their bounding boxes for dynamic overlay positioning.
 */
class SubtitleOcrEngine {

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Scans the full-screen bitmap, filters out UI noise and watermarks,
     * and detects the primary English subtitle sentence along with its bounding box.
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

            var bestCandidate: DetectedSubtitle? = null
            var bestScore = -1.0

            for (block in visionText.textBlocks) {
                val box = block.boundingBox ?: continue

                // 1. Filter out top status bar and extreme bottom nav bar
                if (box.top < 80 || box.bottom > screenHeight - 50) continue

                // 2. Clean and filter raw text
                val rawText = block.text.trim()
                val cleaned = TextDeduplicator.cleanAndFilter(rawText) ?: continue

                // 3. Filter noise: Must have at least 2 words (or dialogue quotation/punctuation)
                val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
                val isDialogueFormat = cleaned.startsWith("-") || cleaned.startsWith("\"") ||
                        cleaned.endsWith("?") || cleaned.endsWith("!")
                if (words.size < 2 && !isDialogueFormat) {
                    continue
                }

                // 4. Subtitle scoring: Subtitles prominently appear in middle-to-lower portion of the screen
                val centerYRatio = box.centerY().toDouble() / screenHeight.toDouble()
                if (centerYRatio < 0.35) {
                    // Less likely to be video subtitle if in top 35% of the screen
                    continue
                }

                val verticalPositionScore = centerYRatio * 150.0 // Higher score for lower screen position
                val widthScore = (box.width().toDouble() / screenWidth.toDouble()) * 100.0
                val wordScore = words.size * 15.0

                val totalScore = verticalPositionScore + widthScore + wordScore

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
