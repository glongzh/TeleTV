package com.teletv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ui.theme.TeleTvColors

/**
 * Branded splash shown while TDLib cold-starts: it loads its native libraries,
 * opens its persistent databases, and resolves the authorization state before
 * the app knows whether to show the grid or the QR login. Rather than a bare
 * "Starting…" line, this presents the brand with a subtle spinner. The network
 * settings entry stays reachable so a start stalled behind a bad proxy is
 * recoverable without waiting it out.
 */
@UnstableApi
@Composable
fun StartingScreen(onOpenProxy: (() -> Unit)? = null) {
    val proxyFocus = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.BrandBackdrop),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
                )
                Spacer(Modifier.width(18.dp))
                Text("TeleTV", style = MaterialTheme.typography.displayMedium)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.qr_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = TeleTvColors.Muted,
            )

            Spacer(Modifier.height(44.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spinner(size = 22.dp)
                Text(
                    stringResource(R.string.starting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TeleTvColors.Muted,
                )
            }
        }

        // Network settings entry, top-right corner — matches the QR screen and
        // keeps a proxy-stalled start recoverable.
        if (onOpenProxy != null) {
            Button(
                onClick = onOpenProxy,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .focusRequester(proxyFocus),
            ) { Text(stringResource(R.string.network_settings)) }

            LaunchedEffect(Unit) { proxyFocus.requestFocus() }
        }
    }
}
