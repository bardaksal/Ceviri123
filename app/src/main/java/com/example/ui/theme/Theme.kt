package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SubCastDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianBackground,
    primaryContainer = ElectricCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = SignalAmber,
    onSecondary = ObsidianBackground,
    secondaryContainer = SignalAmberDim,
    onSecondaryContainer = TextPrimary,
    tertiary = SignalEmerald,
    onTertiary = ObsidianBackground,
    tertiaryContainer = SignalEmeraldDim,
    onTertiaryContainer = TextPrimary,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = SignalCoral,
    onError = ObsidianBackground,
    errorContainer = SignalCoralDim,
    onErrorContainer = TextPrimary
)

private val SubCastShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun SubCastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SubCastDarkColorScheme,
        typography = AppTypography,
        shapes = SubCastShapes,
        content = content
    )
}
