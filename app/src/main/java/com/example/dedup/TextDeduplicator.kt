package com.example.dedup

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Strict Subtitle Filtering & Anti-Flicker Deduplication Engine.
 * Implements aggressive noise, watermark, player timecode, and self-capture filters.
 */
object TextDeduplicator {

    // Threshold above which text is considered unchanged (0.85 = 85% similarity)
    private const val SIMILARITY_THRESHOLD = 0.85

    // 1. Arabic Unicode regex to detect and reject app's own floating translated bubble (prevents self-capture loop)
    private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

    // 2. RegEx for player timecodes anywhere in string (e.g., "25:08", "01:25:08", "12:30 / 45:00", "-03:15")
    private val TIMECODE_REGEX = Regex("(?i)(\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b|\\d{1,2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2})")

    // 3. RegEx for URLs, domains, and watermarks
    private val URL_REGEX = Regex(
        "(?i)\\b(https?://|www\\.|ftp://|[a-z0-9.-]+\\.(com|org|net|io|me|tv|cc|to|xxx|info|biz|ru|cn|gg|co|xyz))\\b"
    )

    // 4. Common video release groups, codec tags, and subtitle site signatures
    private val WATERMARK_KEYWORDS = listOf(
        "subtitles by", "translated by", "ripped by", "yify", "yts",
        "rarbg", "subscene", "addic7ed", "opensubtitles", "synced by",
        "captioned by", "1080p", "720p", "bluray", "web-dl", "x264", "x265",
        "subscribe", "like and share", "all rights reserved"
    )

    // 5. App's own UI text strings to prevent capturing app controls or toasts
    private val APP_UI_KEYWORDS = listOf(
        "subtranslate", "translating", "paused", "ready to translate",
        "auto-tracking", "tap to launch", "live subtitle monitor",
        "detected english", "arabic translation", "quick settings"
    )

    /**
     * Strictly cleans OCR raw text and filters out noise, watermarks, timecodes,
     * self-captured Arabic text, and player UI artifacts.
     * Returns null if the text should be rejected.
     */
    fun cleanAndFilter(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        // 1. CRITICAL: Reject if text contains any Arabic characters (prevents self-capturing floating bubble)
        if (ARABIC_REGEX.containsMatchIn(rawText)) {
            return null
        }

        // 2. Reject if text contains player timecodes (e.g., "25:08")
        if (TIMECODE_REGEX.containsMatchIn(rawText)) {
            return null
        }

        // 3. Clean stray OCR artifacts
        var cleaned = rawText
            .replace(Regex("[|~^_\\[\\]{}<>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 4. Reject if too short
        if (cleaned.length < 3) return null

        // 5. Reject if text contains URLs or domain extensions
        if (URL_REGEX.containsMatchIn(cleaned)) return null

        val lower = cleaned.lowercase(Locale.ROOT)

        // 6. Reject watermarks & release groups
        for (keyword in WATERMARK_KEYWORDS) {
            if (lower.contains(keyword)) return null
        }

        // 7. Reject app's own UI text
        for (uiWord in APP_UI_KEYWORDS) {
            if (lower.contains(uiWord)) return null
        }

        // 8. Must contain at least one English alphabet letter
        val letterCount = cleaned.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (letterCount < 2) return null

        // 9. Reject if the block consists predominantly of numbers (e.g. player stats, fps, resolutions)
        val digitCount = cleaned.count { it.isDigit() }
        if (digitCount >= letterCount) return null

        // 10. Must contain at least 2 words unless formatted as direct dialogue (quotes or dialogue dash)
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val isDialogueFormat = cleaned.startsWith("-") || cleaned.startsWith("\"") ||
                cleaned.endsWith("?") || cleaned.endsWith("!")
        if (words.size < 2 && !isDialogueFormat) {
            return null
        }

        return cleaned
    }

    /**
     * Computes similarity between [current] and [previous] text using Levenshtein distance.
     * Returns true if the change is significant enough to warrant re-translating.
     */
    fun isSignificantlyDifferent(current: String, previous: String?): Boolean {
        if (previous == null) return true

        val s1 = current.trim().lowercase(Locale.ROOT)
        val s2 = previous.trim().lowercase(Locale.ROOT)

        if (s1 == s2) return false

        val distance = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return false

        val similarity = 1.0 - (distance.toDouble() / maxLen.toDouble())
        return similarity < SIMILARITY_THRESHOLD
    }

    /**
     * Standard Levenshtein edit distance calculation.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
