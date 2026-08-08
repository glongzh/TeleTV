package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import com.teletv.R
import androidx.tv.material3.Text
import com.teletv.media.MediaFilter
import com.teletv.media.MediaLibraryViewModel
import com.teletv.media.index.IndexState
import com.teletv.media.index.TagCategory
import com.teletv.media.index.TagWithCount
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens

private data class PanelSection(val category: TagCategory, val titleRes: Int, val tags: List<TagWithCount>)

/** A single row in the panel: the "All" entry, a chip group, or the index actions. */
private sealed interface Row {
    data object All : Row
    data class Header(val titleRes: Int) : Row
    data class TagGroup(val category: TagCategory, val tags: List<TagWithCount>) : Row
    data object IndexActions : Row
}

private val CATEGORY_ORDER = listOf(
    TagCategory.TYPE to R.string.category_type,
    TagCategory.CODE to R.string.category_code,
    TagCategory.NAME to R.string.category_name,
    TagCategory.TERM to R.string.category_tags,
    TagCategory.YEAR to R.string.category_year,
)

/**
 * Left-drawer filter panel. Lists tags grouped by category with per-tag counts;
 * OK applies a single tag filter and closes the drawer, BACK closes without
 * change. Rebuilt from the index each time it opens so counts stay fresh.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FilterPanel(
    library: MediaLibraryViewModel,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    val state by library.state.collectAsStateWithLifecycle()
    var sections by remember { mutableStateOf<List<PanelSection>?>(null) }
    val firstRow = remember { FocusRequester() }

    // Reload tags when the panel opens and again when a scan completes (so newly
    // built tags appear live), but not on every progress tick.
    LaunchedEffect(state.indexState is IndexState.Indexed) {
        sections = CATEGORY_ORDER.mapNotNull { (cat, titleRes) ->
            val tags = library.tagsFor(cat)
            if (tags.isEmpty()) null else PanelSection(cat, titleRes, tags)
        }
    }

    BackHandler { onDismiss() }

    // Flatten sections into a single focusable row list, with the "All" entry first.
    val rows = remember(sections) {
        buildList {
            add(Row.All)
            sections?.forEach { sec ->
                add(Row.Header(sec.titleRes))
                add(Row.TagGroup(sec.category, sec.tags))
            }
            if (sections != null) add(Row.IndexActions)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim over the grid; clicking it (or BACK) dismisses.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TeleTvColors.OverlayScrim)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onDismiss() },
        )

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(360.dp)
                .background(TeleTvColors.Surface)
                // Trap D-pad focus inside the drawer: any focus search that would
                // land outside (e.g. RIGHT onto the grid's Source button) is cancelled.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(
                stringResource(R.string.filter_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
            IndexProgressRow(state.indexState)
            Spacer(Modifier.width(0.dp))

            if (sections == null) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.reading_index), style = MaterialTheme.typography.labelMedium, color = TeleTvColors.Muted)
                }
            } else {
                if (sections!!.isEmpty() && state.indexState is IndexState.Indexed) {
                    Text(
                        stringResource(R.string.no_tags_for_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = TeleTvColors.Muted,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows) { row ->
                        when (row) {
                            Row.All -> FilterRow(
                                label = stringResource(R.string.all_media),
                                count = null,
                                selected = state.activeFilter == null,
                                modifier = Modifier.focusRequester(firstRow),
                                onClick = { library.clearFilter(); onApplied() },
                            )
                            is Row.Header -> SectionHeader(stringResource(row.titleRes))
                            is Row.TagGroup -> FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.tags.forEach { tag ->
                                    TagChip(
                                        label = tag.display,
                                        count = tag.count,
                                        selected = (state.activeFilter as? MediaFilter.Tag)?.tagId == tag.id,
                                        onClick = {
                                            library.applyFilter(
                                                MediaFilter.Tag(
                                                    tagId = tag.id,
                                                    category = row.category,
                                                    display = tag.display,
                                                    count = tag.count,
                                                )
                                            )
                                            onApplied()
                                        },
                                    )
                                }
                            }
                            Row.IndexActions -> IndexActions(
                                indexState = state.indexState,
                                onBuildOrSync = { library.buildOrSyncIndex() },
                                onRebuild = { library.rebuildIndex() },
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(rows.size) {
        if (rows.size > 1) runCatching { firstRow.requestFocus() }
    }
}

@Composable
private fun IndexProgressRow(indexState: IndexState) {
    val text = when (indexState) {
        is IndexState.Indexing ->
            if (indexState.total > 0) stringResource(R.string.indexing_progress, indexState.indexed, indexState.total)
            else stringResource(R.string.indexing_count, indexState.indexed)
        is IndexState.Failed -> stringResource(R.string.indexing_failed)
        else -> null
    }
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (indexState is IndexState.Failed) TeleTvColors.Error else TeleTvColors.Accent,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
    }
}

/** Per-source index actions, adapting to the active source's index state. */
@Composable
private fun IndexActions(
    indexState: IndexState,
    onBuildOrSync: () -> Unit,
    onRebuild: () -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.section_index))
        when (indexState) {
            is IndexState.NotIndexed -> FilterRow(
                label = stringResource(R.string.build_tag_index),
                count = null,
                selected = false,
                onClick = onBuildOrSync,
            )
            is IndexState.Indexed -> {
                FilterRow(label = stringResource(R.string.sync_new_items), count = null, selected = false, onClick = onBuildOrSync)
                FilterRow(label = stringResource(R.string.rebuild_index), count = null, selected = false, onClick = onRebuild)
            }
            is IndexState.Failed -> FilterRow(
                label = stringResource(R.string.retry_indexing),
                count = null,
                selected = false,
                onClick = onBuildOrSync,
            )
            is IndexState.Indexing -> Unit // progress shown above; no action
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TeleTvColors.Muted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** Compact content-sized chip used inside a tag FlowRow. */
@Composable
private fun TagChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> TeleTvColors.Accent
        selected -> TeleTvColors.SurfaceHigh
        else -> TeleTvColors.Bg
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text("● ", style = MaterialTheme.typography.labelSmall, color = if (focused) TeleTvColors.Bg else TeleTvColors.Accent)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) TeleTvColors.Bg else TeleTvColors.OnBg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Spacer(Modifier.width(5.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) TeleTvColors.Bg else TeleTvColors.Muted,
            )
        }
    }
}

@Composable
private fun FilterRow(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> TeleTvColors.Accent
        selected -> TeleTvColors.SurfaceHigh
        else -> TeleTvColors.Bg
    }
    val fg = if (focused) TeleTvColors.Bg else TeleTvColors.OnBg
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
            if (selected) {
                Text("● ", style = MaterialTheme.typography.labelMedium, color = if (focused) TeleTvColors.Bg else TeleTvColors.Accent)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) TeleTvColors.Bg else TeleTvColors.Muted,
            )
        }
    }
}
