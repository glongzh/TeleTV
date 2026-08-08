package com.teletv.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.media.MediaFilter
import com.teletv.media.MediaItem
import com.teletv.media.MediaLibraryViewModel
import com.teletv.media.index.PlaybackProgress
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens
import com.teletv.ui.theme.TeleTvFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale

private const val COLUMNS = 4
private const val THUMB_DOWNLOAD_PRIORITY = 16 // below player media (32)

/** Message ids are per-chat, so a cache key has to carry the chat too. */
private data class ThumbKey(val chatId: Long, val messageId: Long)

/**
 * Thumbnail work that has to outlive a cell.
 *
 * A LazyGrid cell is disposed the moment it scrolls out of the window, so
 * anything held in `remember` is redone on the way back: the minithumb decoded
 * again on the composition thread, the TDLib download requested again.
 *
 * Bounded, because decoded minithumbs are real memory — TDLib caps them near
 * 40px, so ~6KB each, and 256 entries is about 1.5MB.
 */
private val miniThumbCache = LruCache<ThumbKey, ImageBitmap>(256)
private val thumbPathCache = LruCache<ThumbKey, String>(1024)

@Composable
fun GridScreen(library: MediaLibraryViewModel) {
    val state by library.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val restoreFocus = remember { FocusRequester() }
    var panelOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var exitConfirmOpen by remember { mutableStateOf(false) }
    // Track which cell holds focus so LEFT from the leftmost column opens the panel.
    var focusedIndex by remember { mutableStateOf(0) }
    val filter = state.activeFilter
    val activity = LocalContext.current as? Activity

    // GridScreen otherwise had no BackHandler at all, so BACK fell straight
    // through to exiting the app — a single stray press could quit outright.
    // Now: a filter (tag or search) is cleared first; with nothing to clear,
    // BACK asks for confirmation instead of exiting immediately. The search
    // dialog's own BackHandler (composed later, deeper) still intercepts first
    // while it's open, so the full sequence is: close dialog, clear filter,
    // confirm, exit.
    BackHandler {
        if (filter != null) library.clearFilter() else exitConfirmOpen = true
    }

    Box(modifier = Modifier.fillMaxSize().background(TeleTvColors.Bg)) {
        // Only the exit confirmation blurs its backdrop; the filter panel and
        // search dialog keep their own scrim treatment untouched.
        Column(modifier = Modifier.fillMaxSize().modalBackdrop(exitConfirmOpen)) {
            // Branded top bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        state.activeSource?.title ?: "TeleTV",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (filter != null) {
                        Spacer(Modifier.width(16.dp))
                        ActiveFilterChip(label = filter.display, count = filter.count)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { library.openSourcePicker() }) {
                        Text(stringResource(R.string.btn_source), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { searchOpen = true }) {
                        Text(stringResource(R.string.btn_search), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { panelOpen = true }) {
                        Text(stringResource(R.string.btn_filter), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { library.openSettings() }) {
                        Text(stringResource(R.string.btn_settings), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            when {
                state.loading && state.items.isEmpty() -> SkeletonGrid()
                state.error != null && state.items.isEmpty() -> CenterMessage(state.error ?: "")
                filter != null && state.items.isEmpty() && state.endReached ->
                    CenterMessage(stringResource(R.string.no_media_for_filter, filter.display))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = TeleTvDimens.GridHorizontalPadding)
                        // LEFT from the leftmost column has nowhere to go — use it to open the panel.
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown &&
                                e.key == Key.DirectionLeft &&
                                focusedIndex % COLUMNS == 0
                            ) {
                                panelOpen = true; true
                            } else false
                        },
                ) {
                    items(state.items.size, key = { state.items[it].messageId }) { index ->
                        val item = state.items[index]
                        MediaCell(
                            item = item,
                            chatId = state.activeSource?.chatId ?: 0L,
                            // Groups record progress under the first part's id.
                            progress = state.progress[
                                (item as? MediaItem.Video)?.groupId ?: item.messageId
                            ],
                            onClick = { library.select(index) },
                            onFocused = { focusedIndex = index },
                            modifier = if (index == state.selectedIndex)
                                Modifier.focusRequester(restoreFocus) else Modifier,
                        )
                    }
                    if (state.endReached) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (filter != null) stringResource(R.string.end_of_filter, filter.display)
                                    else stringResource(R.string.end_of_source),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TeleTvColors.Muted,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (panelOpen) {
            FilterPanel(
                library = library,
                onDismiss = { panelOpen = false },
                onApplied = { panelOpen = false },
            )
        }

        if (searchOpen) {
            SearchDialog(
                library = library,
                initialQuery = (filter as? MediaFilter.Search)?.query ?: "",
                onDismiss = { searchOpen = false },
            )
        }

        if (exitConfirmOpen) {
            ExitConfirmDialog(
                onConfirm = { activity?.finish() },
                onDismiss = { exitConfirmOpen = false },
            )
        }
    }

    // Pagination: focus reaching the last rows loads the next (older) page.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= library.state.value.items.size - COLUMNS * 2) {
                    library.requestLoadMore()
                }
            }
    }

    // Focus restoration: land on the previously selected cell when (re)entering.
    LaunchedEffect(state.items.isNotEmpty()) {
        if (state.items.isNotEmpty()) {
            runCatching {
                gridState.scrollToItem(state.selectedIndex.coerceIn(0, state.items.lastIndex))
                restoreFocus.requestFocus()
            }
        }
    }
}

