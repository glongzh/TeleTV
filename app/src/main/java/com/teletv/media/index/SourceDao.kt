package com.teletv.media.index

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SourceDao {

    @Query("SELECT * FROM favorite_source ORDER BY addedAt ASC")
    suspend fun favorites(): List<FavoriteSource>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_source WHERE chatId = :chatId)")
    suspend fun isFavorite(chatId: Long): Boolean

    @Upsert
    suspend fun addFavorite(source: FavoriteSource)

    @Query("DELETE FROM favorite_source WHERE chatId = :chatId")
    suspend fun removeFavorite(chatId: Long)

    @Query("DELETE FROM favorite_source")
    suspend fun clearFavorites()
}
