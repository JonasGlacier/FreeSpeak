package com.freespeak.ime.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2352F5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF001453),
    secondary = Color(0xFF008559),
    onSecondary = Color.White,
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEAEEF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AA6FF),
    onPrimary = Color(0xFF002482),
    primaryContainer = Color(0xFF1538B8),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF42E0A8),
    onSecondary = Color(0xFF003824),
    background = Color(0xFF10131A),
    surface = Color(0xFF181C26),
    surfaceVariant = Color(0xFF232938),
)

@Composable
fun FreeSpeakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
