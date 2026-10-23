package com.example

import com.example.dedup.TextDeduplicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDeduplicatorTest {

    @Test
    fun testValidDialogueCleaned() {
        val input = "   I'm going to make him an offer he can't refuse.   "
        val cleaned = TextDeduplicator.cleanAndFilter(input)
        assertNotNull(cleaned)
        assertEquals("I'm going to make him an offer he can't refuse.", cleaned)
    }

    @Test
    fun testDialogueWithDashOrQuoteAccepted() {
        val input1 = "- Yes, of course."
        val cleaned1 = TextDeduplicator.cleanAndFilter(input1)
        assertNotNull(cleaned1)

        val input2 = "\"Wait!\""
        val cleaned2 = TextDeduplicator.cleanAndFilter(input2)
        assertNotNull(cleaned2)
    }

    @Test
    fun testNoiseCharactersStripped() {
        val input = "| May the [Force] be with you ~ ^"
        val cleaned = TextDeduplicator.cleanAndFilter(input)
        assertNotNull(cleaned)
        assertEquals("May the Force be with you", cleaned)
    }

    @Test
    fun testUrlFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("Download subtitles from www.subscene.com"))
        assertNull(TextDeduplicator.cleanAndFilter("Check out https://movies.org/sub"))
        assertNull(TextDeduplicator.cleanAndFilter("stream.xxx/video"))
    }

    @Test
    fun testTimecodeFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("25:08"))
        assertNull(TextDeduplicator.cleanAndFilter("01:30:12"))
        assertNull(TextDeduplicator.cleanAndFilter("Time: 12:45 / 45:00"))
        assertNull(TextDeduplicator.cleanAndFilter("Scene 4 25:08 remaining"))
    }

    @Test
    fun testWatermarkFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("Subtitles by YIFY 1080p"))
        assertNull(TextDeduplicator.cleanAndFilter("Ripped by RARBG x264"))
        assertNull(TextDeduplicator.cleanAndFilter("Subscribe for more content"))
    }

    @Test
    fun testArabicPreventSelfCapture() {
        // Must reject any text containing Arabic characters to prevent self-capturing the translation bubble
        assertNull(TextDeduplicator.cleanAndFilter("الترجمة الفورية"))
        assertNull(TextDeduplicator.cleanAndFilter("مرحبا بك في الفيلم"))
        assertNull(TextDeduplicator.cleanAndFilter("Sub: الترجمة"))
    }

    @Test
    fun testAppUiTextFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("SubTranslate Active"))
        assertNull(TextDeduplicator.cleanAndFilter("Translation Paused"))
        assertNull(TextDeduplicator.cleanAndFilter("Live Subtitle Monitor"))
    }

    @Test
    fun testNumbersAndShortNoiseFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("."))
        assertNull(TextDeduplicator.cleanAndFilter("a"))
        assertNull(TextDeduplicator.cleanAndFilter("   "))
        assertNull(TextDeduplicator.cleanAndFilter("1080 60 480"))
    }

    @Test
    fun testLevenshteinDistance() {
        assertEquals(0, TextDeduplicator.levenshteinDistance("hello", "hello"))
        assertEquals(1, TextDeduplicator.levenshteinDistance("hello", "hella"))
        assertEquals(3, TextDeduplicator.levenshteinDistance("kitten", "sitting"))
    }

    @Test
    fun testDeduplicationSimilarity() {
        val s1 = "Houston, we have a problem"
        val s2 = "Houston, we have a problem."
        assertFalse(TextDeduplicator.isSignificantlyDifferent(s2, s1))

        val s3 = "May the force be with you"
        assertTrue(TextDeduplicator.isSignificantlyDifferent(s3, s1))
    }
}
