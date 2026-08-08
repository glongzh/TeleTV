package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.analytics.Analytics
import com.teletv.ui.theme.TeleTvColors

/**
 * Usage-data opt-out. One toggle, plus an explainer that says in plain words what
 * does and does not leave the TV — a switch labelled only "analytics" asks the
 * user to make a privacy decision with no information.
 *
 * The choice takes effect immediately (no restart) and outlives sign-out; see
 * [Analytics.isOptedOut] for why the flag is stored here rather than in the SDK.
 */
@Composable
fun AnalyticsScreen(onDone: () -> Unit) {
    var optedOut by remember { mutableStateOf(Analytics.isOptedOut) }
    val firstFocus = remember { FocusRequester() }

    BackHandler { onDone() }

    Column(
        modifier = Modifier.fillMaxSize().background(TeleTvColors.BrandBackdrop).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.analytics_title), style = MaterialTheme.typography.headlineMedium)

        Text(
            text = stringResource(R.string.analytics_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = TeleTvColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.7f).padding(top = 12.dp, bottom = 24.dp),
        )

        Text(
            text = stringResource(
                if (optedOut) R.string.analytics_state_off else R.string.analytics_state_on
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Button(
            onClick = {
                optedOut = !optedOut
                Analytics.isOptedOut = optedOut
            },
            modifier = Modifier.width(320.dp).focusRequester(firstFocus),
        ) {
            Text(
                stringResource(
                    if (optedOut) R.string.analytics_turn_on else R.string.analytics_turn_off
                )
            )
        }
    }

    LaunchedEffect(Unit) { firstFocus.requestFocus() }
}
