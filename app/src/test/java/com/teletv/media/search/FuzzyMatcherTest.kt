package com.teletv.media.search

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {

    @Test
    fun `exact substring matches`() {
        assertNotNull(FuzzyMatcher.score("abc", "xxabcxx"))
    }

    @Test
    fun `ordered but scattered characters still match`() {
        assertNotNull(FuzzyMatcher.score("ac", "axxcxx"))
    }

    @Test
    fun `out of order characters do not match`() {
        assertNull(FuzzyMatcher.score("ba", "abxx"))
    }

    @Test
    fun `missing character does not match`() {
        assertNull(FuzzyMatcher.score("abz", "abx"))
    }

    @Test
    fun `empty query or candidate does not match`() {
        assertNull(FuzzyMatcher.score("", "abc"))
        assertNull(FuzzyMatcher.score("abc", ""))
    }

    @Test
    fun `contiguous match outranks a scattered match`() {
        val tight = FuzzyMatcher.score("ac", "acxxxx")!!
        val scattered = FuzzyMatcher.score("ac", "axxxxc")!!
        assertTrue(tight > scattered)
    }

    @Test
    fun `earlier first match outranks a later one at equal contiguity`() {
        val early = FuzzyMatcher.score("ab", "abxxxx")!!
        val late = FuzzyMatcher.score("ab", "xxxxab")!!
        assertTrue(early > late)
    }
}
