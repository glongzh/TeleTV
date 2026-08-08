package com.teletv.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teletv.analytics.Analytics
import com.teletv.analytics.Events
import com.teletv.media.index.IndexState
import com.teletv.media.index.PartGroup
import com.teletv.media.index.PlaybackProgress
import com.teletv.media.index.PlaybackProgressDao
import com.teletv.media.index.TagCategory
import com.teletv.media.index.TagExtractor
import com.teletv.media.index.TagIndexDao
import com.teletv.media.index.TagIndexer
import com.teletv.media.index.TagWithCount
import com.teletv.media.search.SearchIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Which screen is showing. The grid is the home screen; others overlay it. */
sealed interface Screen {
    data object Grid : Screen
    data object Player : Screen
    data object Settings : Screen
    data object SourcePicker : Screen
}

/**
 * The active filter narrowing the grid, or null for the full stream. A tag
 * filter and a search query are alternatives to the same slot — applying one
 * replaces the other (see media-filter-panel's "mutually exclusive" requirement).
 */
sealed interface MediaFilter {
    val display: String
    val count: Int

    data class Tag(
        val tagId: Long,
        val category: TagCategory,
        override val display: String,
        override val count: Int,
    ) : MediaFilter

    data class Search(
        val query: String,
        override val count: Int,
    ) : MediaFilter {
        override val display: String get() = query
    }
}

data class LibraryUiState(
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val selectedIndex: Int = 0,
    val screen: Screen = Screen.Grid,
    val endReached: Boolean = false,
    val error: String? = null,
    val activeSource: MediaSource? = null,
    val activeFilter: MediaFilter? = null,
    val indexState: IndexState = IndexState.NotIndexed,
    /** Watched state of the active source's items, keyed by message id. */
    val progress: Map<Long, PlaybackProgress> = emptyMap(),
    /** Split-group membership of the active source, keyed by message id. */
    val partGroups: Map<Long, PartGroup> = emptyMap(),
)

