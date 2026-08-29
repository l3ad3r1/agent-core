package com.hermes.agent.ui.theme.alt

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Alternative colour styles, alongside each app's own monochrome default.
 *
 * Two styles, chosen from the same place fonts are:
 *  - [ThemeStyle.CLASSIC] — each app's existing scheme (Hermes/Jeeves resolve it
 *    themselves; this file plays no part).
 *  - [ThemeStyle.MYBRAIN] — a fixed cyan/purple palette in the shape of the
 *    MyBrain app's own colours (github.com/mhss1/MyBrain, GPL-3.0). Only the
 *    colour *values* are taken from its `Color.kt` — plain hex constants, not
 *    creative expression — and reimplemented fresh here rather than copied, so
 *    nothing GPL-licensed enters this MIT tree. See the note on the constants
 *    below for the exact source values.
 *  - [ThemeStyle.MATERIAL_YOU] — Android's own per-device wallpaper palette
 *    (`dynamicColorScheme`), available from Android 12 (API 31); falls back to
 *    [ThemeStyle.CLASSIC] below that, since there is nothing to derive a
 *    dynamic palette from.
 */
enum class ThemeStyle(val storageKey: String) {
    CLASSIC("classic"),
    MYBRAIN("mybrain"),
    MATERIAL_YOU("material_you"),
    ;

    companion object {
        val DEFAULT = CLASSIC

        fun fromStorageKey(key: String?): ThemeStyle =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * Hex values reimplemented from MyBrain's `core/ui/.../theme/Color.kt` (as of
 * its `master` branch): a cyan-blue primary, a violet secondary/tertiary, and
 * near-black/near-white neutrals rather than pure black/white. These are plain
 * numeric constants — not the copyrightable part of that GPL codebase — used
 * here to offer the same *look* as an alternative to each app's own default.
 */
private object MyBrainHex {
    val Primary = Color(0xFF28B0DF)
    val OnPrimary = Color.White
    val Secondary = Color(0xFF5F12CA)
    val DarkNeutral = Color(0xFF131313)
    val LightCard = Color(0xFFEFF1F3)
    val LightBackground = Color(0xFFFDFDFD)
    val Error = Color(0xFFB3261E)
}

val MyBrainDark: ColorScheme = darkColorScheme(
    primary = MyBrainHex.Primary,
    onPrimary = MyBrainHex.OnPrimary,
    secondary = MyBrainHex.Secondary,
    onSecondary = Color.White,
    tertiary = MyBrainHex.Secondary,
    onTertiary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = MyBrainHex.DarkNeutral,
    onSurface = Color.White,
    surfaceVariant = MyBrainHex.DarkNeutral,
    onSurfaceVariant = Color.White,
    surfaceTint = MyBrainHex.DarkNeutral,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = MyBrainHex.DarkNeutral,
    surfaceContainer = MyBrainHex.DarkNeutral,
    surfaceContainerHigh = MyBrainHex.DarkNeutral,
    surfaceContainerHighest = MyBrainHex.DarkNeutral,
    surfaceDim = MyBrainHex.DarkNeutral,
    surfaceBright = MyBrainHex.DarkNeutral,
    error = MyBrainHex.Error,
    onError = Color.White,
)

val MyBrainLight: ColorScheme = lightColorScheme(
    primary = MyBrainHex.Primary,
    onPrimary = MyBrainHex.OnPrimary,
    secondary = MyBrainHex.Secondary,
    onSecondary = Color.White,
    tertiary = MyBrainHex.Secondary,
    onTertiary = Color.White,
    background = MyBrainHex.LightBackground,
    onBackground = MyBrainHex.DarkNeutral,
    surface = MyBrainHex.LightCard,
    onSurface = MyBrainHex.DarkNeutral,
    surfaceVariant = MyBrainHex.LightCard,
    onSurfaceVariant = MyBrainHex.DarkNeutral,
    surfaceTint = MyBrainHex.LightCard,
    surfaceContainerLowest = MyBrainHex.LightBackground,
    surfaceContainerLow = MyBrainHex.LightCard,
    surfaceContainer = MyBrainHex.LightCard,
    surfaceContainerHigh = MyBrainHex.LightCard,
    surfaceContainerHighest = MyBrainHex.LightCard,
    surfaceDim = MyBrainHex.LightCard,
    surfaceBright = MyBrainHex.LightCard,
    error = MyBrainHex.Error,
    onError = Color.White,
)

/**
 * Resolves an alternative colour scheme, or null when [style] is
 * [ThemeStyle.CLASSIC] or Material You is requested on a device below API 31 —
 * both cases mean "the caller's own default applies".
 *
 * `@Composable` because Material You needs [LocalContext] to read the device
 * wallpaper palette; the classic default resolved by each app never does.
 */
@Composable
fun resolveAltColorScheme(style: ThemeStyle, darkTheme: Boolean = isSystemInDarkTheme()): ColorScheme? =
    when (style) {
        ThemeStyle.CLASSIC -> null
        ThemeStyle.MYBRAIN -> if (darkTheme) MyBrainDark else MyBrainLight
        ThemeStyle.MATERIAL_YOU -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                null
            }
        }
    }
