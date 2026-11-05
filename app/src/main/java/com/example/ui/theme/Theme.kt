package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    primaryContainer = StudioSurfaceVariant,
    onPrimaryContainer = IndigoLight,
    secondary = CyanAccent,
    onSecondary = Color.Black,
    tertiary = AmberAccent,
    onTertiary = Color.Black,
    background = StudioBackground,
    onBackground = DarkOnBackground,
    surface = StudioSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceMuted,
    outline = StudioBorder,
    outlineVariant = StudioBorder
)

@Composable
fun PhotoEasyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
