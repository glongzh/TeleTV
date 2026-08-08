package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.analytics.Analytics
import com.teletv.ui.theme.TeleTvColors

/** Steps of the settings flow: the menu, the PIN sub-flow, storage, proxy, usage data, sign-out confirm. */
private sealed interface SettingsStep {
    data object Menu : SettingsStep
    data object Pin : SettingsStep
    data object Storage : SettingsStep
    data object Proxy : SettingsStep
    data object UsageData : SettingsStep
    data class SignOut(val error: String? = null) : SettingsStep
}

/**
 * Settings hub: PIN lock, Storage, Proxy, Usage data, Sign out. The existing PIN
 * flow is reused unchanged behind the first entry; Sign out leads to a
 * confirmation whose initial focus is on Cancel, so signing out takes a
 * deliberate second press. The Usage data entry is hidden in builds with no
 * PostHog key compiled in — there would be nothing behind it.
 */
@UnstableApi
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    var step by remember { mutableStateOf<SettingsStep>(SettingsStep.Menu) }

    when (val s = step) {
        is SettingsStep.Menu -> {
            BackHandler { onDone() }
            val firstFocus = remember { FocusRequester() }
            Column(
                modifier = Modifier.fillMaxSize().background(TeleTvColors.BrandBackdrop).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TeleTvColors.Muted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Button(
                    onClick = { step = SettingsStep.Pin },
                    modifier = Modifier.width(320.dp).padding(vertical = 6.dp).focusRequester(firstFocus),
                ) { Text(stringResource(R.string.settings_pin_lock)) }
                Button(
                    onClick = { step = SettingsStep.Storage },
                    modifier = Modifier.width(320.dp).padding(vertical = 6.dp),
                ) { Text(stringResource(R.string.settings_storage)) }
                Button(
                    onClick = { step = SettingsStep.Proxy },
                    modifier = Modifier.width(320.dp).padding(vertical = 6.dp),
                ) { Text(stringResource(R.string.settings_proxy)) }
                if (Analytics.isConfigured) {
                    Button(
                        onClick = { step = SettingsStep.UsageData },
                        modifier = Modifier.width(320.dp).padding(vertical = 6.dp),
                    ) { Text(stringResource(R.string.settings_analytics)) }
                }
                Button(
                    onClick = { step = SettingsStep.SignOut() },
                    modifier = Modifier.width(320.dp).padding(vertical = 6.dp),
                ) { Text(stringResource(R.string.settings_sign_out)) }
            }
            LaunchedEffect(Unit) { firstFocus.requestFocus() }
        }

        is SettingsStep.Pin -> PinSettingsScreen(onDone = { step = SettingsStep.Menu })

        is SettingsStep.Storage -> StorageScreen(onDone = { step = SettingsStep.Menu })

        is SettingsStep.Proxy -> ProxyScreen(onDone = { step = SettingsStep.Menu })

        is SettingsStep.UsageData -> AnalyticsScreen(onDone = { step = SettingsStep.Menu })

        is SettingsStep.SignOut -> {
            BackHandler { step = SettingsStep.Menu }
            val cancelFocus = remember { FocusRequester() }
            Column(
                modifier = Modifier.fillMaxSize().background(TeleTvColors.BrandBackdrop).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.sign_out_confirm_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.sign_out_confirm_warning),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TeleTvColors.Muted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                s.error?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TeleTvColors.Error,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                Button(
                    onClick = {
                        step = SettingsStep.SignOut(error = null)
                        ServiceLocator.auth.logOut { e -> step = SettingsStep.SignOut(error = e) }
                    },
                    modifier = Modifier.width(320.dp).padding(vertical = 6.dp),
                ) { Text(stringResource(R.string.sign_out_confirm)) }
                Button(
                    onClick = { step = SettingsStep.Menu },
                    modifier = Modifier.width(320.dp).padding(vertical = 6.dp).focusRequester(cancelFocus),
                ) { Text(stringResource(R.string.cancel)) }
            }
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        }
    }
}
