package com.teletv.ui

import android.app.Application
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.media.MediaItem
import com.teletv.media.MediaLibraryViewModel
import com.teletv.player.PlayerViewModel
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

private const val SEEK_STEP_MS = 10_000L
private const val OVERLAY_HIDE_DELAY_MS = 3_000L

/** Held past this, a direction key becomes a scrub instead of a discrete step. */
private const val HOLD_THRESHOLD_MS = 250L
/** Cursor advances on this cadence — deliberately not on OS key-repeat events. */
private const val SCRUB_TICK_MS = 16L
/** A dropped key-release must not strand the player in scrub mode forever. */
private const val SCRUB_SAFETY_TIMEOUT_MS = 15_000L

@UnstableApi
@Composable
fun PlayerScreen(library: MediaLibraryViewModel) {
    val app = LocalContext.current.applicationContext as Application
    val vm: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(
            app, ServiceLocator.client, library, ServiceLocator.progressDao, ServiceLocator.appScope
        )
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // An open LEFT/RIGHT gesture. `promoted` distinguishes a tap (released before
    // the hold threshold, still a discrete ±10 s step) from a scrub.
    var gestureJob by remember { mutableStateOf<Job?>(null) }
    var gesturePromoted by remember { mutableStateOf(false) }

    // Overlay lifecycle: bumping the tick summons it; it hides itself ~3 s later.
    var overlayTick by remember { mutableLongStateOf(0L) }
    var overlayVisible by remember { mutableStateOf(false) }
    fun summonOverlay() {
        overlayTick = System.currentTimeMillis()
    }
    // Keyed on scrubbing too: a gesture holds the overlay open for its whole
    // duration, then the countdown restarts when the key comes up.
    LaunchedEffect(overlayTick, state.scrubbing) {
        if (overlayTick > 0L) {
            overlayVisible = true
            if (state.scrubbing) return@LaunchedEffect
            delay(OVERLAY_HIDE_DELAY_MS)
            overlayVisible = false
        }
    }
    // Auto-summon when a video becomes current (doubles as the key-map hint).
    // Also keyed on resumedFromMs: that arrives a DAO read after the item does,
    // so re-summoning guarantees the resume notice is on screen when it lands.
    LaunchedEffect(state.current, state.resumedFromMs) {
        if (state.current is MediaItem.Video) summonOverlay()
    }
    // Show the resume notice for one overlay's worth of time, then retire it, so
    // later summons of the overlay are not still announcing how playback started.
    LaunchedEffect(state.resumedFromMs) {
        if (state.resumedFromMs != null) {
            delay(OVERLAY_HIDE_DELAY_MS)
            vm.clearResumeNotice()
        }
    }

    BackHandler { vm.exitToGrid() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val isSeekKey = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                val direction = if (event.key == Key.DirectionLeft) -1 else 1

                // Seek keys during video are the only ones that need KeyDown: the
                // gesture opens there and closes on KeyUp. Repeats are ignored —
                // the ticker below is the clock, so feel does not vary by remote.
                if (state.isVideo && isSeekKey) {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (event.nativeKeyEvent.repeatCount == 0 && gestureJob == null) {
                                summonOverlay()
                                gesturePromoted = false
                                gestureJob = scope.launch {
                                    delay(HOLD_THRESHOLD_MS)
                                    // Refuses when the duration is unknown, leaving
                                    // the gesture to resolve as a plain tap.
                                    if (!vm.beginScrub()) return@launch
                                    gesturePromoted = true
                                    val started = System.currentTimeMillis()
                                    var last = started
                                    while (true) {
                                        delay(SCRUB_TICK_MS)
                                        val now = System.currentTimeMillis()
                                        val dt = now - last
                                        last = now
                                        val held = now - started
                                        if (held >= SCRUB_SAFETY_TIMEOUT_MS) {
                                            vm.commitScrub()
                                            gestureJob = null
                                            // Stay "promoted" so a late KeyUp resolves
                                            // as an already-committed scrub (commitScrub
                                            // no-ops) instead of firing a stray ±10 s tap.
                                            break
                                        }
                                        vm.updateScrub(
                                            (vm.scrubVelocity(held) * dt).toLong() * direction
                                        )
                                    }
                                }
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            gestureJob?.cancel()
                            gestureJob = null
                            if (gesturePromoted) {
                                gesturePromoted = false
                                vm.commitScrub() // the gesture's one and only seek
                            } else {
                                vm.seekBy(direction * SEEK_STEP_MS)
                            }
                            summonOverlay()
                            true
                        }
                        else -> true
                    }
                } else if (event.type != KeyEventType.KeyUp) {
                    false
                } else if (gestureJob != null) {
                    true // a gesture is in flight; swallow everything until it closes
                } else if (state.isVideo) when (event.key) {
                    Key.DirectionUp -> { vm.previous(); true }
                    Key.DirectionDown -> { vm.next(); true }
                    Key.DirectionCenter, Key.Enter -> { vm.togglePlay(); summonOverlay(); true }
                    else -> false
                } else when (event.key) {
                    Key.DirectionLeft -> { vm.previous(); true }
                    Key.DirectionRight -> { vm.next(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.error != null -> ErrorCard(state.error ?: "")
            state.current == null -> Spinner()
            else -> MediaContent(vm)
        }

        AnimatedVisibility(
            visible = state.isVideo && overlayVisible && state.error == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
        ) {
            TransportOverlay(vm)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@UnstableApi
@Composable
private fun MediaContent(vm: PlayerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Video surface (always present; empty when the current item is a photo).
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false // transport is our own Compose overlay
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                player = vm.exoPlayer
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    // Photo overlay.
    state.photoPath?.let { path ->
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }

    // Download progress (non-streaming fallback): circular determinate + %.
    state.downloadProgress?.let { progress ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgress(progress = progress, size = 96.dp)
        }
    }

    // Buffering / photo-loading: indeterminate spinner, centered.
    if (state.buffering && state.downloadProgress == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Spinner(size = 56.dp)
        }
    }
}

/** Indeterminate rotating arc. */
@Composable
fun Spinner(size: Dp = 56.dp) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spinAngle",
    )
    Canvas(modifier = Modifier.size(size)) {
        drawArc(
            color = TeleTvColors.Accent,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Determinate circular progress with centered percentage. */
@Composable
private fun CircularProgress(progress: Float, size: Dp) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = TeleTvColors.SurfaceHigh,
                startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke,
            )
            drawArc(
                color = TeleTvColors.Accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = stroke,
            )
        }
        Text(
            "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Styled error presentation with the recovery hint baked into the message. */
@Composable
private fun ErrorCard(message: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(TeleTvDimens.RadiusCard))
            .background(TeleTvColors.SurfaceHigh)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⚠", style = MaterialTheme.typography.headlineMedium, color = TeleTvColors.Error)
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Position / duration / progress + buffered bar with a position thumb. */
@UnstableApi
@Composable
private fun TransportOverlay(vm: PlayerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var positionMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = vm.exoPlayer.currentPosition.coerceAtLeast(0L)
            bufferedMs = vm.exoPlayer.bufferedPosition.coerceAtLeast(0L)
            durationMs = vm.exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // While scrubbing the readout follows the cursor, not the 500 ms poll — a 2 Hz
    // position under a 60 fps cursor reads as broken.
    val shownPositionMs = if (state.scrubbing) state.scrubCursorMs else positionMs
    val playedFrac =
        if (durationMs > 0) (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFrac = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // Past the downloaded extent the commit will have to wait on a download; say so
    // before the user releases the key rather than after.
    val cursorPastBuffer = state.scrubbing && playedFrac > bufferedFrac

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TeleTvColors.OverlayScrim)
            .padding(horizontal = 48.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Lives inside the overlay so it shares its lifecycle exactly: summoned
        // by any handled key (pausing included) and gone on the same auto-hide.
        state.current?.label?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = TeleTvColors.OnBg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TimeChip(formatDuration((shownPositionMs / 1000).toInt()), emphasized = true)
            when {
                // Magnitude of the pending jump: the chip above already shows the
                // target, this says how far it is from where the gesture started.
                state.scrubbing -> {
                    val deltaMs = state.scrubCursorMs - state.scrubAnchorMs
                    val sign = if (deltaMs < 0) "−" else "+"
                    Text(
                        "$sign${formatDuration((abs(deltaMs) / 1000).toInt())}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (cursorPastBuffer) TeleTvColors.Error else TeleTvColors.Accent,
                    )
                }
                // Rides the overlay's normal auto-hide; nothing to dismiss, no focus taken.
                state.resumedFromMs != null -> Text(
                    stringResource(
                        R.string.resumed_from,
                        formatDuration(((state.resumedFromMs ?: 0L) / 1000).toInt()),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = TeleTvColors.Accent,
                )
            }
            TimeChip(formatDuration((durationMs / 1000).toInt()), emphasized = false)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackWidth = maxWidth
            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(TeleTvDimens.RadiusBar))
                    .background(Color(0x30FFFFFF)),
            )
            // Buffered
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedFrac)
                    .height(6.dp)
                    .clip(RoundedCornerShape(TeleTvDimens.RadiusBar))
                    .background(Color(0x66FFFFFF)),
            )
            // Played (accent gradient)
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedFrac)
                    .height(6.dp)
                    .clip(RoundedCornerShape(TeleTvDimens.RadiusBar))
                    .background(TeleTvColors.AccentGradient),
            )
            // Thumb at the played position, or the scrub cursor while gesturing —
            // recoloured once it leaves the downloaded range.
            Box(
                modifier = Modifier
                    .offset(x = trackWidth * playedFrac - if (state.scrubbing) 9.dp else 7.dp)
                    .size(if (state.scrubbing) 18.dp else 14.dp)
                    .clip(CircleShape)
                    .background(if (cursorPastBuffer) TeleTvColors.Error else TeleTvColors.OnBg),
            )
        }
    }
}

@Composable
private fun TimeChip(text: String, emphasized: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
            .background(TeleTvColors.ChipBg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (emphasized) TeleTvColors.OnBg else TeleTvColors.Muted,
        )
    }
}
