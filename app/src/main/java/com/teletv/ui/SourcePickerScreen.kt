package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.media.MediaLibraryViewModel
import com.teletv.media.MediaSource
import com.teletv.media.index.IndexState
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens
import kotlinx.coroutines.launch

private sealed interface PickerRow {
    data class Header(val titleRes: Int) : PickerRow
    data class Source(val source: MediaSource, val favorite: Boolean) : PickerRow
}

/**
 * Full-screen source picker: pinned Saved Messages, favorites, and recent joined
 * chats. Selecting a source switches to it; a star toggles favorites. Protected
 * chats are greyed out and cannot be selected.
 */
@Composable
fun SourcePickerScreen(
    library: MediaLibraryViewModel,
    onDone: () -> Unit,
) {
    val state by library.state.collectAsStateWithLifecycle()
    val indexStates by ServiceLocator.indexer.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<PickerRow>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val firstRow = remember { FocusRequester() }

    LaunchedEffect(reload) {
        val saved = ServiceLocator.sources.savedSource()
        val favorites = ServiceLocator.sources.favorites()
        val favoriteIds = favorites.map { it.chatId }.toSet()
        val recents = runCatching { ServiceLocator.sources.recents() }.getOrDefault(emptyList())
            .filter { it.chatId !in favoriteIds }
        // Populate index status for everything shown.
        (listOf(saved) + favorites + recents).forEach { ServiceLocator.indexer.refreshState(it.chatId) }

        rows = buildList {
            add(PickerRow.Source(saved, favorite = false))
            if (favorites.isNotEmpty()) {
                add(PickerRow.Header(R.string.favorites))
                favorites.forEach { add(PickerRow.Source(it, favorite = true)) }
            }
            if (recents.isNotEmpty()) {
                add(PickerRow.Header(R.string.recent_chats))
                recents.forEach { add(PickerRow.Source(it, favorite = false)) }
            }
        }
    }

    BackHandler { onDone() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.Bg)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Text(stringResource(R.string.choose_source), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(0.dp))

        val current = rows
        if (current == null) {
            Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Text(stringResource(R.string.loading_chats), style = MaterialTheme.typography.bodyMedium, color = TeleTvColors.Muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(current) { row ->
                    when (row) {
                        is PickerRow.Header -> Text(
                            stringResource(row.titleRes).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TeleTvColors.Muted,
                            modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 4.dp),
                        )
                        is PickerRow.Source -> SourceRow(
                            source = row.source,
                            favorite = row.favorite,
                            active = row.source.chatId == state.activeSource?.chatId,
                            status = stringResource(indexStatusLabel(indexStates[row.source.chatId])),
                            firstModifier = if (current.indexOf(row) == 0)
                                Modifier.focusRequester(firstRow) else Modifier,
                            onSelect = { if (!row.source.isProtected) library.switchSource(row.source) },
                            onToggleFavorite = if (row.source.isDefault) null else {
                                {
                                    scope.launch {
                                        if (row.favorite) ServiceLocator.sources.removeFavorite(row.source.chatId)
                                        else ServiceLocator.sources.addFavorite(row.source)
                                        reload++
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(rows != null) {
        if (rows != null) runCatching { firstRow.requestFocus() }
    }
}

private fun indexStatusLabel(state: IndexState?): Int = when (state) {
    is IndexState.Indexing -> R.string.status_indexing
    is IndexState.Indexed -> R.string.status_indexed
    is IndexState.Failed -> R.string.status_index_failed
    else -> R.string.status_not_indexed
}

@Composable
private fun SourceRow(
    source: MediaSource,
    favorite: Boolean,
    active: Boolean,
    status: String,
    firstModifier: Modifier,
    onSelect: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
) {
    if (source.isProtected) {
        // Greyed, non-focusable: visible but not selectable.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
                .alpha(0.4f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("🔒 ${source.title}", style = MaterialTheme.typography.bodyLarge, color = TeleTvColors.OnBg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.protected_source), style = MaterialTheme.typography.labelSmall, color = TeleTvColors.Muted)
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectableCell(
            modifier = Modifier.weight(1f).then(firstModifier),
            active = active,
            onClick = onSelect,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    if (active) Text("● ", style = MaterialTheme.typography.bodyLarge, color = TeleTvColors.Accent)
                    Text(
                        (if (source.isDefault) "★ " else "") + source.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(status, style = MaterialTheme.typography.labelSmall, color = TeleTvColors.Muted)
            }
        }
        if (onToggleFavorite != null) {
            Spacer(Modifier.width(8.dp))
            SelectableCell(active = false, onClick = onToggleFavorite) {
                Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A focusable, clickable cell that highlights on focus; shared by the picker rows. */
@Composable
private fun SelectableCell(
    modifier: Modifier = Modifier,
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> TeleTvColors.Accent
        active -> TeleTvColors.SurfaceHigh
        else -> TeleTvColors.Surface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}
