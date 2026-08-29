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
 * Alternative colour + shape styles, alongside each app's own default.
 *
 * Three styles, chosen from the same place fonts are:
 *  - [ThemeStyle.CLASSIC] — each app's existing scheme (Hermes/Jeeves resolve it
 *    themselves; this file plays no part).
 *  - [ThemeStyle.CORTEX] — "Cortex": a fixed cyan/violet palette plus squircle
 *    card shapes and icon tiles, in the shape of the MyBrain app's own UI
 *    (github.com/mhss1/MyBrain, GPL-3.0). Nothing here is copied from that
 *    codebase — only *values* (hex colour constants, a corner-radius number)
 *    and the general *look* (a colour family, a corner style, "icon on a
 *    tinted squircle" as a tile pattern) are reimplemented fresh, all in this
 *    repo's own code, using this repo's own icon set (Material Icons) rather
 *    than MyBrain's Flaticon-attributed artwork. See the note on the
 *    constants below for the exact source values, and [SquircleShape] /
 *    [SpaceTile] for the shape and tile pieces.
 *  - [ThemeStyle.MATERIAL_YOU] — Android's own per-device wallpaper palette
 *    (`dynamicColorScheme`), available from Android 12 (API 31); falls back to
 *    [ThemeStyle.CLASSIC] below that, since there is nothing to derive a
 *    dynamic palette from. Shares Cortex's squircle/icon-tile shape language,
 *    just with dynamic colours instead of the fixed palette.
 */
enum class ThemeStyle(val storageKey: String) {
    CLASSIC("classic"),
    CORTEX("cortex"),
    MATERIAL_YOU("material_you"),
    ;

    /** Whether this style should render squircle card/tile shapes (see [SquircleShapes]). */
    val usesSquircleShapes: Boolean get() = this != CLASSIC

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
private object CortexHex {
    val Primary = Color(0xFF28B0DF)
    val OnPrimary = Color.White
    val Secondary = Color(0xFF5F12CA)
    val DarkNeutral = Color(0xFF131313)
    val LightCard = Color(0xFFEFF1F3)
    val LightBackground = Color(0xFFFDFDFD)
    val Error = Color(0xFFB3261E)
}

val CortexDark: ColorScheme = darkColorScheme(
    primary = CortexHex.Primary,
    onPrimary = CortexHex.OnPrimary,
    secondary = CortexHex.Secondary,
    onSecondary = Color.White,
    tertiary = CortexHex.Secondary,
    onTertiary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = CortexHex.DarkNeutral,
    onSurface = Color.White,
    surfaceVariant = CortexHex.DarkNeutral,
    onSurfaceVariant = Color.White,
    surfaceTint = CortexHex.DarkNeutral,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = CortexHex.DarkNeutral,
    surfaceContainer = CortexHex.DarkNeutral,
    surfaceContainerHigh = CortexHex.DarkNeutral,
    surfaceContainerHighest = CortexHex.DarkNeutral,
    surfaceDim = CortexHex.DarkNeutral,
    surfaceBright = CortexHex.DarkNeutral,
    error = CortexHex.Error,
    onError = Color.White,
)

val CortexLight: ColorScheme = lightColorScheme(
    primary = CortexHex.Primary,
    onPrimary = CortexHex.OnPrimary,
    secondary = CortexHex.Secondary,
    onSecondary = Color.White,
    tertiary = CortexHex.Secondary,
    onTertiary = Color.White,
    background = CortexHex.LightBackground,
    onBackground = CortexHex.DarkNeutral,
    surface = CortexHex.LightCard,
    onSurface = CortexHex.DarkNeutral,
    surfaceVariant = CortexHex.LightCard,
    onSurfaceVariant = CortexHex.DarkNeutral,
    surfaceTint = CortexHex.LightCard,
    surfaceContainerLowest = CortexHex.LightBackground,
    surfaceContainerLow = CortexHex.LightCard,
    surfaceContainer = CortexHex.LightCard,
    surfaceContainerHigh = CortexHex.LightCard,
    surfaceContainerHighest = CortexHex.LightCard,
    surfaceDim = CortexHex.LightCard,
    surfaceBright = CortexHex.LightCard,
    error = CortexHex.Error,
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
        ThemeStyle.CORTEX -> if (darkTheme) CortexDark else CortexLight
        ThemeStyle.MATERIAL_YOU -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                null
            }
        }
    }

/**
 * Resolves an alternative [androidx.compose.material3.Shapes] set, or null for
 * [ThemeStyle.CLASSIC] (meaning "the caller's own default applies"). Cortex and
 * Material You both use [SquircleShapes] — the squircle corner style is part of
 * the "MyBrain-shaped" look regardless of which colours are behind it.
 */
fun resolveAltShapes(style: ThemeStyle): androidx.compose.material3.Shapes? =
    if (style.usesSquircleShapes) {
        androidx.compose.material3.Shapes(
            extraSmall = SquircleShapes.extraSmall,
            small = SquircleShapes.small,
            medium = SquircleShapes.medium,
            large = SquircleShapes.large,
            extraLarge = SquircleShapes.extraLarge,
        )
    } else {
        null
    }
