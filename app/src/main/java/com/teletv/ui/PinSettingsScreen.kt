package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.ui.theme.TeleTvColors
import kotlinx.coroutines.delay

/** Steps of the PIN settings flow. */
private sealed interface PinStep {
    data object Menu : PinStep                                  // pin set: change / remove
    data class VerifyCurrent(val forRemoval: Boolean) : PinStep // gate for change/remove
    data class EnterNew(val error: Boolean = false) : PinStep   // set or change
    data class ConfirmNew(val first: String) : PinStep
    data class Done(val messageRes: Int) : PinStep
}

/**
 * PIN management: set (enter twice), change (current → new → confirm),
 * remove (current). Composes the reusable PinPadScreen for every entry step.
 */
@Composable
fun PinSettingsScreen(onDone: () -> Unit) {
    val pinRepo = ServiceLocator.pin
    var step by remember {
        mutableStateOf<PinStep>(if (pinRepo.isPinSet()) PinStep.Menu else PinStep.EnterNew())
    }
    var verifyError by remember { mutableStateOf(false) }
    val menuFocus = remember { FocusRequester() }

    BackHandler { onDone() }

    when (val s = step) {
        is PinStep.Menu -> {
            Column(
                modifier = Modifier.fillMaxSize().background(TeleTvColors.BrandBackdrop).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.pin_lock), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.pin_lock_active_desc),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TeleTvColors.Muted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Button(
                    onClick = { verifyError = false; step = PinStep.VerifyCurrent(forRemoval = false) },
                    modifier = Modifier.padding(vertical = 6.dp).focusRequester(menuFocus),
                ) { Text(stringResource(R.string.change_pin)) }
                Button(
                    onClick = { verifyError = false; step = PinStep.VerifyCurrent(forRemoval = true) },
                    modifier = Modifier.padding(vertical = 6.dp),
                ) { Text(stringResource(R.string.remove_pin)) }
            }
            LaunchedEffect(Unit) { menuFocus.requestFocus() }
        }

        is PinStep.VerifyCurrent -> PinPadScreen(
            title = stringResource(R.string.enter_current_pin),
            message = if (verifyError) stringResource(R.string.wrong_pin_try_again) else null,
            isError = verifyError,
            onSubmit = { pin ->
                if (pinRepo.verify(pin)) {
                    verifyError = false
                    step = if (s.forRemoval) {
                        pinRepo.clearPin()
                        PinStep.Done(R.string.pin_removed)
                    } else PinStep.EnterNew()
                } else verifyError = true
            },
        )

        is PinStep.EnterNew -> PinPadScreen(
            title = stringResource(R.string.enter_new_pin),
            message = if (s.error) stringResource(R.string.pins_didnt_match) else stringResource(R.string.enter_twice_hint),
            isError = s.error,
            onSubmit = { pin -> step = PinStep.ConfirmNew(pin) },
        )

        is PinStep.ConfirmNew -> PinPadScreen(
            title = stringResource(R.string.confirm_new_pin),
            onSubmit = { pin ->
                if (pin == s.first) {
                    pinRepo.setPin(pin)
                    step = PinStep.Done(R.string.pin_set)
                } else step = PinStep.EnterNew(error = true)
            },
        )

        is PinStep.Done -> {
            CenterMessage(stringResource(s.messageRes))
            LaunchedEffect(Unit) {
                delay(1200)
                onDone()
            }
        }
    }
}
