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

internal val DefaultLightColorScheme = lightColorScheme(
    primary = Color(0xFF2352F5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF001453),
    secondary = Color(0xFF008559),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E6F8),
    onSecondaryContainer = Color(0xFF101C2B),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF1F8),
    surfaceVariant = Color(0xFFEAEEF7),
)

internal val DefaultDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AA6FF),
    onPrimary = Color(0xFF002482),
    primaryContainer = Color(0xFF1538B8),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF42E0A8),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF2B3548),
    onSecondaryContainer = Color(0xFFD8E2F8),
    background = Color(0xFF10131A),
    surface = Color(0xFF181C26),
    surfaceContainerLow = Color(0xFF161A24),
    surfaceContainerHigh = Color(0xFF252B3B),
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
        darkTheme -> DefaultDarkColorScheme
        else -> DefaultLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
