package com.teletv.media.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TagIndexDao {

    // --- writes ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<IndexedMessage>)

    /** Returns -1 when the (chatId, category, value, source) tag already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT id FROM tag WHERE chatId = :chatId AND category = :category AND value = :value AND source = :source")
    suspend fun tagId(chatId: Long, category: String, value: String, source: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<MessageTag>)

    /** Insert-or-look-up a tag id for a source. Call inside a transaction. */
    suspend fun ensureTag(chatId: Long, category: String, value: String, display: String, source: String): Long {
        val inserted = insertTag(
            TagEntity(chatId = chatId, category = category, value = value, display = display, source = source)
        )
        return if (inserted != -1L) inserted else checkNotNull(tagId(chatId, category, value, source))
    }

    /** Persist one scanned page: messages plus their extracted tag links, for one source. */
    @Transaction
    suspend fun persistPage(chatId: Long, messages: List<IndexedMessage>, tagsPerMessage: Map<Long, List<ExtractedTag>>) {
        insertMessages(messages)
        val links = ArrayList<MessageTag>()
        for ((messageId, tags) in tagsPerMessage) {
            for (tag in tags) {
                val id = ensureTag(chatId, tag.category.name, tag.value, tag.display, TagEntity.SOURCE_PARSED)
                links.add(MessageTag(tagId = id, chatId = chatId, messageId = messageId))
            }
        }
        insertLinks(links)
    }

    // --- filter panel reads (per source) --------------------------------------

    /**
     * Tags of a category with distinct-ITEM counts for a source, highest first.
     * A split video counts once rather than once per part: without the COALESCE
     * a three-part film adds 3 to every tag it touches, which inflates the
     * numbers and — because ORDER BY count drives the panel — skews the ordering
     * toward whatever terms happen to co-occur with split files.
     * Orphan tags (all their messages pruned) drop out via the HAVING clause.
     */
    @Query(
        """
        SELECT t.id AS id, t.category AS category, t.value AS value, t.display AS display,
               COUNT(DISTINCT COALESCE(g.groupId, mt.messageId)) AS count
        FROM tag t JOIN message_tag mt ON mt.tagId = t.id
        LEFT JOIN part_group g ON g.chatId = mt.chatId AND g.messageId = mt.messageId
        WHERE t.chatId = :chatId AND t.category = :category
        GROUP BY t.id
        HAVING count >= :minCount
        ORDER BY count DESC, t.value ASC
        LIMIT :limit
        """
    )
    suspend fun tagsForCategory(chatId: Long, category: String, minCount: Int, limit: Int): List<TagWithCount>

    /**
     * One id per item for a tag, newest first. A group contributes a single
     * representative — its newest member, so the filtered order matches the
     * unfiltered stream — which the caller resolves back to the whole group.
     */
    @Query(
        """
        SELECT MAX(m.messageId) FROM message_tag mt
        JOIN indexed_message m ON m.chatId = mt.chatId AND m.messageId = mt.messageId
        LEFT JOIN part_group g ON g.chatId = mt.chatId AND g.messageId = mt.messageId
        WHERE mt.tagId = :tagId
        GROUP BY COALESCE(g.groupId, mt.messageId)
        ORDER BY MAX(m.date) DESC, MAX(m.messageId) DESC
        """
    )
    suspend fun messageIdsForTag(tagId: Long): List<Long>

    // --- part groups ----------------------------------------------------------

    /**
     * Every indexed video of a source as a detection candidate. Detection reads
     * the file name alone, falling back to the raw text only when Telegram gave
     * the video no name at all.
     */
    @Query(
        """
        SELECT messageId, date, size, durationSec,
               CASE WHEN fileName != '' THEN fileName ELSE rawText END AS text
        FROM indexed_message
        WHERE chatId = :chatId AND type = 'video'
        ORDER BY messageId DESC
        """
    )
    suspend fun groupingCandidates(chatId: Long): List<GroupCandidate>

    /** Every stored row of a source, for re-extracting tags without re-paging. */
    @Query("SELECT * FROM indexed_message WHERE chatId = :chatId")
    suspend fun messagesFor(chatId: Long): List<IndexedMessage>

    /** Indexed videos still missing the data grouping needs. */
    @Query("SELECT COUNT(*) FROM indexed_message WHERE chatId = :chatId AND type = 'video' AND fileName = ''")
    suspend fun videosMissingFileName(chatId: Long): Int

    @Upsert
    suspend fun saveGroups(rows: List<PartGroup>)

    /** Every grouped message of a source, for the grid's collapse pass. */
    @Query("SELECT * FROM part_group WHERE chatId = :chatId")
    fun observeGroups(chatId: Long): Flow<List<PartGroup>>

    /** One group's members in part order. */
    @Query("SELECT * FROM part_group WHERE chatId = :chatId AND groupId = :groupId ORDER BY partIndex ASC")
    suspend fun groupMembers(chatId: Long, groupId: Long): List<PartGroup>

    @Query("DELETE FROM part_group WHERE chatId = :chatId AND messageId IN (:messageIds)")
    suspend fun deleteGroupsFor(chatId: Long, messageIds: List<Long>)

    @Query("SELECT COUNT(*) FROM indexed_message WHERE chatId = :chatId")
    suspend fun indexedMessageCount(chatId: Long): Int

    // --- pruning (per source) -------------------------------------------------

    @Transaction
    suspend fun pruneMessages(chatId: Long, messageIds: List<Long>) {
        deleteLinksFor(chatId, messageIds)
        // Drop group rows too, so a deleted part cannot leave a dangling group
        // that the count query would still fold other members into.
        deleteGroupsFor(chatId, messageIds)
        deleteMessages(chatId, messageIds)
    }

    @Query("DELETE FROM message_tag WHERE chatId = :chatId AND messageId IN (:messageIds)")
    suspend fun deleteLinksFor(chatId: Long, messageIds: List<Long>)

    @Query("DELETE FROM indexed_message WHERE chatId = :chatId AND messageId IN (:messageIds)")
    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>)

    // --- scan cursor (per source) ---------------------------------------------

    @Query("SELECT * FROM scan_state WHERE chatId = :chatId")
    suspend fun scanState(chatId: Long): ScanState?

    @Upsert
    suspend fun saveScanState(state: ScanState)

    // --- per-source wipe (rebuild) --------------------------------------------

    @Transaction
    suspend fun clearSource(chatId: Long) {
        clearLinksFor(chatId); clearTagsFor(chatId); clearMessagesFor(chatId)
        clearGroupsFor(chatId); clearScanStateFor(chatId)
    }

    @Query("DELETE FROM part_group WHERE chatId = :chatId") suspend fun clearGroupsFor(chatId: Long)

    @Query("DELETE FROM message_tag WHERE chatId = :chatId") suspend fun clearLinksFor(chatId: Long)
    @Query("DELETE FROM tag WHERE chatId = :chatId") suspend fun clearTagsFor(chatId: Long)
    @Query("DELETE FROM indexed_message WHERE chatId = :chatId") suspend fun clearMessagesFor(chatId: Long)
    @Query("DELETE FROM scan_state WHERE chatId = :chatId") suspend fun clearScanStateFor(chatId: Long)

    // --- full wipe (logout) ---------------------------------------------------

    @Transaction
    suspend fun clearAll() {
        clearLinks(); clearTags(); clearMessages(); clearGroups(); clearScanState()
    }

    @Query("DELETE FROM part_group") suspend fun clearGroups()

    @Query("DELETE FROM message_tag") suspend fun clearLinks()
    @Query("DELETE FROM tag") suspend fun clearTags()
    @Query("DELETE FROM indexed_message") suspend fun clearMessages()
    @Query("DELETE FROM scan_state") suspend fun clearScanState()
}
