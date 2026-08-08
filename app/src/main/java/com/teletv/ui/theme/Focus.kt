package com.teletv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.CardScale
import androidx.tv.material3.Glow

/**
 * The app-wide focus signature: scale + accent ring + soft glow.
 * Every focusable Card uses these three together so focus reads identically
 * across the grid, menus, and any future surfaces.
 */
object TeleTvFocus {

    @Composable
    fun cardScale(): CardScale = CardDefaults.scale(focusedScale = TeleTvDimens.FocusScale)

    @Composable
    fun cardBorder(): CardBorder = CardDefaults.border(
        focusedBorder = Border(
            border = BorderStroke(TeleTvDimens.FocusBorderWidth, TeleTvColors.Accent),
            shape = RoundedCornerShape(TeleTvDimens.RadiusCell),
        ),
    )

    @Composable
    fun cardGlow(): CardGlow = CardDefaults.glow(
        focusedGlow = Glow(elevationColor = TeleTvColors.Accent.copy(alpha = 0.55f), elevation = 18.dp),
        glow = Glow(elevationColor = Color.Transparent, elevation = 0.dp),
    )
}
