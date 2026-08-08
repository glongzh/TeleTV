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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens

@Composable
fun QrLoginScreen(link: String, connected: Boolean = true, onOpenProxy: (() -> Unit)? = null) {
    val qr = remember(link) { generateQrBitmap(link, 480) }
    val proxyFocus = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.BrandBackdrop),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: brand + numbered steps.
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text("TeleTV", style = MaterialTheme.typography.displayMedium)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.qr_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TeleTvColors.Muted,
                )
                Spacer(Modifier.height(36.dp))
                InstructionStep(1, stringResource(R.string.qr_step_1))
                InstructionStep(2, stringResource(R.string.qr_step_2))
                InstructionStep(3, stringResource(R.string.qr_step_3))
            }

            Spacer(Modifier.width(56.dp))

            // Right: the QR (when connected), or a can't-connect notice — a QR shown
            // while offline is stale and won't confirm, so never present one as valid.
            if (connected) {
                // Elevated white card (scanners need a light background).
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(TeleTvDimens.RadiusCard))
                        .background(Color.White)
                        .padding(20.dp),
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_code_desc),
                        modifier = Modifier.size(300.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(340.dp)
                        .clip(RoundedCornerShape(TeleTvDimens.RadiusCard))
                        .background(TeleTvColors.Surface)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.qr_disconnected_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = TeleTvColors.Error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.qr_disconnected_desc),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TeleTvColors.Muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Network settings entry, top-right corner.
        if (onOpenProxy != null) {
            Button(
                onClick = onOpenProxy,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .focusRequester(proxyFocus),
            ) { Text(stringResource(R.string.network_settings)) }
        }
    }

    // The button is the only focusable element; claim focus so the D-pad can
    // reach it (TV screens without an explicit request lose focus).
    if (onOpenProxy != null) {
        LaunchedEffect(Unit) { proxyFocus.requestFocus() }
    }
}

@Composable
private fun InstructionStep(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(TeleTvColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.titleMedium,
                color = TeleTvColors.Bg,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
