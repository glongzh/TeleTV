package com.teletv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Composable
fun TeleTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TeleTvColors.Accent,
            secondary = TeleTvColors.AccentDeep,
            background = TeleTvColors.Bg,
            surface = TeleTvColors.Surface,
            surfaceVariant = TeleTvColors.SurfaceHigh,
            onPrimary = TeleTvColors.Bg,
            onBackground = TeleTvColors.OnBg,
            onSurface = TeleTvColors.OnBg,
            onSurfaceVariant = TeleTvColors.Muted,
            error = TeleTvColors.Error,
        ),
        typography = TeleTvTypography,
    ) {
        // Screens draw on plain backgrounds without a Surface, so provide the
        // default content color explicitly (black-on-black guard, learned v1).
        CompositionLocalProvider(
            LocalContentColor provides TeleTvColors.OnBg,
            content = content,
        )
    }
}
