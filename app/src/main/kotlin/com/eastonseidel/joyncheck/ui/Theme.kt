package com.eastonseidel.joyncheck.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as JoynCon, so the two read as one family.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF00E5A0),
    secondary = Color(0xFF00C2FF),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00875A),
    secondary = Color(0xFF0077A8),
)

@Composable
fun JoynCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
