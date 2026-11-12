package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val MooMooLightColorScheme = lightColorScheme(
    primary = MeadowGreen,
    onPrimary = Color.White,
    primaryContainer = SoftSage,
    onPrimaryContainer = DeepSageInk,
    secondary = TerracottaAccent,
    onSecondary = Color.White,
    secondaryContainer = TerracottaContainer,
    onSecondaryContainer = TerracottaInk,
    tertiary = ButtercupWarm,
    onTertiary = Color.White,
    tertiaryContainer = ButtercupContainer,
    onTertiaryContainer = EspressoInk,
    background = CreamBackground,
    onBackground = EspressoInk,
    surface = CreamBackground,
    onSurface = EspressoInk,
    surfaceVariant = OatSurface,
    onSurfaceVariant = EspressoMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = OatSurface,
    surfaceContainer = OatSurfaceHigh,
    surfaceContainerHigh = OatSurfaceHighest,
    outline = OutlineWarm,
    outlineVariant = OatSurfaceHighest,
    error = ErrorCrimson,
    errorContainer = ErrorContainerLight
)

private val MooMooDarkColorScheme = darkColorScheme(
    primary = MintSage,
    onPrimary = MintSageInk,
    primaryContainer = MintSageContainer,
    onPrimaryContainer = SoftSage,
    secondary = AmberWarm,
    onSecondary = AmberWarmInk,
    secondaryContainer = AmberWarmContainer,
    onSecondaryContainer = AmberWarmInk,
    tertiary = MintSage,
    onTertiary = MintSageInk,
    background = NightPastureBg,
    onBackground = CreamInk,
    surface = NightPastureBg,
    onSurface = CreamInk,
    surfaceVariant = NightPastureSurface,
    onSurfaceVariant = CreamMuted,
    surfaceContainerLowest = NightPastureBg,
    surfaceContainerLow = NightPastureSurface,
    surfaceContainer = NightPastureSurfaceHigh,
    surfaceContainerHigh = NightPastureSurfaceHighest,
    outline = OutlineNight,
    outlineVariant = NightPastureSurfaceHighest,
    error = ErrorDark,
    errorContainer = ErrorContainerDark
)

val MooMooShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun MooMooTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) MooMooDarkColorScheme else MooMooLightColorScheme,
        typography = MooMooTypography,
        shapes = MooMooShapes,
        content = content
    )
}
