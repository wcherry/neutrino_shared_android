package com.neutrino.shared.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * How one app looks where the shared screens draw it: `NeutrinoBrand` in the iOS shared package.
 * Each app's icon is drawn in its gradient; the sign-in screen and the lock screen show the icon,
 * the name and the tagline.
 */
data class NeutrinoBrand(
    val name: String,
    val tagline: String,
    /** The gradient's first colour, used as the theme's primary. */
    val primary: Color,
    /** The gradient's second colour, the theme's secondary. */
    val secondary: Color,
    /** The app icon's artwork, shown on the sign-in and lock screens. */
    @param:DrawableRes val icon: Int,
    /**
     * The reasons to trust the app, under the sign-in title. Each claim must be true of this app:
     * Calendar's events are stored readable (provider sync needs them), so it doesn't claim E2EE.
     */
    val trustRows: List<TrustRow> = listOf(
        TrustRow(Icons.Filled.Shield, "End-to-end encrypted", Color(0xFF2EA44F)),
        TrustRow(Icons.Filled.Lock, "Zero-knowledge cloud storage", Color(0xFF007AFF)),
        TrustRow(Icons.Filled.Key, "Only you hold your keys", Color(0xFF5856D6)),
    ),
    /** What the lock screen says the app protects. */
    val lockedSubtitle: String = "Your data is encrypted on this phone.",
    /** Under "Create your account". */
    val registerSubtitle: String = "Your files are encrypted on this phone before they are uploaded. Only you hold the key.",
    /** The first line of encryption setup: what the key protects in this app. */
    val encryptionBlurb: String = "Your files are encrypted with a key only you hold. It stays on this phone and is never sent to the server.",
    /** A tinted primary for dark mode, readable on a dark surface. */
    val primaryDark: Color = primary,
    val secondaryDark: Color = secondary,
) {
    val gradient: Brush get() = Brush.linearGradient(listOf(primary, secondary))

    companion object {
        val success = Color(0xFF2EA44F)
        val warning = Color(0xFFE08A00)
        val blue = Color(0xFF007AFF)
        val indigo = Color(0xFF5856D6)
    }
}

data class TrustRow(val icon: ImageVector, val text: String, val color: Color)

val LocalNeutrinoBrand = staticCompositionLocalOf<NeutrinoBrand> { error("No NeutrinoBrand provided") }

/** The neutral surfaces every Neutrino app shares, around the brand's own primary and secondary. */
fun neutrinoColorScheme(brand: NeutrinoBrand, dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = brand.primaryDark,
        onPrimary = Color(0xFF111118),
        primaryContainer = brand.primary.copy(alpha = 0.55f).compositeOver(Color(0xFF121218)),
        onPrimaryContainer = Color(0xFFEDEDF7),
        secondary = brand.secondaryDark,
        onSecondary = Color(0xFF111118),
        secondaryContainer = brand.secondary.copy(alpha = 0.45f).compositeOver(Color(0xFF121218)),
        onSecondaryContainer = Color(0xFFEDEDF7),
        background = Color(0xFF121218),
        surface = Color(0xFF121218),
        surfaceContainerLowest = Color(0xFF0C0C11),
        surfaceContainerLow = Color(0xFF1A1A21),
        surfaceContainer = Color(0xFF1E1E26),
        surfaceContainerHigh = Color(0xFF282830),
    )
} else {
    lightColorScheme(
        primary = brand.primary,
        onPrimary = Color.White,
        primaryContainer = brand.primary.copy(alpha = 0.16f).compositeOver(Color.White),
        onPrimaryContainer = brand.primary.copy(alpha = 1f).darken(0.55f),
        secondary = brand.secondary,
        onSecondary = Color.White,
        secondaryContainer = brand.secondary.copy(alpha = 0.16f).compositeOver(Color.White),
        onSecondaryContainer = brand.secondary.darken(0.55f),
        background = Color(0xFFF7F7FB),
        surface = Color(0xFFF7F7FB),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF1F1F7),
        surfaceContainer = Color(0xFFEDEDF4),
        surfaceContainerHigh = Color(0xFFE7E7EF),
    )
}

/** The brand's theme, with the brand itself provided for the shared screens. */
@Composable
fun NeutrinoTheme(brand: NeutrinoBrand, colorScheme: ColorScheme? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNeutrinoBrand provides brand) {
        MaterialTheme(colorScheme = colorScheme ?: neutrinoColorScheme(brand, isSystemInDarkTheme()), content = content)
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

private fun Color.darken(by: Float): Color = Color(red * (1 - by), green * (1 - by), blue * (1 - by), alpha)
