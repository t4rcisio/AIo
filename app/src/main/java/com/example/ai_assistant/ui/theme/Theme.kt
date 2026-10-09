package com.example.ai_assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = AioPineGreen,
    onPrimary = AioSurface,
    primaryContainer = AioSelection,
    onPrimaryContainer = AioPineGreen,
    secondary = AioGraphite,
    onSecondary = AioSurface,
    secondaryContainer = AioSelection,
    onSecondaryContainer = AioGraphite,
    background = AioBackground,
    onBackground = AioGraphite,
    surface = AioSurface,
    onSurface = AioGraphite,
    surfaceVariant = AioSurfaceVariant,
    onSurfaceVariant = AioTextSecondary,
    outline = AioOutline,
    error = AioError,
    onError = AioSurface
)

private val DarkColorScheme = darkColorScheme(
    primary = AioPineGreen,
    onPrimary = AioSurface,
    primaryContainer = AioPineGreen.copy(alpha = 0.3f),
    onPrimaryContainer = AioSelection,
    secondary = AioSelection,
    onSecondary = AioGraphite,
    background = AioGraphite,
    onBackground = AioBackground,
    surface = Color(0xFF28302B),
    onSurface = AioBackground,
    surfaceVariant = Color(0xFF333D37),
    onSurfaceVariant = Color(0xFFA6B2AA),
    outline = Color(0xFF45524B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun AI_assistantTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}