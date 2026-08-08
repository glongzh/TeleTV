package com.teletv.storage

import com.teletv.tdlib.TdlibClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Generation-scoped automatic cache cleanup: one trim when auth reaches ready
 * (bounds cross-launch growth), plus a debounced trim once enough new data
 * downloads in a session (bounds a long binge). Unlimited cap disables both.
 */
class StorageManager(
    private val client: TdlibClient,
    private val repo: StorageRepository,
    private val prefs: CachePrefs,
    private val scope: CoroutineScope,
) {
    private var accumulatedBytes = 0L
    private val seenFileIds = HashSet<Int>()

    /** Run the initial trim, then start observing completed downloads. */
    fun start() {
        scope.launch {
            trimIfCapped()
            observeDownloads()
        }
    }

    private suspend fun observeDownloads() {
        client.updates
            .filterIsInstance<TdApi.UpdateFile>()
            .collect { update ->
                val file = update.file
                if (!file.local.isDownloadingCompleted) return@collect
                if (!seenFileIds.add(file.id)) return@collect // already counted
                accumulatedBytes += file.size
                if (accumulatedBytes >= DEBOUNCE_THRESHOLD_BYTES) {
                    accumulatedBytes = 0L
                    seenFileIds.clear()
                    scope.launch {
                        delay(DEBOUNCE_DELAY_MS)
                        trimIfCapped()
                    }
                }
            }
    }

    private suspend fun trimIfCapped() {
        val cap = prefs.currentCap().bytes ?: return // Unlimited: no automatic trim
        runCatching { repo.trimTo(cap) }
    }

    private companion object {
        const val DEBOUNCE_THRESHOLD_BYTES = 256L * 1024 * 1024
        const val DEBOUNCE_DELAY_MS = 5_000L
    }
}
