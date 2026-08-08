package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.storage.CacheCap
import com.teletv.storage.StorageUsage
import com.teletv.ui.theme.TeleTvColors
import kotlinx.coroutines.launch

/** Human-readable byte size, e.g. "1.8 GB" / "340 MB". */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

private fun capLabel(cap: CacheCap): Int = when (cap) {
    CacheCap.ONE_GB -> R.string.storage_cap_1gb
    CacheCap.TWO_GB -> R.string.storage_cap_2gb
    CacheCap.FIVE_GB -> R.string.storage_cap_5gb
    CacheCap.UNLIMITED -> R.string.storage_cap_unlimited
}

/**
 * Storage settings: approximate usage display, a persisted cache-cap selector,
 * and a manual "Clear cache now" action. Clearing targets TDLib's files
 * directory only — it never signs the user out or drops the tag index.
 */
@Composable
fun StorageScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var cap by remember { mutableStateOf(ServiceLocator.cachePrefs.currentCap()) }
    var clearing by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    fun refreshUsage() {
        scope.launch { usage = runCatching { ServiceLocator.storage.usage() }.getOrNull() }
    }

    LaunchedEffect(Unit) { refreshUsage() }
    BackHandler { onDone() }

    Column(
        modifier = Modifier.fillMaxSize().background(TeleTvColors.BrandBackdrop).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.headlineMedium)

        val u = usage
        Text(
            text = if (u != null) {
                stringResource(R.string.storage_usage, formatBytes(u.filesSize), formatBytes(u.databaseSize))
            } else {
                stringResource(R.string.storage_usage_loading)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = TeleTvColors.Muted,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        Text(
            stringResource(R.string.storage_cap_label),
            style = MaterialTheme.typography.bodyMedium,
            color = TeleTvColors.Muted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CacheCap.entries.forEachIndexed { index, choice ->
                val isSelected = choice == cap
                val label = stringResource(capLabel(choice))
                Button(
                    onClick = {
                        cap = choice
                        ServiceLocator.cachePrefs.setCap(choice)
                    },
                    modifier = Modifier
                        .width(120.dp)
                        .let { if (index == 0) it.focusRequester(firstFocus) else it },
                ) {
                    Text(if (isSelected) "✓ $label" else label)
                }
            }
        }
        LaunchedEffect(Unit) { firstFocus.requestFocus() }

        Button(
            enabled = !clearing,
            onClick = {
                clearing = true
                scope.launch {
                    runCatching { ServiceLocator.storage.clearAll() }
                    refreshUsage()
                    clearing = false
                }
            },
            modifier = Modifier.width(320.dp).padding(top = 28.dp),
        ) {
            Text(
                if (clearing) stringResource(R.string.storage_clearing)
                else stringResource(R.string.storage_clear_now)
            )
        }
    }
}
