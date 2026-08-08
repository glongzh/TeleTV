package com.teletv.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.teletv.R
import com.teletv.analytics.Analytics
import com.teletv.analytics.Events
import com.teletv.media.MediaItem
import com.teletv.media.MediaLibraryViewModel
import com.teletv.media.Screen
import com.teletv.media.VideoPart
import com.teletv.media.index.PlaybackProgress
import com.teletv.media.index.PlaybackProgressDao
import com.teletv.tdlib.TdlibClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File

data class PlayerUiState(
    val current: MediaItem? = null,
    val photoPath: String? = null,
    val downloadProgress: Float? = null, // set while a non-streaming video downloads
    val buffering: Boolean = false,      // video buffering / photo still downloading
    val error: String? = null,
    /** Set when playback started at a remembered position, for the overlay notice. */
    val resumedFromMs: Long? = null,
    /** True while a press-and-hold scrub gesture is previewing a target. */
    val scrubbing: Boolean = false,
    /** Position playback held when the gesture began; the delta readout's origin. */
    val scrubAnchorMs: Long = 0L,
    /** Preview cursor. NOT a player position — nothing is seeked until commit. */
    val scrubCursorMs: Long = 0L,
) {
    val isVideo: Boolean get() = current is MediaItem.Video
}

/**
 * Playback-only ViewModel: the media list, selection, and navigation live in the
 * shared [MediaLibraryViewModel]; this reacts to selection changes while the
 * player screen is active and drives ExoPlayer / photo display for that item.
 */
