package com.teletv.storage

import com.teletv.tdlib.TdlibClient
import org.drinkless.tdlib.TdApi

/** Approximate cache usage for display. */
data class StorageUsage(val filesSize: Long, val databaseSize: Long)

/**
 * Thin wrapper over TDLib's storage-management calls. `optimizeStorage` with an
 * empty `fileTypes` list uses TDLib's default set (excludes thumbnails, profile
 * photos, stickers, wallpapers) and only ever touches the files directory — the
 * authorization database and the app's own Room index are untouched.
 */
class StorageRepository(private val client: TdlibClient) {

    /**
     * Cache usage for the settings display. The cached-media figure comes from
     * the accurate [TdApi.GetStorageStatistics] (a real scan of the files
     * directory): the "fast" variant only reports TDLib's lazily-maintained
     * counter, which badly under-reports partial/streamed downloads (a few
     * minutes of streamed video can still read as a few hundred KB). The database
     * size, which the accurate scan doesn't cover, still comes from the fast call.
     */
    suspend fun usage(): StorageUsage {
        val fast = client.send<TdApi.StorageStatisticsFast>(TdApi.GetStorageStatisticsFast())
        // chatLimit = 0: we only need the grand total, not the per-chat breakdown.
        val full = client.send<TdApi.StorageStatistics>(TdApi.GetStorageStatistics(0))
        return StorageUsage(filesSize = full.size, databaseSize = fast.databaseSize)
    }

    /** Delete cached media down to [capBytes], keeping thumbnails/profile photos/stickers. */
    suspend fun trimTo(capBytes: Long) {
        client.send<TdApi.StorageStatistics>(
            TdApi.OptimizeStorage(
                /* size = */ capBytes,
                /* ttl = */ -1,
                /* count = */ -1,
                /* immunityDelay = */ 0,
                /* fileTypes = */ null,
                /* chatIds = */ LongArray(0),
                /* excludeChatIds = */ LongArray(0),
                /* returnDeletedFileStatistics = */ false,
                /* chatLimit = */ 0,
            )
        )
    }

    /** Delete everything deletable. */
    suspend fun clearAll() = trimTo(0)
}