/**
 * Activity-scoped owner of the active source, media list, pagination cursor,
 * selection, and screen navigation. The grid and player observe this; the player
 * reads only [LibraryUiState.items], so it automatically stays within the active
 * source AND the active tag filter.
 *
 * Two pagination sources swap by filter state (stream cursor ↔ tag id-list), and
 * both carry the active source's chat id. Switching source resets everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaLibraryViewModel(
    private val repo: MediaRepository,
    private val dao: TagIndexDao,
    private val indexer: TagIndexer,
    private val sources: SourceRepository,
    private val progressDao: PlaybackProgressDao,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val search = SearchIndex(dao)

    /** Debounced search-apply; cancelled by a newer keystroke, a tag pick, or a source switch. */
    private var searchJob: Job? = null

    // Unfiltered stream cursor.
    private var nextFrom: Long = 0L
    private var loadingMore = false

    /**
     * Every item paged in, before split groups are folded. Kept because grouping
     * arrives asynchronously: the scan discovers groups after the grid has
     * already rendered, and collapsing has to be recomputed from the originals
     * rather than applied destructively to the visible list.
     *
     * Volatile because the fold reads it off the main thread (see [foldMutex]);
     * it only ever grows by whole-list replacement, so a reader sees either the
     * list before or after a page — never a half-appended one.
     */
    @Volatile
    private var rawItems: List<MediaItem> = emptyList()

    /** Parts fetched by id because they had not been paged in yet. */
    private val partCache = mutableMapOf<Long, MediaItem.Video>()

    // --- fold state ------------------------------------------------------------
    // Folding used to re-run over the whole of rawItems on every page turn, on
    // the main thread. At PAGE=30 a 6k-item source meant 200 full passes, each
    // one rebuilding its lookup maps and sorting every group's members — paging
    // got quadratic, and the stalls landed in the frame. Now the folded prefix
    // is kept and only the newly paged tail is folded, off the main thread.

    private val foldMutex = Mutex()

    /** The prefix of [rawItems] up to [foldedUpTo], already folded. */
    private var foldedItems: List<MediaItem> = emptyList()
    private var foldedUpTo: Int = 0

    /** Ids absorbed into a representative, so a later page skips them. */
    private val foldConsumed = HashSet<Long>()

    /** Every video paged in so far, by id — the fold's part lookup. */
    private val foldPaged = HashMap<Long, MediaItem.Video>()

    /** Group membership keyed by group id, derived from [foldGroups]. */
    private var foldByGroupId: Map<Long, List<PartGroup>> = emptyMap()

    /**
     * The [LibraryUiState.partGroups] the prefix was folded against. The scan
     * discovers groups progressively, so a new map means the whole fold is
     * stale — identity comparison catches that without a separate signal.
     */
    private var foldGroups: Map<Long, PartGroup>? = null

    /** A part could not be fetched, leaving a group unfolded; retry in full. */
    private var foldIncomplete = false

    // Filtered (tag) cursor: the full id list plus how far we've paged into it.
    private var filteredIds: List<Long> = emptyList()
    private var filteredCursor: Int = 0

    private val ready = CompletableDeferred<Unit>()

    private val activeChatId: Long? get() = _state.value.activeSource?.chatId

    init {
        viewModelScope.launch {
            runCatching { sources.resolveInitialSource() }
                .onSuccess { src ->
                    _state.update { it.copy(activeSource = src, indexState = indexer.stateOf(src.chatId)) }
                    indexer.refreshState(src.chatId)
                    // Grouping needs the index, so every source scans itself —
                    // otherwise the grid would look different depending on
                    // whether the user had ever pressed "build index" here.
                    indexer.requestScan(src.chatId)
                    ready.complete(Unit)
                    loadMore()
                }
                .onFailure { e ->
                    ready.completeExceptionally(e)
                    _state.update { it.copy(loading = false, error = e.message ?: "Failed to load media") }
                }
        }
        // Mirror the active source's index state into the ui state. A transition
        // into Indexed (first scan or a sync) invalidates that source's cached
        // search corpus, so newly indexed items become searchable without a
        // restart — the corpus itself is rebuilt lazily on the next search.
        viewModelScope.launch {
            indexer.states.collect { map ->
                val cid = activeChatId ?: return@collect
                val newState = map[cid] ?: IndexState.NotIndexed
                if (newState is IndexState.Indexed && _state.value.indexState !is IndexState.Indexed) {
                    search.invalidate(cid)
                }
                _state.update { it.copy(indexState = newState) }
            }
        }
        // Watched state, re-subscribed per source. Room re-emits on every write,
        // so returning from the player (or an auto-advance completion several
        // items later) refreshes the cells with no poke from the player.
        viewModelScope.launch {
            _state.map { it.activeSource?.chatId }
                .distinctUntilChanged()
                .flatMapLatest { chatId ->
                    if (chatId == null) flowOf(emptyList()) else progressDao.observeChat(chatId)
                }
                .collect { rows ->
                    _state.update { st -> st.copy(progress = rows.associateBy { it.messageId }) }
                }
        }
        // Split groups, re-subscribed per source. The scan discovers them after
        // the grid has already rendered, so each emission re-folds the raw list —
        // that is what makes groups collapse in progressively on a first visit.
        viewModelScope.launch {
            _state.map { it.activeSource?.chatId }
                .distinctUntilChanged()
                .flatMapLatest { chatId ->
                    if (chatId == null) flowOf(emptyList()) else dao.observeGroups(chatId)
                }
                .collect { rows ->
                    val map = rows.associateBy { it.messageId }
                    // Room re-emits on every write to the table and a scan writes
                    // many times over; an unchanged map would throw away the
                    // folded prefix and refold the whole list for nothing.
                    if (map == _state.value.partGroups) return@collect
                    _state.update { st -> st.copy(partGroups = map) }
                    recomputeItems()
                }
        }
    }

    /** Returns true if new items were appended. */
    suspend fun loadMore(): Boolean {
        if (loadingMore || _state.value.endReached) return false
        loadingMore = true
        return try {
            ready.await()
            if (_state.value.activeFilter != null) loadMoreFiltered() else loadMoreStream()
        } catch (e: Exception) {
            if (ready.isCompleted && !ready.isCancelled) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load page") }
            }
            false
        } finally {
            loadingMore = false
        }
    }

    private suspend fun loadMoreStream(): Boolean {
        val chatId = activeChatId ?: return false
        val page = repo.loadPage(chatId, nextFrom)
        return if (page.items.isEmpty()) {
            _state.update { it.copy(loading = false, endReached = true) }
            false
        } else {
            nextFrom = page.nextFromMessageId
            rawItems = rawItems + page.items
            _state.update {
                it.copy(
                    loading = false,
                    endReached = page.nextFromMessageId == 0L,
                    error = null,
                )
            }
            recomputeItems()
            true
        }
    }

    private suspend fun loadMoreFiltered(): Boolean {
        val chatId = activeChatId ?: return false
        if (filteredCursor >= filteredIds.size) {
            _state.update { it.copy(loading = false, endReached = true) }
            return false
        }
        val slice = filteredIds.subList(filteredCursor, minOf(filteredCursor + PAGE, filteredIds.size))
        val result = repo.loadByIds(chatId, slice)
        filteredCursor += slice.size
        if (result.missingIds.isNotEmpty()) {
            viewModelScope.launch { dao.pruneMessages(chatId, result.missingIds) }
        }
        val end = filteredCursor >= filteredIds.size
        rawItems = rawItems + result.items
        _state.update { it.copy(loading = false, endReached = end, error = null) }
        recomputeItems()
        if (result.items.isEmpty() && !end) return loadMoreFiltered()
        return result.items.isNotEmpty()
    }

    fun requestLoadMore() {
        viewModelScope.launch { loadMore() }
    }

    // --- split-group collapsing -----------------------------------------------

    private suspend fun recomputeItems() {
        val chatId = activeChatId ?: return
        val collapsed = foldMutex.withLock {
            withContext(Dispatchers.Default) { collapse(chatId) }
        }
        _state.update { it.copy(items = collapsed) }
    }

    /**
     * Fold each detected group into a single item. The group's newest part is
     * the representative — the stream is newest-first, so that keeps a freshly
     * uploaded film at the top instead of sinking it to its oldest part — while
     * playback and the group's identity start from part 1.
     *
     * Only the tail of [rawItems] past [foldedUpTo] is folded; the prefix is
     * reused as-is. That is sound because a representative is the newest of its
     * parts and the stream is newest-first, so a group's representative is
     * always reached before the parts it absorbs — appending a page can never
     * change how an earlier item folded. Anything that breaks the assumption
     * (new groups, a source or filter switch, a part that failed to fetch)
     * resets the prefix instead. Callers hold [foldMutex].
     */
    private suspend fun collapse(chatId: Long): List<MediaItem> {
        val raw = rawItems
        val groups = _state.value.partGroups
        if (groups.isEmpty()) return raw
        if (groups !== foldGroups) {
            resetFold()
            foldGroups = groups
            foldByGroupId = groups.values.groupBy { it.groupId }
        } else if (foldIncomplete) {
            // A part was unreachable last time, so its group stayed unfolded.
            // Only a full pass can pick it up now that the fetch may succeed.
            val cached = foldByGroupId
            resetFold()
            foldGroups = groups
            foldByGroupId = cached
        }
        if (foldedUpTo >= raw.size) return foldedItems

        val byGroupId = foldByGroupId
        val tail = raw.subList(foldedUpTo, raw.size)
        tail.forEach { if (it is MediaItem.Video) foldPaged[it.messageId] = it }

        // A group's other parts may live on a page the user has not reached, or
        // on none at all in filtered mode; fetch them once, in a single batch.
        val missing = LinkedHashSet<Long>()
        for (item in tail) {
            val members = byGroupId[groups[item.messageId]?.groupId].orEmpty()
            if (members.size < 2 || item.messageId != members.maxOf { it.messageId }) continue
            members.forEach { m ->
                if (m.messageId !in foldPaged && m.messageId !in partCache) missing += m.messageId
            }
        }
        if (missing.isNotEmpty()) {
            runCatching { repo.loadByIds(chatId, missing.toList()) }.getOrNull()?.items
                ?.filterIsInstance<MediaItem.Video>()
                ?.forEach { partCache[it.messageId] = it }
        }

        // Copied rather than appended in place: the previous list is already
        // published to the grid, and mutating it under Compose is not safe.
        // A memcpy per page is nothing against the per-item work above.
        val out = ArrayList<MediaItem>(foldedItems.size + tail.size)
        out.addAll(foldedItems)
        for (item in tail) {
            if (item.messageId in foldConsumed) continue
            val members = byGroupId[groups[item.messageId]?.groupId].orEmpty().sortedBy { it.partIndex }
            if (item !is MediaItem.Video || members.size < 2) {
                out.add(item); continue
            }
            if (item.messageId != members.maxOf { it.messageId }) {
                foldConsumed.add(item.messageId); continue // absorbed by its representative
            }
            val ordered = members.mapNotNull { foldPaged[it.messageId] ?: partCache[it.messageId] }
            if (ordered.size != members.size) {
                foldIncomplete = true
                out.add(item); continue // a part is missing; leave it as a plain item
            }
            foldConsumed.addAll(members.map { it.messageId })
            out.add(repo.mergeGroup(item, ordered))
        }
        foldedItems = out
        foldedUpTo = raw.size
        return out
    }

    /** Drop the folded prefix; the next [collapse] rebuilds it from scratch. */
    private fun resetFold() {
        foldedItems = emptyList()
        foldedUpTo = 0
        foldConsumed.clear()
        foldPaged.clear()
        foldByGroupId = emptyMap()
        foldGroups = null
        foldIncomplete = false
    }

    private suspend fun resetItems() = foldMutex.withLock {
        rawItems = emptyList()
        partCache.clear()
        resetFold()
    }

    // --- source switching -----------------------------------------------------

    fun openSourcePicker() {
        _state.update { it.copy(screen = Screen.SourcePicker) }
    }

    /** Switch the active source, resetting the grid. Gated for non-default sources. */
    fun switchSource(source: MediaSource) {
        if (source.isProtected || !sources.canUse(source)) return
        if (source.chatId == activeChatId) {
            _state.update { it.copy(screen = Screen.Grid) }
            return
        }
        searchJob?.cancel()
        viewModelScope.launch {
            ready.await()
            nextFrom = 0L
            filteredIds = emptyList()
            filteredCursor = 0
            resetItems()
            _state.update {
                it.copy(
                    activeSource = source,
                    items = emptyList(),
                    selectedIndex = 0,
                    endReached = false,
                    loading = true,
                    error = null,
                    activeFilter = null,
                    indexState = indexer.stateOf(source.chatId),
                    screen = Screen.Grid,
                )
            }
            Analytics.capture(
                Events.SOURCE_SWITCHED,
                mapOf("indexed" to (indexer.stateOf(source.chatId) is IndexState.Indexed)),
            )
            sources.saveLastSource(source.chatId)
            indexer.refreshState(source.chatId)
            indexer.requestScan(source.chatId)
            loadMore()
        }
    }

    // --- filtering ------------------------------------------------------------

    /** Tags of a category with counts, for the filter panel (active source). */
    suspend fun tagsFor(category: TagCategory): List<TagWithCount> {
        val chatId = activeChatId ?: return emptyList()
        // Only TERM is frequency-mined. NAME deliberately is not: a performer
        // appears in one title, which is exactly what makes the facet useful.
        val min = if (category == TagCategory.TERM) TERM_MIN_COUNT else 1
        val limit = if (category == TagCategory.TERM) TERM_LIMIT else Int.MAX_VALUE
        return dao.tagsForCategory(chatId, category.name, min, limit)
    }

    fun applyFilter(filter: MediaFilter.Tag) {
        if ((_state.value.activeFilter as? MediaFilter.Tag)?.tagId == filter.tagId) {
            clearFilter(); return
        }
        searchJob?.cancel() // a tag pick replaces an in-flight or active search
        viewModelScope.launch {
            ready.await()
            filteredIds = dao.messageIdsForTag(filter.tagId)
            filteredCursor = 0
            resetItems()
            // Category and size only — a tag's display text is mined from the
            // user's own file names and is theirs, not ours.
            Analytics.capture(
                Events.FILTER_APPLIED,
                mapOf("category" to filter.category.name, "match_count" to filteredIds.size),
            )
            _state.update {
                it.copy(
                    items = emptyList(),
                    selectedIndex = 0,
                    endReached = false,
                    loading = true,
                    error = null,
                    activeFilter = filter,
                )
            }
            loadMore()
        }
    }

    fun clearFilter() {
        searchJob?.cancel()
        val cleared = _state.value.activeFilter ?: return
        Analytics.capture(
            Events.FILTER_CLEARED,
            mapOf("kind" to if (cleared is MediaFilter.Search) "search" else "tag"),
        )
        viewModelScope.launch {
            ready.await()
            nextFrom = 0L
            filteredIds = emptyList()
            filteredCursor = 0
            resetItems()
            _state.update {
                it.copy(
                    items = emptyList(),
                    selectedIndex = 0,
                    endReached = false,
                    loading = true,
                    error = null,
                    activeFilter = null,
                )
            }
            loadMore()
        }
    }

    /**
     * Live search, debounced: a query shorter than [SearchIndex.MIN_QUERY_LENGTH]
     * (after the same width/case normalization the matcher uses) performs no
     * search and, if a search was the active filter, clears it — leaving a tag
     * filter (if any) alone, since starting a search is what replaces a tag
     * filter, not merely typing below the threshold.
     */
    fun applySearch(query: String) {
        searchJob?.cancel()
        val normalized = TagExtractor.normalizeWidth(query).trim()
        if (normalized.length < SearchIndex.MIN_QUERY_LENGTH) {
            if (_state.value.activeFilter is MediaFilter.Search) clearFilter()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            ready.await()
            val chatId = activeChatId ?: return@launch
            val ids = search.search(chatId, normalized, _state.value.partGroups)
            filteredIds = ids
            filteredCursor = 0
            resetItems()
            // Length and outcome, never the query itself — the whole point of
            // the search box is finding the user's own private media.
            Analytics.capture(
                Events.SEARCH_PERFORMED,
                mapOf(
                    "query_length" to normalized.length,
                    "result_count" to ids.size,
                    "has_results" to ids.isNotEmpty(),
                ),
            )
            _state.update {
                it.copy(
                    items = emptyList(),
                    selectedIndex = 0,
                    endReached = false,
                    loading = true,
                    error = null,
                    activeFilter = MediaFilter.Search(query = query, count = ids.size),
                )
            }
            loadMore()
        }
    }

    /** OK on a grid cell: remember the cell and enter the player there. */
    fun select(index: Int) {
        _state.update { it.copy(selectedIndex = index, screen = Screen.Player) }
    }

    fun moveSelection(delta: Int) {
        val s = _state.value
        val target = s.selectedIndex + delta
        when {
            target < 0 -> Unit
            target <= s.items.lastIndex ->
                _state.update { it.copy(selectedIndex = target) }
            else -> viewModelScope.launch {
                if (loadMore()) {
                    _state.update { st ->
                        st.copy(selectedIndex = (st.selectedIndex + delta).coerceAtMost(st.items.lastIndex))
                    }
                } else {
                    backToGrid()
                }
            }
        }
    }

    fun backToGrid() {
        _state.update { it.copy(screen = Screen.Grid) }
    }

    fun openSettings() {
        _state.update { it.copy(screen = Screen.Settings) }
    }

    // --- index actions (active source) ----------------------------------------

    /** Build (first scan) or sync (incremental) the active source's index. */
    fun buildOrSyncIndex() {
        activeChatId?.let {
            Analytics.capture(Events.INDEX_SCAN_REQUESTED, mapOf("mode" to "scan"))
            indexer.requestScan(it)
        }
    }

    /** Wipe and re-scan the active source's index. */
    fun rebuildIndex() {
        activeChatId?.let {
            Analytics.capture(Events.INDEX_SCAN_REQUESTED, mapOf("mode" to "rebuild"))
            indexer.rebuildSource(it)
        }
    }

    class Factory(
        private val repo: MediaRepository,
        private val dao: TagIndexDao,
        private val indexer: TagIndexer,
        private val sources: SourceRepository,
        private val progressDao: PlaybackProgressDao,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MediaLibraryViewModel(repo, dao, indexer, sources, progressDao) as T
    }

    private companion object {
        const val PAGE = 30
        /**
         * Minimum distinct ITEMS a term must appear in to be offered as a filter.
         *
         * Was 3, but that was calibrated against counts inflated by split videos:
         * a film in three parts pushed its own title over the bar by itself. Now
         * that a group counts once, the honest equivalent of "not a one-off" is
         * two distinct items — on a real library this restored the panel from 11
         * terms to 27, against 25 before the dedupe.
         */
        const val TERM_MIN_COUNT = 2
        const val TERM_LIMIT = 100
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
