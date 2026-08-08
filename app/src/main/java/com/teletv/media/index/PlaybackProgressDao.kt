package com.teletv.media.index

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {

    @Upsert
    suspend fun save(row: PlaybackProgress)

    @Query("SELECT * FROM playback_progress WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun progressFor(chatId: Long, messageId: Long): PlaybackProgress?

    /** Reactive feed for the grid: emits on every write, so returning from the
     *  player (or an auto-advance completion) refreshes cells with no manual poke. */
    @Query("SELECT * FROM playback_progress WHERE chatId = :chatId")
    fun observeChat(chatId: Long): Flow<List<PlaybackProgress>>

    @Query("DELETE FROM playback_progress WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun delete(chatId: Long, messageId: Long)

    @Query("DELETE FROM playback_progress")
    suspend fun clearAll()

    /**
     * Keep only the [limit] most recently updated rows. Expressed as an age
     * cutoff rather than a rowid exclusion so it uses only declared columns;
     * ties at the boundary survive, which is fine for a bound this loose.
     */
    @Query(
        """
        DELETE FROM playback_progress
        WHERE updatedAt < (
            SELECT MIN(updatedAt) FROM (
                SELECT updatedAt FROM playback_progress ORDER BY updatedAt DESC LIMIT :limit
            )
        )
        """
    )
    suspend fun pruneToLimit(limit: Int)
}
