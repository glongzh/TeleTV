package com.teletv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.analytics.Analytics
import com.teletv.analytics.Screens
import com.teletv.auth.AuthState
import com.teletv.media.MediaLibraryViewModel
import com.teletv.media.Screen
import com.teletv.security.LockViewModel

@UnstableApi
@Composable
fun App() {
    val authState by ServiceLocator.auth.state.collectAsStateWithLifecycle()

    // Pre-auth proxy overlay: a blocked network stalls before login, so proxy
    // config must be reachable from the login screens — not just post-login
    // settings. The Ready branch uses the settings menu instead.
    var showProxy by remember { mutableStateOf(false) }
    if (showProxy && authState !is AuthState.Ready) {
        ProxyScreen(onDone = { showProxy = false })
        return
    }

    when (val s = authState) {
        is AuthState.Ready -> LockGate()
        is AuthState.ShowQr -> {
            val connected by ServiceLocator.auth.connected.collectAsStateWithLifecycle()
            QrLoginScreen(
                link = s.link,
                connected = connected,
                onOpenProxy = { showProxy = true },
            )
        }
        is AuthState.WaitPassword -> PasswordScreen(
            state = s,
            onSubmit = ServiceLocator.auth::checkPassword,
            onStartOver = ServiceLocator.auth::restartLogin,
            onOpenProxy = { showProxy = true },
        )
        is AuthState.Error -> CenterMessage(s.message, onOpenProxy = { showProxy = true })
        AuthState.SigningOut -> CenterMessage(stringResource(R.string.signing_out))
        AuthState.LoggedOut -> CenterMessage(stringResource(R.string.signed_out))
        AuthState.Initializing -> StartingScreen(onOpenProxy = { showProxy = true })
    }
}

/**
 * PIN gate ahead of all authenticated content: while locked, ONLY the PIN pad
 * exists in the composition — no grid, thumbnails, or player.
 */
@UnstableApi
@Composable
private fun LockGate() {
    val lockVm: LockViewModel = viewModel(factory = LockViewModel.Factory(ServiceLocator.pin))
    val locked by lockVm.locked.collectAsStateWithLifecycle()

    Crossfade(targetState = locked, animationSpec = tween(250), label = "lockGate") { isLocked ->
        if (isLocked) {
            var wrong by remember { mutableStateOf(false) }
            PinPadScreen(
                title = stringResource(R.string.enter_pin),
                message = if (wrong) stringResource(R.string.wrong_pin_try_again) else null,
                isError = wrong,
                onSubmit = { pin -> wrong = !lockVm.tryUnlock(pin) },
            )
        } else {
            AuthedContent()
        }
    }
}

@UnstableApi
@Composable
private fun AuthedContent() {
    // Activity-scoped: the grid and the player share one media library state.
    val library: MediaLibraryViewModel = viewModel(
        factory = MediaLibraryViewModel.Factory(
            ServiceLocator.media, ServiceLocator.tagDao, ServiceLocator.indexer,
            ServiceLocator.sources, ServiceLocator.progressDao,
        )
    )
    val state by library.state.collectAsStateWithLifecycle()

    // One `$screen` per navigation. Keyed on the screen so it fires on transitions
    // only, not on the recompositions that a growing grid triggers constantly.
    LaunchedEffect(state.screen) {
        Analytics.screen(
            when (state.screen) {
                Screen.Grid -> Screens.GRID
                Screen.Player -> Screens.PLAYER
                Screen.Settings -> Screens.SETTINGS
                Screen.SourcePicker -> Screens.SOURCE_PICKER
            }
        )
    }

    Crossfade(targetState = state.screen, animationSpec = tween(250), label = "screens") { screen ->
        when (screen) {
            Screen.Grid -> GridScreen(library)
            Screen.Player -> PlayerScreen(library)
            Screen.Settings -> SettingsScreen(onDone = { library.backToGrid() })
            Screen.SourcePicker -> SourcePickerScreen(library, onDone = { library.backToGrid() })
        }
    }
}

@Composable
fun CenterMessage(message: String, onOpenProxy: (() -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, style = MaterialTheme.typography.headlineSmall)
            // When the network is blocked the app can sit here indefinitely; give
            // the user a way to reach proxy config from the pre-login screens.
            if (onOpenProxy != null) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenProxy) { Text(stringResource(R.string.settings_proxy)) }
            }
        }
    }
}
