package com.dexrdp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4EA1FF),
    onPrimary = Color(0xFF00325B),
    secondary = Color(0xFF9FCAFF),
    background = Color(0xFF14161A),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF1C1F25),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF2A2E36),
    onSurfaceVariant = Color(0xFFBFC6D4),
    outline = Color(0xFF3A3F49)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    secondary = Color(0xFF006398),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun DexRdpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}