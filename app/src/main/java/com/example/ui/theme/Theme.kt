package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val TerminalDarkColorScheme = darkColorScheme(
    primary = CyanElectric,
    onPrimary = OnCyan,
    primaryContainer = CyanContainer,
    onPrimaryContainer = CyanElectric,
    secondary = MintEmerald,
    onSecondary = OnMint,
    secondaryContainer = MintContainer,
    onSecondaryContainer = MintEmerald,
    tertiary = AmberAlert,
    onTertiary = ObsidianBg,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberAlert,
    error = CrimsonError,
    onError = ObsidianBg,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonError,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle,
)

private val TerminalLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,
    secondary = LightSecondary,
    onSecondary = LightSurface,
    background = LightBg,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextMuted,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AlpacaShellTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) TerminalDarkColorScheme else TerminalLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
