package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.media.MediaLibraryViewModel
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens

/**
 * Centered search dialog, opened from the grid's top bar (replaces the earlier
 * in-drawer search field). Overlays the grid behind a scrim — results stream
 * into the grid live while typing, same as before, just via a different input
 * surface. BACK here closes only this dialog; an applied search filter (if any)
 * stays active on the grid and is cleared by a subsequent BACK there (see
 * GridScreen's filter-aware BackHandler), not by dismissing this dialog.
 */
@Composable
fun SearchDialog(
    library: MediaLibraryViewModel,
    initialQuery: String,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(query) { library.applySearch(query) }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

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
                .width(520.dp)
                .clip(RoundedCornerShape(TeleTvDimens.RadiusCard))
                .background(TeleTvColors.Surface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                stringResource(R.string.search_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
                    .background(TeleTvColors.Bg)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = TeleTvColors.Muted)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TeleTvColors.OnBg),
                    cursorBrush = SolidColor(TeleTvColors.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus),
                )
            }
        }
    }
}
