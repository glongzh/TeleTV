package com.teletv.media.search

/**
 * Pure ordered-subsequence matcher: every character of [query] must occur in
 * [candidate] in order, not necessarily contiguously. Case/width folding is the
 * caller's responsibility (see [SearchIndex]) so this stays a plain string
 * algorithm, testable with whatever casing a test wants to exercise directly.
 */
object FuzzyMatcher {

    /**
     * Null when [query] does not occur as an ordered subsequence of [candidate].
     * Otherwise a score where higher ranks better: matches that land as
     * contiguous runs score above scattered ones, and an earlier first match
     * outranks a later one, so a tight prefix match floats to the top.
     */
    fun score(query: String, candidate: String): Int? {
        if (query.isEmpty() || candidate.isEmpty()) return null
        var qi = 0
        var lastMatchIndex = -1
        var firstMatchIndex = -1
        var contiguityBonus = 0
        var ci = 0
        while (ci < candidate.length && qi < query.length) {
            if (candidate[ci] == query[qi]) {
                if (firstMatchIndex < 0) firstMatchIndex = ci
                if (lastMatchIndex == ci - 1) contiguityBonus++
                lastMatchIndex = ci
                qi++
            }
            ci++
        }
        if (qi < query.length) return null
        return contiguityBonus * 100 - firstMatchIndex
    }
}
