package com.teletv.media.search

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinIndexTest {

    @Test
    fun `CJK characters are replaced by the injected lookup`() {
        val lookup: (Char) -> Char? = { c -> if (c == '张') 'z' else if (c == '国') 'g' else null }
        assertEquals("zg", PinyinIndex.initialsOf("张国", lookup))
    }

    @Test
    fun `non-CJK characters pass through lowercased`() {
        assertEquals("abc123", PinyinIndex.initialsOf("ABC123", lookup = { null }))
    }

    @Test
    fun `mixed CJK and latin text combines both`() {
        val lookup: (Char) -> Char? = { c -> if (c == '张') 'z' else null }
        assertEquals("zabc", PinyinIndex.initialsOf("张ABC", lookup))
    }

    @Test
    fun `punctuation passes through unchanged`() {
        assertEquals("a-b!", PinyinIndex.initialsOf("A-B!", lookup = { null }))
    }

    @Test
    fun `empty string yields empty result`() {
        assertEquals("", PinyinIndex.initialsOf("", lookup = { null }))
    }

    @Test
    fun `unresolvable CJK character is dropped rather than guessed at`() {
        assertEquals("xy", PinyinIndex.initialsOf("x张y", lookup = { null }))
    }

    @Test
    fun `default lookup degrades gracefully when ICU is unavailable in this test host`() {
        // No Robolectric here: Build.VERSION.SDK_INT reads as the stub default
        // (0) on a plain JUnit host, so the real ICU path is never reached and
        // CJK characters are dropped — the same graceful-degradation behavior
        // this exercises for pre-API-24 devices, just triggered a different way.
        assertEquals("abc", PinyinIndex.initialsOf("张ABC国"))
    }
}
