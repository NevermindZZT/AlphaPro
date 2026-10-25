package com.letter.alphapro.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AlphaOrangePrimary,
    onPrimary = AlphaOrangeOnPrimary,
    primaryContainer = AlphaOrangeOnContainer,
    onPrimaryContainer = AlphaOrangeContainer,
    secondary = AlphaBlueSecondary,
    onSecondary = AlphaBlueOnSecondary,
    secondaryContainer = AlphaBlueOnContainer,
    onSecondaryContainer = AlphaBlueContainer,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = AlphaOrangePrimary,
    onPrimary = AlphaOrangeOnPrimary,
    primaryContainer = AlphaOrangeContainer,
    onPrimaryContainer = AlphaOrangeOnContainer,
    secondary = AlphaBlueSecondary,
    onSecondary = AlphaBlueOnSecondary,
    secondaryContainer = AlphaBlueContainer,
    onSecondaryContainer = AlphaBlueOnContainer,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

@Composable
fun AlphaProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // Follows system theme by default
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                // In dark theme, status bar text should be light (isAppearanceLightStatusBars = false)
                // In light theme, status bar text should be dark (isAppearanceLightStatusBars = true)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
