package com.teletv.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Brand palette — the single source of truth for every color in the app.
 * No raw hex values belong in screen composables.
 */
object TeleTvColors {
    val Bg = Color(0xFF0B0F17)          // space blue-black app background
    val Surface = Color(0xFF131B29)     // cards, skeletons
    val SurfaceHigh = Color(0xFF1C2738) // elevated cards (errors, dialogs)
    val Accent = Color(0xFF2AABEE)      // Telegram-heritage blue
    val AccentDeep = Color(0xFF1E88D4)  // gradient partner
    val OnBg = Color(0xFFECF1F8)        // primary text
    val Muted = Color(0xFF8B98AB)       // secondary text
    val Error = Color(0xFFFF6B6B)
    val ChipBg = Color(0xCC0B0F17)      // translucent chip background over media

    /** Horizontal accent gradient (progress fills, brand touches). */
    val AccentGradient = Brush.horizontalGradient(listOf(Accent, AccentDeep))

    /** Bottom scrim for labels over thumbnails. */
    val LabelScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B0F17)))

    /** Full-screen backdrop glow used on brand screens (QR, PIN). */
    val BrandBackdrop = Brush.radialGradient(
        colors = listOf(Color(0xFF12233A), Bg),
        radius = 1600f,
    )

    /** Transport overlay scrim. */
    val OverlayScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xF20B0F17)))

    /**
     * Flat dim for content behind a modal, used where the platform has no blur
     * to give it (API < 31 — minSdk here is 21, so real TV boxes hit this).
     */
    val ModalDim = Color(0xB30B0F17)
}
