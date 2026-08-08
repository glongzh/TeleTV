package com.teletv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.auth.AuthState
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens

/**
 * Cloud-password (2FA) entry after a confirmed QR scan. The system TV IME does
 * the typing; explicit Submit / Start-over buttons back up remotes whose IME
 * mishandles the Done action.
 */
@Composable
fun PasswordScreen(
    state: AuthState.WaitPassword,
    onSubmit: (String) -> Unit,
    onStartOver: () -> Unit,
    onOpenProxy: (() -> Unit)? = null,
) {
    var password by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }

    fun submit() {
        if (!state.checking && password.isNotEmpty()) onSubmit(password)
    }

    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.BrandBackdrop)
            .padding(horizontal = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.password_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.password_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = TeleTvColors.Muted,
        )
        state.hint?.let { hint ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.password_hint_label, hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TeleTvColors.Muted,
            )
        }

        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(TeleTvDimens.RadiusCard))
                .background(TeleTvColors.Surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            BasicTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = TeleTvColors.OnBg),
                cursorBrush = SolidColor(TeleTvColors.Accent),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Fixed-height status line so the layout doesn't jump between states.
        Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            when {
                state.checking -> Text(
                    text = stringResource(R.string.password_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TeleTvColors.Muted,
                )
                state.error != null -> Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TeleTvColors.Error,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { submit() }, enabled = !state.checking) {
                Text(stringResource(R.string.password_submit))
            }
            Button(onClick = onStartOver, enabled = !state.checking) {
                Text(stringResource(R.string.password_start_over))
            }
            if (onOpenProxy != null) {
                Button(onClick = onOpenProxy, enabled = !state.checking) {
                    Text(stringResource(R.string.settings_proxy))
                }
            }
        }
    }
}
