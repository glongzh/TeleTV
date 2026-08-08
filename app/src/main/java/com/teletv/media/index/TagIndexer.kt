package com.teletv.media.index

import com.teletv.media.MediaRepository
import com.teletv.media.ScanRow
import com.teletv.tdlib.TdlibException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-source index lifecycle, rendered by the filter panel. */
sealed interface IndexState {
    /** Never scanned — browsing works, but there are no tags yet. */
    data object NotIndexed : IndexState
    /** A scan is running. [total] is Telegram's approximate media count; 0 when unknown. */
    data class Indexing(val indexed: Int, val total: Int) : IndexState
    /** Full scan complete; [completedAt] is unix millis (0 if unknown). */
    data class Indexed(val count: Int, val completedAt: Long) : IndexState
    data class Failed(val message: String) : IndexState
}

/**
 * Application-scoped, per-source background scanner: pages through a source's
 * text metadata, runs [TagExtractor], and persists the inverted index. Scans
 * are resumable (cursor saved per page) and serialized (one at a time). Never
 * downloads media files.
 *
 * Saved Messages auto-scans on auth ready; other sources scan only when the user
 * triggers [requestScan]. State per source is exposed via [states].
 */
class TagIndexer(
    private val repo: MediaRepository,
    private val dao: TagIndexDao,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<Long, IndexState>>(emptyMap())
    val states: StateFlow<Map<Long, IndexState>> = _states.asStateFlow()

    // Serializes scans: only one source is scanned at a time; others queue.
    private val scanMutex = Mutex()

    /** Per-source look-behind carried across scan pages for group detection. */
    private val groupTails = mutableMapOf<Long, List<ScanRow>>()

    /** Chats with a scan running or queued, so repeat requests collapse. */
    private val inFlight = mutableSetOf<Long>()

    fun stateOf(chatId: Long): IndexState = _states.value[chatId] ?: IndexState.NotIndexed

    private fun setState(chatId: Long, state: IndexState) {
        _states.update { it + (chatId to state) }
    }

    /**
     * Populate a source's state from the DB without scanning, so the panel can
     * show NotIndexed / Indexed before any scan runs. Skips sources mid-scan.
     */
    fun refreshState(chatId: Long) {
        scope.launch {
            if (_states.value[chatId] is IndexState.Indexing) return@launch
            val st = dao.scanState(chatId)
            // An interrupted (incomplete) scan reads as NotIndexed so the panel offers
            // Build, which resumes from the persisted cursor; any partial tags still show.
            setState(
                chatId,
                if (st?.completedFullScan == true) IndexState.Indexed(dao.indexedMessageCount(chatId), st.completedAt)
                else IndexState.NotIndexed,
            )
        }
    }

    /**
     * Build (first scan) or sync (incremental) a source, depending on its state.
     * Idempotent per source: now that every source auto-scans on activation, the
     * same chat can be requested from several places, and a redundant request
     * must not queue a second pass behind the running one.
     */
    fun requestScan(chatId: Long) {
        synchronized(inFlight) { if (!inFlight.add(chatId)) return }
        scope.launch {
            try {
                runCatching { scanSerialized(chatId) }
                    .onFailure { e -> setState(chatId, IndexState.Failed(e.message ?: "Index scan failed")) }
            } finally {
                synchronized(inFlight) { inFlight.remove(chatId) }
            }
        }
    }

    /** Auto-run for Saved Messages on auth ready (build if new, else sync). */
    fun autoStartSaved(chatId: Long) = requestScan(chatId)

    /** Wipe one source's index and scan it fresh. */
    fun rebuildSource(chatId: Long) {
        scope.launch {
            dao.clearSource(chatId)
            setState(chatId, IndexState.NotIndexed)
            runCatching { scanSerialized(chatId) }
                .onFailure { e -> setState(chatId, IndexState.Failed(e.message ?: "Index scan failed")) }
        }
    }

    /** Drop all indexed data (logout). */
    suspend fun clear() {
        dao.clearAll()
        groupTails.clear()
        _states.value = emptyMap()
    }

    private suspend fun scanSerialized(chatId: Long) = scanMutex.withLock {
        groupTails.remove(chatId) // a fresh run must not look behind into a stale page
        val persisted = dao.scanState(chatId) ?: ScanState(chatId = chatId)
        // A schema addition leaves existing rows blank and an incremental sync
        // never revisits them, so a source indexed by an older build would stay
        // permanently ungrouped. Detecting the gap here rather than in the
        // migration keeps it self-healing: a migration fires once, this check
        // runs on every scan and repairs whatever state the install is in.
        val stale = persisted.completedFullScan && dao.videosMissingFileName(chatId) > 0
        val saved =
            if (stale) persisted.copy(completedFullScan = false, fullScanCursor = 0L) else persisted
        if (saved.completedFullScan) scanIncremental(chatId, saved) else scanFull(chatId, saved)
        groupTails.remove(chatId)
        runCatching { reextractIfStale(chatId) }
        // Authoritative pass over the whole corpus: catches groups straddling a
        // page boundary, and is the only thing that groups a source indexed
        // before this feature existed.
        runCatching { regroupSource(chatId) }
        groupTails.remove(chatId)
        val st = dao.scanState(chatId)
        setState(chatId, IndexState.Indexed(dao.indexedMessageCount(chatId), st?.completedAt ?: 0L))
    }

    /** First pass newest → oldest, resuming from the persisted cursor. */
    private suspend fun scanFull(chatId: Long, start: ScanState) {
        var st = start
        while (true) {
            val page = fetchPageWithBackoff(chatId, st.fullScanCursor)
            if (page.rows.isNotEmpty()) {
                persist(chatId, page.rows)
                val done = page.nextFromMessageId == 0L
                st = st.copy(
                    newestScannedMessageId = maxOf(st.newestScannedMessageId, page.rows.maxOf { it.messageId }),
                    fullScanCursor = page.nextFromMessageId,
                    completedFullScan = done,
                    completedAt = if (done) System.currentTimeMillis() else st.completedAt,
                )
            } else {
                st = st.copy(completedFullScan = true, fullScanCursor = 0L, completedAt = System.currentTimeMillis())
            }
            dao.saveScanState(st)
            setState(chatId, IndexState.Indexing(dao.indexedMessageCount(chatId), page.totalCount))
            if (st.completedFullScan) return
            delay(PAGE_DELAY_MS)
        }
    }

    /** Sync: newest → first already-indexed message, then stop. */
    private suspend fun scanIncremental(chatId: Long, start: ScanState) {
        var st = start
        var cursor = 0L
        while (true) {
            val page = fetchPageWithBackoff(chatId, cursor)
            val fresh = page.rows.filter { it.messageId > start.newestScannedMessageId }
            if (fresh.isNotEmpty()) {
                persist(chatId, fresh)
                st = st.copy(newestScannedMessageId = maxOf(st.newestScannedMessageId, fresh.maxOf { it.messageId }))
                dao.saveScanState(st)
            }
            if (fresh.size < page.rows.size || page.nextFromMessageId == 0L) return
            cursor = page.nextFromMessageId
            setState(chatId, IndexState.Indexing(dao.indexedMessageCount(chatId), page.totalCount))
            delay(PAGE_DELAY_MS)
        }
    }

    private suspend fun persist(chatId: Long, rows: List<ScanRow>) {
        val messages = rows.map {
            IndexedMessage(
                chatId, it.messageId, it.type, it.date, it.durationSec, it.text, it.size, it.fileName
            )
        }
        val tags = rows.associate { row ->
            row.messageId to (
                TagExtractor.extract(row.text) +
                    TagExtractor.names(row.fileName) +
                    TagExtractor.facets(row.type, row.date)
                )
        }
        dao.persistPage(chatId, messages, tags)
        detectGroups(chatId, rows)
    }

    /**
     * Run split detection over this page plus a carried-over tail of the previous
     * one, so a group whose parts straddle a page boundary is still found. Only
     * videos can be parts; photos are dropped before detection.
     */
    private suspend fun detectGroups(chatId: Long, rows: List<ScanRow>) {
        val videos = rows.filter { it.type == "video" }
        val carried = groupTails[chatId].orEmpty()
        val candidates = (carried + videos).map {
            GroupCandidate(
                it.messageId, it.date, it.fileName.ifEmpty { it.text }, it.size, it.durationSec
            )
        }
        persistGroups(chatId, GroupDetector.detect(candidates))
        // Keep the newest slice as the next page's look-behind. The scan runs
        // newest → oldest, so a group's later parts are seen first.
        groupTails[chatId] = videos.takeLast(GROUP_TAIL)
    }

    /**
     * Rebuild a source's tags from its stored rows when the extraction rules have
     * moved on since they were built.
     *
     * Local-only — the scan already persisted every caption and file name, so new
     * rules reach an existing library without re-paging Telegram. Without this a
     * rules change would only ever apply to messages arriving afterwards, the same
     * trap the `fileName` backfill fell into.
     */
    private suspend fun reextractIfStale(chatId: Long) {
        val st = dao.scanState(chatId) ?: return
        if (st.extractorVersion == EXTRACTOR_VERSION) return
        val rows = dao.messagesFor(chatId)
        if (rows.isNotEmpty()) {
            // Old links and tags must go first: re-persisting alone would leave
            // tags the current rules no longer produce.
            dao.clearLinksFor(chatId)
            dao.clearTagsFor(chatId)
            dao.persistPage(
                chatId,
                rows,
                rows.associate { m ->
                    m.messageId to (
                        TagExtractor.extract(m.rawText) +
                            TagExtractor.names(m.fileName) +
                            TagExtractor.facets(m.type, m.date)
                        )
                },
            )
        }
        dao.saveScanState(st.copy(extractorVersion = EXTRACTOR_VERSION))
    }

    /**
     * Re-run detection over everything already indexed for a source.
     *
     * The per-page pass above only ever sees rows as they are scanned, so on its
     * own it never covers a source whose scan already completed before grouping
     * existed: the incremental sync finds no fresh messages, persist() is never
     * called, and the source stays ungrouped forever. This pass is what makes
     * grouping appear for an existing library, and it is local-only — no paging,
     * no network — so running it after every scan is cheap.
     */
    private suspend fun regroupSource(chatId: Long) {
        val candidates = dao.groupingCandidates(chatId)
        if (candidates.size < 2) return
        persistGroups(chatId, GroupDetector.detect(candidates))
    }

    private suspend fun persistGroups(chatId: Long, detected: List<DetectedGroup>) {
        if (detected.isEmpty()) return
        dao.saveGroups(
            detected.flatMap { g ->
                g.memberIds.mapIndexed { i, id -> PartGroup(chatId, id, g.groupId, i + 1) }
            }
        )
    }

    /** Fetch one page, backing off on rate limits ("... retry after N"). */
    private suspend fun fetchPageWithBackoff(chatId: Long, fromMessageId: Long): com.teletv.media.ScanPage {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) {
            try {
                return repo.scanPage(chatId, fromMessageId, PAGE_SIZE)
            } catch (e: TdlibException) {
                lastError = e
                val retryAfter = RETRY_AFTER.find(e.message.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                delay((retryAfter ?: DEFAULT_BACKOFF_SEC) * 1000L)
            }
        }
        throw lastError ?: IllegalStateException("Index scan failed")
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val PAGE_DELAY_MS = 100L
        /** Look-behind rows kept between pages; comfortably over the max group size. */
        const val GROUP_TAIL = 16
        /** Bump whenever TagExtractor's rules change; forces a local re-extract. */
        const val EXTRACTOR_VERSION = 2
        const val MAX_RETRIES = 5
        const val DEFAULT_BACKOFF_SEC = 5L
        val RETRY_AFTER = Regex("retry after (\\d+)")
    }
}
