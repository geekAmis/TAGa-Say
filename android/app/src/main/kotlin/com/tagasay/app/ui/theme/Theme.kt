package com.tagasay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = TagaPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2352),
    onPrimaryContainer = Color(0xFFD9D2FF),
    secondary = TagaBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1C3057),
    onSecondaryContainer = Color(0xFFD4E4FF),
    tertiary = TagaGreen,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF232738),
    error = TagaRed,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = TagaPurpleDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF231A5C),
    secondary = Color(0xFF2F6FE4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8FF),
    onSecondaryContainer = Color(0xFF102A56),
    tertiary = Color(0xFF1F8F63),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFE6E9F2),
    error = TagaRed,
)

@Composable
fun TAGaSayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TagaTypography,
        content = content,
    )
}
