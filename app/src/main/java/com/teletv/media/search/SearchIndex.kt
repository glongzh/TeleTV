package com.teletv.media.search

import com.teletv.media.index.PartGroup
import com.teletv.media.index.TagExtractor
import com.teletv.media.index.TagIndexDao

/** One indexed item's searchable text, precomputed once per source. */
private data class SearchEntry(
    val messageId: Long,
    val date: Int,
    val normalizedText: String,
    val initials: String,
)

/**
 * Free-text search over a source's already-indexed raw text. No new persisted
 * schema: the corpus is built in memory, on demand, from
 * [TagIndexDao.messagesFor] — the same query the tag extractor already uses to
 * rebuild tags without re-paging Telegram (see design.md D1).
 */
class SearchIndex(private val dao: TagIndexDao) {

    private val corpusCache = HashMap<Long, List<SearchEntry>>()

    /** Drop a source's cached corpus so the next search rebuilds it from the index. */
    fun invalidate(chatId: Long) {
        corpusCache.remove(chatId)
    }

    /**
     * Matched message ids for [query] against [chatId]'s corpus, newest/most
     * relevant first, with split-video parts collapsed to one representative id
     * per group (mirrors [TagIndexDao.messageIdsForTag]'s collapsing). Returns an
     * empty list for a query shorter than [MIN_QUERY_LENGTH] — see design.md D3.
     */
    suspend fun search(chatId: Long, query: String, groups: Map<Long, PartGroup>): List<Long> {
        val normalizedQuery = TagExtractor.normalizeWidth(query).lowercase()
        if (normalizedQuery.length < MIN_QUERY_LENGTH) return emptyList()

        // Not MutableMap.getOrPut: its lambda parameter isn't suspend-aware, and
        // buildCorpus is a suspend call (it hits the database on a cache miss).
        val corpus = corpusCache[chatId] ?: buildCorpus(chatId).also { corpusCache[chatId] = it }
        data class Hit(val messageId: Long, val date: Int, val score: Int)

        val hits = corpus.mapNotNull { entry ->
            val textScore = FuzzyMatcher.score(normalizedQuery, entry.normalizedText)
            val initialsScore = FuzzyMatcher.score(normalizedQuery, entry.initials)
            val best = maxOf(textScore ?: Int.MIN_VALUE, initialsScore ?: Int.MIN_VALUE)
            if (best == Int.MIN_VALUE) null else Hit(entry.messageId, entry.date, best)
        }

        val collapsed = hits.groupBy { groups[it.messageId]?.groupId ?: it.messageId }
            .map { (_, members) ->
                val representative = members.maxBy { it.messageId }
                Hit(representative.messageId, representative.date, members.maxOf { it.score })
            }

        return collapsed
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.date })
            .take(MAX_RESULTS)
            .map { it.messageId }
    }

    private suspend fun buildCorpus(chatId: Long): List<SearchEntry> =
        dao.messagesFor(chatId).map { m ->
            val normalized = TagExtractor.normalizeWidth(m.rawText).lowercase()
            SearchEntry(m.messageId, m.date, normalized, PinyinIndex.initialsOf(normalized))
        }

    companion object {
        /** Below this many (width/case-normalized) characters, no search runs at all. */
        const val MIN_QUERY_LENGTH = 2
        private const val MAX_RESULTS = 200
    }
}
