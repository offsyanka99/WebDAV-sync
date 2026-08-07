package org.vovchenko.webdavsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// App is dark-first, matching the WiFi-VPN reference; a light scheme is kept as a fallback only.
private val AppDarkColorScheme = darkColorScheme(
    primary = MdThemePrimary,
    onPrimary = MdThemeOnPrimary,
    primaryContainer = MdThemePrimaryContainer,
    onPrimaryContainer = MdThemeOnPrimaryContainer,
    secondary = MdThemeSecondary,
    onSecondary = MdThemeOnSecondary,
    background = MdThemeSurface,
    onBackground = MdThemeOnSurface,
    surface = MdThemeSurfaceCard,
    onSurface = MdThemeOnSurface,
    surfaceVariant = MdThemeSurface,
    error = MdThemeError,
)

private val AppLightColorScheme = lightColorScheme(
    primary = MdThemePrimaryContainer,
    secondary = MdThemeSecondary,
)

@Composable
fun WebDavSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AppDarkColorScheme else AppLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
