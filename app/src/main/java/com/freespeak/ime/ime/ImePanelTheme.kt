package com.freespeak.ime.ime

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.freespeak.ime.data.ImeColorScheme
import com.freespeak.ime.ui.theme.DefaultDarkColorScheme
import com.freespeak.ime.ui.theme.DefaultLightColorScheme

data class ImePanelPalette(
    val panelBg: Int,
    val keyBg: Int,
    val keyFg: Int,
    val keyRipple: Int,
    val roundKeyBg: Int,
    val roundKeyFg: Int,
    val statusText: Int,
    val micBg: Int,
    val micFg: Int,
    val micRipple: Int,
    val micCancelBg: Int,
    val micCancelFg: Int,
)

object ImePanelTheme {

    fun isNightMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    fun resolvePalette(
        context: Context,
        scheme: ImeColorScheme,
        isNight: Boolean = isNightMode(context),
    ): ImePanelPalette {
        return when (scheme) {
            ImeColorScheme.CLASSIC -> resolveClassicPalette(isNight)
            ImeColorScheme.DYNAMIC -> resolveDynamicPalette(context, isNight)
        }
    }

    private fun resolveClassicPalette(isNight: Boolean): ImePanelPalette {
        return if (!isNight) {
            ImePanelPalette(
                panelBg = 0xFFF0F0F0.toInt(),
                keyBg = 0xFFFFFFFF.toInt(),
                keyFg = 0xFF111111.toInt(),
                keyRipple = 0x1F000000,
                roundKeyBg = 0xFFE3E3E3.toInt(),
                roundKeyFg = 0xFF111111.toInt(),
                statusText = 0xFF666666.toInt(),
                micBg = 0xFF111111.toInt(),
                micFg = 0xFFFFFFFF.toInt(),
                micRipple = 0x33FFFFFF,
                micCancelBg = 0xFF757575.toInt(),
                micCancelFg = 0xFFFFFFFF.toInt(),
            )
        } else {
            ImePanelPalette(
                panelBg = 0xFF141414.toInt(),
                keyBg = 0xFF262626.toInt(),
                keyFg = 0xFFF2F2F2.toInt(),
                keyRipple = 0x26FFFFFF,
                roundKeyBg = 0xFF323232.toInt(),
                roundKeyFg = 0xFFF2F2F2.toInt(),
                statusText = 0xFF9E9E9E.toInt(),
                micBg = 0xFFEDEDED.toInt(),
                micFg = 0xFF141414.toInt(),
                micRipple = 0x26000000,
                micCancelBg = 0xFF616161.toInt(),
                micCancelFg = 0xFFFFFFFF.toInt(),
            )
        }
    }

    private fun resolveDynamicPalette(context: Context, isNight: Boolean): ImePanelPalette {
        val cs = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (isNight) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            isNight -> DefaultDarkColorScheme
            else -> DefaultLightColorScheme
        }

        return if (!isNight) {
            val onSurface = cs.onSurface.toArgb()
            val onPrimary = cs.onPrimary.toArgb()
            ImePanelPalette(
                panelBg = cs.surfaceContainer.toArgb(),
                keyBg = cs.surfaceContainerLowest.toArgb(),
                keyFg = onSurface,
                keyRipple = ColorUtils.setAlphaComponent(onSurface, 0x1F),
                roundKeyBg = cs.secondaryContainer.toArgb(),
                roundKeyFg = cs.onSecondaryContainer.toArgb(),
                statusText = cs.onSurfaceVariant.toArgb(),
                micBg = cs.primary.toArgb(),
                micFg = onPrimary,
                micRipple = ColorUtils.setAlphaComponent(onPrimary, 0x33),
                micCancelBg = cs.error.toArgb(),
                micCancelFg = cs.onError.toArgb(),
            )
        } else {
            val onSurface = cs.onSurface.toArgb()
            val onPrimary = cs.onPrimary.toArgb()
            ImePanelPalette(
                panelBg = cs.surfaceContainerLow.toArgb(),
                keyBg = cs.surfaceContainerHigh.toArgb(),
                keyFg = onSurface,
                keyRipple = ColorUtils.setAlphaComponent(onSurface, 0x24),
                roundKeyBg = cs.secondaryContainer.toArgb(),
                roundKeyFg = cs.onSecondaryContainer.toArgb(),
                statusText = cs.onSurfaceVariant.toArgb(),
                micBg = cs.primary.toArgb(),
                micFg = onPrimary,
                micRipple = ColorUtils.setAlphaComponent(onPrimary, 0x26),
                micCancelBg = cs.errorContainer.toArgb(),
                micCancelFg = cs.onErrorContainer.toArgb(),
            )
        }
    }

    fun pillKeyDrawable(fillColor: Int, rippleColor: Int, density: Float): Drawable {
        val radius = 100f * density
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun ovalKeyDrawable(fillColor: Int, rippleColor: Int): Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun micDrawable(fillColor: Int, rippleColor: Int, density: Float): Drawable {
        val radius = 200f * density
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }
}
