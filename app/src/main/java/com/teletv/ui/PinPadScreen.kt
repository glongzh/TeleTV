package com.teletv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.ui.theme.TeleTvColors

private const val PIN_LENGTH = 4

/**
 * Reusable 4-digit PIN pad: title + masked dots + D-pad-focusable 0-9 pad.
 * Auto-submits (and clears) when the 4th digit is entered. Remote number keys
 * and backspace also work. All PIN flows (unlock / set / change / remove)
 * compose this with different titles and submit handlers.
 */
@Composable
fun PinPadScreen(
    title: String,
    message: String? = null,
    isError: Boolean = false,
    onSubmit: (String) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    val centerFocus = remember { FocusRequester() }

    LaunchedEffect(entry) {
        if (entry.length == PIN_LENGTH) {
            val pin = entry
            entry = ""
            onSubmit(pin)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.BrandBackdrop)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                val digit = digitOf(event.key)
                when {
                    digit != null -> { entry = (entry + digit).take(PIN_LENGTH); true }
                    event.key == Key.Backspace || event.key == Key.Delete -> {
                        entry = entry.dropLast(1); true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)

        Text(
            text = message ?: " ",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) TeleTvColors.Error else TeleTvColors.Muted,
            modifier = Modifier.padding(top = 12.dp),
        )

        // Masked entry dots: accent when filled.
        Row(
            modifier = Modifier.padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(PIN_LENGTH) { i ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < entry.length) TeleTvColors.Accent else TeleTvColors.SurfaceHigh
                        ),
                )
            }
        }

        // 3x4 pad: 1-9, then [⌫ 0].
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { keyLabel ->
                    Button(
                        onClick = {
                            entry = when (keyLabel) {
                                "⌫" -> entry.dropLast(1)
                                else -> (entry + keyLabel).take(PIN_LENGTH)
                            }
                        },
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .then(if (keyLabel == "5") Modifier.focusRequester(centerFocus) else Modifier),
                    ) {
                        Text(text = keyLabel, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { centerFocus.requestFocus() }
}

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
