package com.freespeak.ime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fallback palette for the IME panel "dynamic" scheme on pre-Android 12 devices.
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

/** The single accent colour used sparingly across the monochrome UI. */
val NothingRed = Color(0xFFD71921)

private val MonoLight = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = NothingRed,
    onTertiary = Color.White,
    background = Color(0xFFF4F4F4),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF4F4F4),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF6E6E6E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    error = NothingRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E6),
    onErrorContainer = NothingRed,
)

private val MonoDark = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF262626),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = NothingRed,
    onTertiary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF8E8E8E),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF242424),
    error = Color(0xFFFF3B41),
    onError = Color.White,
    errorContainer = Color(0xFF3A0B0D),
    onErrorContainer = Color(0xFFFF8A8E),
)

/** Monospace "readout" style used for labels, numbers and status (Nothing-like). */
@Immutable
data class MonoTypes(
    val label: TextStyle,
    val readout: TextStyle,
    val display: TextStyle,
)

private val LocalMonoTypes = staticCompositionLocalOf {
    MonoTypes(TextStyle.Default, TextStyle.Default, TextStyle.Default)
}

object FreeSpeakType {
    val mono: MonoTypes
        @Composable @ReadOnlyComposable get() = LocalMonoTypes.current
}

private val monoTypes = MonoTypes(
    label = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
    ),
    readout = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    ),
    display = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
)

private val baseTypography = Typography()
private val appTypography = baseTypography.copy(
    titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.Normal),
    titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.Normal),
)

@Composable
fun FreeSpeakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalMonoTypes provides monoTypes) {
        MaterialTheme(
            colorScheme = if (darkTheme) MonoDark else MonoLight,
            typography = appTypography,
            content = content,
        )
    }
}
