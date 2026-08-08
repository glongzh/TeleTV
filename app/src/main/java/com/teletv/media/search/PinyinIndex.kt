package com.teletv.media.search

import android.icu.text.Transliterator
import android.os.Build

/**
 * Pinyin-initials rendering of CJK text, used so a latin-only query (typed on a
 * remote/soft keyboard) can match a CJK title. Uses the OS's built-in ICU
 * `Han-Latin` transliterator (framework API since API 24) rather than a new
 * dependency or a hand-maintained character table — see design.md D2.
 *
 * Below API 24, or for a character ICU can't resolve, that character is simply
 * dropped from the initials string rather than guessed at: it only narrows this
 * one representation, normalized-text matching is untouched.
 */
object PinyinIndex {

    private val cache = HashMap<Char, Char?>()

    /**
     * Non-CJK characters pass through lowercased; each CJK character becomes its
     * pinyin initial (or is dropped if unresolvable). [lookup] is injectable so
     * the surrounding logic is unit-testable without invoking real ICU code,
     * which plain JUnit (no Robolectric here) cannot execute.
     */
    fun initialsOf(text: String, lookup: (Char) -> Char? = ::initialOf): String = buildString(text.length) {
        for (c in text) {
            if (isCjk(c)) {
                lookup(c)?.let { append(it) }
            } else {
                append(c.lowercaseChar())
            }
        }
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }

    private val transliterator: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull()
        } else {
            null
        }
    }

    /**
     * Not `cache.getOrPut`: with a nullable value type, `Map.get` can't tell
     * "cached as unresolvable" apart from "never looked up", so `getOrPut` would
     * re-run the ICU lookup on every occurrence of a character it fails to
     * resolve instead of caching that outcome too.
     */
    private fun initialOf(c: Char): Char? {
        if (cache.containsKey(c)) return cache[c]
        val result = computeInitial(c)
        cache[c] = result
        return result
    }

    private fun computeInitial(c: Char): Char? {
        val t = transliterator ?: return null
        val syllable = runCatching { t.transliterate(c.toString()) }.getOrNull() ?: return null
        // The first ASCII letter of the transliterated syllable is the pinyin
        // initial regardless of whether ICU marks tone with a trailing digit or
        // a combining diacritic. If ICU couldn't transliterate this character it
        // returns it unchanged, and a bare Han character is not an ASCII letter,
        // so it correctly falls through to null rather than polluting the
        // initials string with the original CJK character.
        return syllable.firstOrNull { it in 'a'..'z' || it in 'A'..'Z' }?.lowercaseChar()
    }
}