/** Shimmering placeholder cells shown before the first page arrives. */
@Composable
private fun SkeletonGrid() {
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize().padding(horizontal = TeleTvDimens.GridHorizontalPadding),
    ) {
        items(COLUMNS * 2) {
            Box(
                modifier = Modifier
                    .padding(TeleTvDimens.CellSpacing)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(TeleTvDimens.RadiusCell))
                    .alpha(shimmer)
                    .background(TeleTvColors.Surface),
            )
        }
    }
}

/** How far the content behind a modal is pushed out of focus. */
private val ModalBlurRadius = 20.dp

/**
 * Backdrop treatment for whatever sits behind a modal.
 *
 * [blur] is backed by RenderEffect, which the platform only provides from API
 * 31. minSdk here is 21, so on an older TV box the call is silently a no-op and
 * the backdrop would get no treatment at all — those devices dim instead.
 */
private fun Modifier.modalBackdrop(active: Boolean): Modifier = when {
    !active -> this
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> blur(ModalBlurRadius)
    else -> drawWithContent {
        drawContent()
        drawRect(TeleTvColors.ModalDim)
    }
}

/**
 * Centered confirmation shown on the exiting BACK press (nothing left to clear,
 * no dialog open) — one stray remote press no longer quits the app outright.
 * BACK while this is open dismisses it, same as the other overlays' BackHandler.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // focusProperties { exit = ... }
@Composable
private fun ExitConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Defaults to Cancel, not Exit: an overlay with no initial focus at all
    // leaves D-pad OK with nothing to act on, and defaulting the focused
    // control to the destructive option would make a stray OK press exit.
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.OverlayScrim)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                // Focus was free to walk back out into the grid behind, which
                // the blur now makes obvious: a focus ring drifting across
                // out-of-focus cells. Trapped the same way FilterPanel does it.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .clip(RoundedCornerShape(TeleTvDimens.RadiusCard))
                .background(TeleTvColors.Surface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.exit_confirm_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                    Text(stringResource(R.string.exit_confirm_no))
                }
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.exit_confirm_yes))
                }
            }
        }
    }
}

/** Small pill in the top bar showing the active filter and its item count. */
@Composable
private fun ActiveFilterChip(label: String, count: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
            .background(TeleTvColors.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⛃ ", style = MaterialTheme.typography.labelMedium, color = TeleTvColors.Accent)
        Text(
            "$label · $count",
            style = MaterialTheme.typography.labelMedium,
            color = TeleTvColors.OnBg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MediaCell(
    item: MediaItem,
    chatId: Long,
    progress: PlaybackProgress?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = remember(chatId, item.messageId) { ThumbKey(chatId, item.messageId) }

    // Tier 1: instant blurred placeholder from embedded minithumbnail bytes.
    // Seeded from the cache so a cell scrolled back into view paints on its
    // first frame; only a miss pays for a decode, and that happens off the
    // composition thread rather than inside the frame.
    var miniBitmap by remember(key) { mutableStateOf(miniThumbCache.get(key)) }
    LaunchedEffect(key) {
        if (miniBitmap != null) return@LaunchedEffect
        val bytes = item.minithumbBytes ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }?.asImageBitmap() ?: return@LaunchedEffect
        miniThumbCache.put(key, decoded)
        miniBitmap = decoded
    }

    // Tier 2: the real (small) thumbnail file, downloaded lazily at low priority.
    var thumbPath by remember(key) { mutableStateOf(thumbPathCache.get(key)) }
    LaunchedEffect(key) {
        if (thumbPath != null) return@LaunchedEffect
        val id = item.thumbFileId ?: return@LaunchedEffect
        var settled = false
        try {
            val file = ServiceLocator.client.send<TdApi.File>(
                TdApi.DownloadFile(id, THUMB_DOWNLOAD_PRIORITY, 0, 0, true)
            )
            val path = file.local?.path?.takeIf { it.isNotEmpty() }
            if (path != null) {
                thumbPathCache.put(key, path)
                thumbPath = path
                settled = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No thumbnail to be had; the minithumb stands in for it.
        } finally {
            // Cancelling this coroutine only abandons the wait — TDLib keeps
            // working the download it was already handed. Without this, a fast
            // scroll through a large channel leaves thousands of queued
            // thumbnail downloads competing with the player's own traffic.
            if (!settled) withContext(NonCancellable) {
                runCatching {
                    ServiceLocator.client.send<TdApi.Ok>(TdApi.CancelDownloadFile(id, false))
                }
            }
        }
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .padding(TeleTvDimens.CellSpacing)
            .aspectRatio(16f / 9f)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(TeleTvDimens.RadiusCell)),
        scale = TeleTvFocus.cardScale(),
        border = TeleTvFocus.cardBorder(),
        glow = TeleTvFocus.cardGlow(),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(TeleTvColors.Surface)) {
            miniBitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            thumbPath?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = item.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Duration, and the part count beneath it. Stacked in the top-right
            // corner rather than opposite the label: the label strip spans the
            // full width, so anything along the bottom edge lands on top of it.
            if (item is MediaItem.Video) {
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
                            .background(TeleTvColors.ChipBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            // Aggregate duration for a group — the cell stands for
                            // the whole film, not its representative part.
                            "▶ ${formatDuration(item.durationSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TeleTvColors.OnBg,
                        )
                    }
                    if (item.isGroup) {
                        // A footnote about how the file arrived, not something to
                        // read before the title — so: smaller than the duration,
                        // muted rather than accented, and no word attached.
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
                                .background(TeleTvColors.ChipBg)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                "⧉${item.parts.size}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = TeleTvColors.Muted,
                            )
                        }
                    }
                }
                // Watched marker: the opposite corner from the duration chip, so
                // neither displaces the other. Same chip treatment, accent text.
                if (progress?.completed == true) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
                            .background(TeleTvColors.ChipBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "✓ ${stringResource(R.string.watched)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TeleTvColors.Accent,
                        )
                    }
                }
            }
            // Label strip on a bottom scrim.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(TeleTvColors.LabelScrim)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TeleTvColors.OnBg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Watched bar, flush with the bottom edge and drawn last so it sits
            // over the label scrim's padding — never over the label text itself.
            if (progress != null && !progress.completed && progress.durationMs > 0L) {
                val watched = (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x40FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(watched)
                            .fillMaxHeight()
                            .background(TeleTvColors.AccentGradient),
                    )
                }
            }
        }
    }
}

fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
