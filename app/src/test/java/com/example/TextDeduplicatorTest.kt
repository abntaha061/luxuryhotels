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
        val input = "   I'm going to make him an offer...   "
        val cleaned = TextDeduplicator.cleanAndFilter(input)
        assertNotNull(cleaned)
        assertEquals("I'm going to make him an offer...", cleaned)
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
        val input1 = "Download subtitles from www.subscene.com"
        assertNull(TextDeduplicator.cleanAndFilter(input1))

        val input2 = "Check out https://movies.org/sub"
        assertNull(TextDeduplicator.cleanAndFilter(input2))
    }

    @Test
    fun testTimestampFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("00:12:45"))
        assertNull(TextDeduplicator.cleanAndFilter("01:30:12,450"))
    }

    @Test
    fun testWatermarkFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("Subtitles by YIFY"))
        assertNull(TextDeduplicator.cleanAndFilter("Ripped by RARBG"))
    }

    @Test
    fun testShortNoiseFiltered() {
        assertNull(TextDeduplicator.cleanAndFilter("."))
        assertNull(TextDeduplicator.cleanAndFilter("a"))
        assertNull(TextDeduplicator.cleanAndFilter("   "))
    }

    @Test
    fun testLevenshteinDistance() {
        assertEquals(0, TextDeduplicator.levenshteinDistance("hello", "hello"))
        assertEquals(1, TextDeduplicator.levenshteinDistance("hello", "hella"))
        assertEquals(3, TextDeduplicator.levenshteinDistance("kitten", "sitting"))
    }

    @Test
    fun testDeduplicationSimilarity() {
        // High similarity (>85%) should NOT trigger new translation (returns false for significantly different)
        val s1 = "Houston, we have a problem"
        val s2 = "Houston, we have a problem."
        assertFalse(TextDeduplicator.isSignificantlyDifferent(s2, s1))

        // Different dialogue sentence SHOULD trigger new translation (returns true)
        val s3 = "May the force be with you"
        assertTrue(TextDeduplicator.isSignificantlyDifferent(s3, s1))
    }
}
