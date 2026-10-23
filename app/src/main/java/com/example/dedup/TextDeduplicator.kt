package com.example.dedup

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Smart Deduplication and Subtitle Filtering Engine.
 * Prevents UI flickering and redundant translation calls by comparing text similarity
 * and filtering out non-dialogue noise, URLs, timestamps, and watermarks.
 */
object TextDeduplicator {

    // Threshold above which text is considered unchanged (0.85 = 85% similarity)
    private const val SIMILARITY_THRESHOLD = 0.85

    // RegEx patterns for common on-screen watermarks, URLs, and timestamps
    private val URL_REGEX = Regex(
        "(?i)\\b(https?://|www\\.|ftp://|[a-z0-9.-]+\\.(com|org|net|io|me|tv|cc|to|xxx|info|biz|ru|cn))\\b"
    )
    private val TIMESTAMP_REGEX = Regex("^(\\d{1,2}:)?\\d{2}:\\d{2}([,.]\\d{1,3})?$")
    private val WATERMARK_KEYWORDS = listOf(
        "subtitles by", "translated by", "ripped by", "yify", "yts",
        "rarbg", "subscene", "addic7ed", "opensubtitles", "synced by"
    )

    /**
     * Cleans OCR raw text and filters out noise.
     * Returns null if the text is empty or non-dialogue noise.
     */
    fun cleanAndFilter(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        // 1. Remove stray OCR artifacts like pipes, tildes, carats
        var cleaned = rawText
            .replace(Regex("[|~^_\\[\\]{}<>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 2. Reject if too short (e.g. single character noise)
        if (cleaned.length < 2) return null

        // 3. Reject if text contains URLs or domains
        if (URL_REGEX.containsMatchIn(cleaned)) return null

        // 4. Reject if it is solely a timestamp (e.g. 00:14:22)
        if (TIMESTAMP_REGEX.matches(cleaned)) return null

        // 5. Reject if it contains common release group/ripper watermark signatures
        val lower = cleaned.lowercase(Locale.ROOT)
        for (keyword in WATERMARK_KEYWORDS) {
            if (lower.contains(keyword)) return null
        }

        // 6. Must contain at least one letter
        val hasLetter = cleaned.any { it.isLetter() }
        if (!hasLetter) return null

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
