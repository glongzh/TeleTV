package com.teletv.media

import android.content.Context
import com.teletv.media.index.FavoriteSource
import com.teletv.media.index.SourceDao

/**
 * Facade over source selection: the Saved default, favorites (persisted), recent
 * joined chats, the last-viewed source, and the entitlement gate. The ViewModel
 * and the source picker use this rather than touching the DAO / prefs directly.
 */
class SourceRepository(
    private val media: MediaRepository,
    private val sourceDao: SourceDao,
    context: Context,
    private val entitlement: SourceEntitlement,
) {
    private val prefs = context.getSharedPreferences("media_source", Context.MODE_PRIVATE)

    suspend fun savedSource(): MediaSource = media.savedSource()

    /** Favorites, refreshed with live title/protected flag when resolvable. */
    suspend fun favorites(): List<MediaSource> = sourceDao.favorites().map { fav ->
        media.sourceForChat(fav.chatId)
            ?: MediaSource(chatId = fav.chatId, title = fav.title, isProtected = false, isDefault = false)
    }

    suspend fun recents(limit: Int = 40): List<MediaSource> = media.recentSources(limit)

    suspend fun isFavorite(chatId: Long): Boolean = sourceDao.isFavorite(chatId)

    suspend fun addFavorite(source: MediaSource) =
        sourceDao.addFavorite(FavoriteSource(source.chatId, source.title, System.currentTimeMillis()))

    suspend fun removeFavorite(chatId: Long) = sourceDao.removeFavorite(chatId)

    /** The default source is always allowed; others pass the entitlement gate. */
    fun canUse(source: MediaSource): Boolean =
        source.isDefault || entitlement.canUseSource(source.chatId)

    fun saveLastSource(chatId: Long) {
        prefs.edit().putLong(KEY_LAST, chatId).apply()
    }

    private fun lastSourceId(): Long? =
        prefs.getLong(KEY_LAST, 0L).takeIf { it != 0L }

    /**
     * Restore the last-viewed source, falling back to Saved Messages when it is
     * absent, unresolvable, protected, or no longer permitted.
     */
    suspend fun resolveInitialSource(): MediaSource {
        val last = lastSourceId()
        val saved = media.savedSource()
        if (last != null && last != saved.chatId) {
            media.sourceForChat(last)?.let { src ->
                if (!src.isProtected && canUse(src)) return src
            }
        }
        return saved
    }

    suspend fun clearOnLogout() {
        sourceDao.clearFavorites()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_LAST = "last_source_chat_id"
    }
}