@UnstableApi
class PlayerViewModel(
    app: Application,
    private val client: TdlibClient,
    private val library: MediaLibraryViewModel,
    private val progressDao: PlaybackProgressDao,
    /** Outlives this ViewModel, so the teardown flush in [onCleared] completes. */
    private val writeScope: CoroutineScope,
) : AndroidViewModel(app) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(
        app,
        // Fall back to alternative decoders when the preferred one rejects the
        // format (e.g. emulator software decoders choking on HEVC 1080p60).
        DefaultRenderersFactory(app).setEnableDecoderFallback(true)
    ).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        // Snap to the nearest keyframe instead of decoding forward from the
        // preceding one. Every seek on this transport costs a DataSource
        // teardown plus a TDLib re-target, so the saved decode matters; landing
        // up to a GOP off is invisible for a 10 s tap or a multi-minute scrub.
        setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    private val streamingFactory = TdlibDataSourceFactory(client)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var currentJob: Job? = null

    /** Last position handed to the DAO, so the ticker skips no-op writes. */
    private var lastWrittenPositionMs = -1L

    /** Parts already warmed for the current item, so prefetch fires once each. */
    private val prefetchedFileIds = mutableSetOf<Int>()

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
                // A finished video auto-advances through the shared queue. Mark it
                // complete BEFORE advancing: next() re-enters showItem(), whose own
                // flush would otherwise land on this item with a stale position.
                if (playbackState == Player.STATE_ENDED) {
                    (_state.value.current as? MediaItem.Video)?.let { item ->
                        Analytics.capture(
                            Events.PLAYBACK_COMPLETED,
                            mapOf(
                                "duration_sec" to item.durationSec,
                                "is_group" to item.isGroup,
                            ),
                        )
                    }
                    flushProgress(markCompleted = true)
                    next()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Analytics.capture(
                    Events.PLAYBACK_FAILED,
                    mapOf(
                        "error_code" to error.errorCodeName,
                        "media_type" to mediaType(_state.value.current),
                        "streaming" to ((_state.value.current as? MediaItem.Video)?.supportsStreaming ?: false),
                    ),
                )
                _state.update {
                    it.copy(
                        buffering = false,
                        error = getApplication<Application>()
                            .getString(R.string.playback_failed, error.errorCodeName)
                    )
                }
            }
        })
        // React to (screen, selection) — NOT to list appends — so pagination while
        // playing never restarts the current item.
        viewModelScope.launch {
            library.state
                .map { Triple(it.screen, it.selectedIndex, it.items.getOrNull(it.selectedIndex)) }
                .distinctUntilChanged { old, new -> old.first == new.first && old.second == new.second }
                .collect { (screen, _, item) ->
                    if (screen == Screen.Player && item != null) showItem(item) else stopPlayback()
                }
        }
        // Bounds progress loss from paths that never run onCleared(): process
        // death, an OOM kill, the TV losing power.
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                if (!exoPlayer.isPlaying) continue
                if (exoPlayer.currentPosition != lastWrittenPositionMs) flushProgress(prune = false)
                maybePrefetchNextPart()
            }
        }
    }

    /**
     * The one exception to the app's no-prefetch rule: the next part of the group
     * already playing. Without it, every part boundary stalls on a cold TDLib
     * download. Nothing is fetched for any other grid item, and only the opening
     * bytes are requested — enough to cover the boundary, not the whole part.
     */
    private fun maybePrefetchNextPart() {
        val item = _state.value.current as? MediaItem.Video ?: return
        if (!item.isGroup) return
        val positionMs = exoPlayer.currentPosition
        var boundaryMs = 0L
        for ((i, part) in item.parts.withIndex()) {
            boundaryMs += part.durationSec * 1000L
            val next = item.parts.getOrNull(i + 1) ?: return
            if (positionMs < boundaryMs - PREFETCH_LEAD_MS || positionMs >= boundaryMs) continue
            if (!prefetchedFileIds.add(next.fileId)) return
            viewModelScope.launch {
                runCatching {
                    client.send<TdApi.File>(
                        TdApi.DownloadFile(next.fileId, PREFETCH_PRIORITY, 0, PREFETCH_BYTES, false)
                    )
                }
            }
            return
        }
    }

    fun next() = library.moveSelection(+1)
    fun previous() = library.moveSelection(-1)

    fun togglePlay() {
        val pausing = exoPlayer.playWhenReady
        if (pausing) flushProgress()
        exoPlayer.playWhenReady = !pausing
    }

    /** LEFT/RIGHT tap in video mode: jump by [deltaMs], clamped to the media bounds. */
    fun seekBy(deltaMs: Long) {
        val duration = exoPlayer.duration
        if (duration <= 0) return
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    // --- scrubbing ------------------------------------------------------------

    /** Play state to restore after a gesture commits; null when not scrubbing. */
    private var playWhenReadyBeforeScrub: Boolean? = null

    /**
     * Open a scrub gesture. Pauses playback DIRECTLY rather than through
     * [togglePlay], which would flush a playback-progress row — a scrub is not a
     * user pause and must leave no progress record of its own.
     *
     * @return true if the gesture opened; false when scrubbing is unavailable
     *   (not a video, or the duration is not known yet).
     */
    fun beginScrub(): Boolean {
        if (_state.value.current !is MediaItem.Video) return false
        val duration = exoPlayer.duration
        if (duration <= 0L) return false
        val anchor = exoPlayer.currentPosition.coerceIn(0L, duration)
        playWhenReadyBeforeScrub = exoPlayer.playWhenReady
        exoPlayer.playWhenReady = false
        _state.update { it.copy(scrubbing = true, scrubAnchorMs = anchor, scrubCursorMs = anchor) }
        return true
    }

    /** Advance the preview cursor. Issues no seek — see [commitScrub]. */
    fun updateScrub(deltaMs: Long) {
        val duration = exoPlayer.duration
        if (!_state.value.scrubbing || duration <= 0L) return
        _state.update {
            it.copy(scrubCursorMs = (it.scrubCursorMs + deltaMs).coerceIn(0L, duration))
        }
    }

    /** Close the gesture with the one and only seek it produces. */
    fun commitScrub() {
        val s = _state.value
        if (!s.scrubbing) return
        exoPlayer.seekTo(s.scrubCursorMs)
        endScrub()
    }

    /** Close the gesture without seeking (item switch, leaving the player). */
    fun cancelScrub() {
        if (!_state.value.scrubbing) return
        endScrub()
    }

    private fun endScrub() {
        playWhenReadyBeforeScrub?.let { exoPlayer.playWhenReady = it }
        playWhenReadyBeforeScrub = null
        _state.update { it.copy(scrubbing = false, scrubAnchorMs = 0L, scrubCursorMs = 0L) }
    }

    /**
     * Cursor speed in media-ms per real-ms, for a key held [heldMs].
     *
     * Ramps over [SCRUB_RAMP_MS] so the first moment is slow enough to land
     * within a few seconds of a target, then holds at a peak derived from the
     * total duration: an absolute peak would traverse a 5-minute clip in
     * seconds but need 90 s of holding on a feature-length video.
     */
    fun scrubVelocity(heldMs: Long): Float {
        val duration = exoPlayer.duration.takeIf { it > 0L } ?: return 0f
        // Peak aims to cross the whole media in ~SCRUB_TRAVERSE_MS of holding,
        // floored so short clips stay controllable rather than instantaneous.
        val peak = (duration.toFloat() / SCRUB_TRAVERSE_MS).coerceAtLeast(SCRUB_MIN_RATE)
        val ramp = (heldMs.toFloat() / SCRUB_RAMP_MS).coerceIn(0f, 1f)
        // Quadratic ease-in: fine control early, full speed once committed to a jump.
        return peak * (SCRUB_START_FRACTION + (1f - SCRUB_START_FRACTION) * ramp * ramp)
    }

    /**
     * Drop the resume notice once it has had its moment. Without this it stays
     * set for the whole item, so it reappears every time the overlay is summoned
     * — it is an announcement about opening the video, not a status field.
     */
    fun clearResumeNotice() {
        if (_state.value.resumedFromMs != null) _state.update { it.copy(resumedFromMs = null) }
    }

    fun exitToGrid() {
        stopPlayback()
        library.backToGrid()
    }

    private fun stopPlayback() {
        cancelScrub() // restore play state before the flush reads the position
        flushProgress()
        currentJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    private fun showItem(item: MediaItem) {
        cancelScrub() // a gesture in flight belongs to the outgoing item
        flushProgress() // still reads the OUTGOING item; stop() below would zero it
        currentJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        lastWrittenPositionMs = -1L
        prefetchedFileIds.clear()
        _state.update {
            it.copy(
                current = item,
                photoPath = null,
                downloadProgress = null,
                error = null,
                resumedFromMs = null,
            )
        }
        currentJob = viewModelScope.launch {
            when (item) {
                is MediaItem.Photo -> {
                    Analytics.capture(Events.PLAYBACK_STARTED, mapOf("media_type" to "photo"))
                    showPhoto(item)
                }
                is MediaItem.Video -> {
                    val resumeMs = resumePointFor(item)
                    if (resumeMs > 0L) _state.update { it.copy(resumedFromMs = resumeMs) }
                    // Shape of the item and how it will be fetched — no title,
                    // caption or file name. The split-group and resume figures
                    // are what tell us whether those two features get used.
                    Analytics.capture(
                        Events.PLAYBACK_STARTED,
                        mapOf(
                            "media_type" to "video",
                            "duration_sec" to item.durationSec,
                            "size_bytes" to item.size,
                            "streaming" to item.supportsStreaming,
                            "is_group" to item.isGroup,
                            "part_count" to item.parts.size,
                            "resumed" to (resumeMs > 0L),
                        ),
                    )
                    if (item.supportsStreaming) playStreaming(item, resumeMs)
                    else playAfterDownload(item, resumeMs)
                }
            }
        }
    }

    // --- progress memory ------------------------------------------------------

    private val activeChatId: Long? get() = library.state.value.activeSource?.chatId

    /** The stored position to start at, or 0 for a fresh start. */
    private suspend fun resumePointFor(item: MediaItem.Video): Long {
        val chatId = activeChatId ?: return 0L
        val row = runCatching { progressDao.progressFor(chatId, item.groupId) }.getOrNull()
            ?: return 0L
        return if (!row.completed && row.positionMs >= MIN_PROGRESS_MS) row.positionMs else 0L
    }

    /**
     * Persist the current video's position. MUST be called before any
     * `stop()`/`clearMediaItems()` — those reset `currentPosition` to 0.
     *
     * Writes on [writeScope] so a flush issued from `onCleared` (where
     * `viewModelScope` is already cancelled) still lands.
     */
    private fun flushProgress(markCompleted: Boolean = false, prune: Boolean = true) {
        val item = _state.value.current as? MediaItem.Video ?: return
        val chatId = activeChatId ?: return
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        // A player that never got going (load error, not yet prepared) reports 0.
        // Writing that would delete a perfectly good resume point, so leave it be.
        if (!markCompleted && positionMs <= 0L) return
        val durationMs =
            exoPlayer.duration.takeIf { it > 0L } ?: (item.durationSec * 1000L)
        val completed = markCompleted ||
            (durationMs > 0L && durationMs - positionMs <= completionTailMs(durationMs))

        lastWrittenPositionMs = positionMs
        writeScope.launch {
            runCatching {
                // Keyed by groupId: for a split video that is the first part's id,
                // so one record covers the merged timeline instead of the parts
                // competing with separate resume points.
                when {
                    // Completion wins over the floor: a short clip watched to the
                    // end is finished, however few seconds that took.
                    completed -> progressDao.save(
                        PlaybackProgress(chatId, item.groupId, 0L, durationMs, true, now())
                    )
                    positionMs < MIN_PROGRESS_MS -> progressDao.delete(chatId, item.groupId)
                    else -> progressDao.save(
                        PlaybackProgress(chatId, item.groupId, positionMs, durationMs, false, now())
                    )
                }
                if (prune) progressDao.pruneToLimit(RETENTION_LIMIT)
            }
        }
    }

    private suspend fun showPhoto(item: MediaItem.Photo) {
        _state.update { it.copy(buffering = true) } // photos have no player buffering events
        runCatching {
            // synchronous = true resolves once the file is fully downloaded.
            val file = client.send<TdApi.File>(
                TdApi.DownloadFile(item.fileId, 32, 0, 0, true)
            )
            _state.update { it.copy(photoPath = file.local?.path, buffering = false) }
        }.onFailure { e ->
            _state.update {
                it.copy(
                    buffering = false,
                    error = e.message ?: getApplication<Application>().getString(R.string.failed_to_load_photo)
                )
            }
        }
    }

    private fun playStreaming(item: MediaItem.Video, resumeMs: Long) {
        val source = if (item.isGroup) mergedSource(item) else
            ProgressiveMediaSource.Factory(streamingFactory)
                .createMediaSource(ExoMediaItem.fromUri(tdlibMediaUri(item.fileId, item.size)))
        // Start position rather than a seek after prepare: TdlibDataSource then
        // gets the resume offset on its FIRST DataSpec, instead of fetching the
        // head of the file only to discard it.
        exoPlayer.setMediaSource(source, resumeMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /**
     * A split group as a single player window: summed duration, seeks that cross
     * part boundaries, and STATE_ENDED only after the final part. Telegram gives
     * a per-part duration, which is exactly the placeholder the builder wants, so
     * nothing has to be probed first.
     *
     * A lone video deliberately keeps the plain [ProgressiveMediaSource] path
     * rather than being wrapped in a one-element concatenation — it is the most
     * exercised code in the app and there is nothing to gain by perturbing it.
     */
    private fun mergedSource(item: MediaItem.Video): ConcatenatingMediaSource2 {
        val builder = ConcatenatingMediaSource2.Builder()
        for (part in item.parts) {
            builder.add(
                ProgressiveMediaSource.Factory(streamingFactory)
                    .createMediaSource(ExoMediaItem.fromUri(tdlibMediaUri(part.fileId, part.size))),
                part.durationSec * 1000L,
            )
        }
        return builder.build()
    }

    private suspend fun playAfterDownload(item: MediaItem.Video, resumeMs: Long) {
        // Fallback for supports_streaming == false: download fully, showing progress.
        // A group falls back wholesale rather than per part, so the merged timeline
        // survives and the indicator reports one aggregate figure instead of
        // restarting at 0% at every boundary.
        val parts = item.parts.ifEmpty {
            listOf(VideoPart(item.messageId, item.fileId, item.size, item.durationSec, false))
        }
        val totalBytes = parts.sumOf { it.size }.coerceAtLeast(1L)
        val partFileIds = parts.map { it.fileId }.toSet()
        val downloaded = mutableMapOf<Int, Long>()

        val progressJob = viewModelScope.launch {
            client.updates
                .filterIsInstance<TdApi.UpdateFile>()
                .filter { it.file.id in partFileIds }
                .collect { u ->
                    downloaded[u.file.id] = u.file.local.downloadedSize
                    val p = downloaded.values.sum().toFloat() / totalBytes
                    _state.update { it.copy(downloadProgress = p.coerceIn(0f, 1f)) }
                }
        }
        runCatching {
            val paths = parts.map { part ->
                val file = client.send<TdApi.File>(TdApi.DownloadFile(part.fileId, 32, 0, 0, true))
                downloaded[part.fileId] = part.size // settle the bar past this part
                file.local?.path ?: error("no local path")
            }
            progressJob.cancel()
            _state.update { it.copy(downloadProgress = null) }
            if (paths.size == 1) {
                exoPlayer.setMediaItem(ExoMediaItem.fromUri(Uri.fromFile(File(paths.single()))), resumeMs)
            } else {
                val builder = ConcatenatingMediaSource2.Builder()
                    .useDefaultMediaSourceFactory(getApplication())
                parts.forEachIndexed { i, part ->
                    builder.add(
                        ExoMediaItem.fromUri(Uri.fromFile(File(paths[i]))),
                        part.durationSec * 1000L,
                    )
                }
                exoPlayer.setMediaSource(builder.build(), resumeMs)
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }.onFailure { e ->
            progressJob.cancel()
            _state.update {
                it.copy(
                    downloadProgress = null,
                    error = e.message ?: getApplication<Application>().getString(R.string.failed_to_load_video)
                )
            }
        }
    }

    override fun onCleared() {
        flushProgress()
        exoPlayer.release()
        super.onCleared()
    }

    class Factory(
        private val app: Application,
        private val client: TdlibClient,
        private val library: MediaLibraryViewModel,
        private val progressDao: PlaybackProgressDao,
        private val writeScope: CoroutineScope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(app, client, library, progressDao, writeScope) as T
    }

    private companion object {
        /** Below this, an item is "barely opened": leave no resume point behind. */
        const val MIN_PROGRESS_MS = 15_000L
        const val TICK_MS = 5_000L
        const val RETENTION_LIMIT = 500

        /**
         * How close to the end counts as finished. Proportional, because a flat
         * 30 s tail would call a 40-second clip complete at the 10-second mark.
         */
        fun completionTailMs(durationMs: Long): Long = minOf(30_000L, durationMs / 10)

        // Scrub tuning. Deliberately isolated: these are the constants expected
        // to be adjusted after the first hands-on pass on a TV.
        /** Sustained-hold time to cross the whole media, in real ms. */
        const val SCRUB_TRAVERSE_MS = 8_000f
        /** Speed floor (media-ms per real-ms) so short clips stay controllable. */
        const val SCRUB_MIN_RATE = 6f
        /** How long the velocity ramp takes to reach peak. */
        const val SCRUB_RAMP_MS = 2_500f
        /** Fraction of peak speed at the very start of the ramp. */
        const val SCRUB_START_FRACTION = 0.12f

        /** How far ahead of a part boundary the next part is warmed. */
        const val PREFETCH_LEAD_MS = 30_000L
        /** Opening bytes only — enough to cover the boundary, not the whole part. */
        const val PREFETCH_BYTES = 8L * 1024 * 1024
        /** Below the playing stream's 32 so it never competes with playback. */
        const val PREFETCH_PRIORITY = 16

        fun now(): Long = System.currentTimeMillis()

        fun mediaType(item: MediaItem?): String = when (item) {
            is MediaItem.Video -> "video"
            is MediaItem.Photo -> "photo"
            null -> "none"
        }
    }
}
