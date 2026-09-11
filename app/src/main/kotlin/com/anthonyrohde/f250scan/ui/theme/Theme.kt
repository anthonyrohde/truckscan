package com.anthonyrohde.f250scan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colour scheme.
 *
 * Dark by default and high contrast, because this app gets used in a truck cab
 * where the alternatives are glare through a windscreen or ruined night vision.
 * Fault severity is carried by colour and by text, never colour alone.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FB2FF),
    onPrimary = Color(0xFF00243F),
    secondary = Color(0xFF9FCBFF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF181D22),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF232A31),
    onSurfaceVariant = Color(0xFFBFC8D1),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00558F),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
)

@Composable
fun F250ScanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
