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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * Alternative colour + shape styles, alongside each app's own default.
 *
 * Three styles, chosen from the same place fonts are:
 *  - [ThemeStyle.CLASSIC] — each app's existing scheme (Hermes/Jeeves resolve
 *    the colours themselves; this file plays no part in those). It does share
 *    the home grid's tile layout, via [OutlinedSpaceTile]: the same squircle in
 *    the same 1:1 footprint, but unfilled — a hairline outline and monochrome
 *    contents instead of an accent glow and a tinted icon.
 *  - [ThemeStyle.CORTEX] — "Cortex": a fixed ember/teal palette plus squircle
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
 *    just with dynamic colours instead of the fixed palette — and it derives
 *    its five tile accents from the wallpaper `primary` rather than handing out
 *    scheme roles; see [tileAccent] for why the roles were the wrong source.
 *
 * Both Cortex and Material You also accept a user-chosen accent colour (see
 * [accentColorScheme]) instead of the built-in ember/teal or the wallpaper
 * palette — the same "pick your own colour" idea as the Bloub bot customiser's
 * swatch row, offered here because a single fixed accent can clash with a
 * particular wallpaper or just not be to taste (e.g. the default ember/teal
 * pairing reading weak against a near-white light background).
 */
enum class ThemeStyle(val storageKey: String) {
    CLASSIC("classic"),
    CORTEX("cortex"),
    MATERIAL_YOU("material_you"),
    ;

    /**
     * Whether this style swaps the app's whole `Shapes` set for the squircle
     * scale (see [SquircleShapes]).
     *
     * False for Classic, which keeps its own corner style everywhere else in
     * the app — its home tiles reach for [SquircleShapes.tile] directly rather
     * than restyling every surface.
     */
    val usesSquircleShapes: Boolean get() = this != CLASSIC

    /** Whether this style offers the custom-accent swatch picker. */
    val supportsCustomAccent: Boolean get() = this != CLASSIC

    companion object {
        val DEFAULT = CLASSIC

        fun fromStorageKey(key: String?): ThemeStyle =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * Cortex's fixed palette: an ember primary, a deep-teal secondary/tertiary, and
 * near-black/near-white neutrals rather than pure black/white.
 *
 * The neutrals are still the ones reimplemented from MyBrain's
 * `core/ui/.../theme/Color.kt` — plain numeric constants, not the copyrightable
 * part of that GPL codebase. The accent pair is no longer theirs: their cyan
 * (`#28B0DF`) only reached 2.51:1 against the white this scheme puts on top of
 * it, short of even the 3.0 minimum for UI components, so both accents were
 * rechosen for contrast as well as for looks.
 *
 * Both accents have to work on the near-black dark surface AND the near-white
 * light background, which is a tighter constraint than it looks: carrying white
 * text at 4.5:1 while staying 3:1 clear of the dark surface confines an accent's
 * relative luminance to roughly 0.12..0.18. Two accents inside that window can
 * differ in luminance by at most ~1.38:1, so what actually separates them is
 * hue, not brightness — ember and teal sit ~170 degrees apart, near-opposite on
 * the wheel. Ember: 5.12:1 on white, 3.63:1 on the dark surface, 5.03:1 on the
 * light background. Teal: 5.93 / 3.13 / 5.83.
 *
 * The light-mode ramp (Container* below) is this repo's own addition, not
 * MyBrain's: the original single flat `LightCard` tone for every container
 * level read flat/washed-out, so light mode here layers four tones instead of
 * one for real elevation cues, the surface/container split any Material3
 * scheme relies on for depth.
 */
internal object CortexHex {
    val Primary = Color(0xFFC1440E)
    val Secondary = Color(0xFF0D6E7C)
    val DarkNeutral = Color(0xFF131313)
    val LightBackground = Color(0xFFFDFDFD)
    val LightContainerLow = Color(0xFFF5F6F8)
    val LightContainer = Color(0xFFEFF1F3)
    val LightContainerHigh = Color(0xFFE6E8EB)
    val LightContainerHighest = Color(0xFFDCDEE2)
    val Error = Color(0xFFB3261E)
}

val CortexDark: ColorScheme = darkColorScheme(
    primary = CortexHex.Primary,
    onPrimary = Color.White,
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
    // Without these the scheme would inherit Material's baseline purple for
    // every container role, in a palette that has no purple in it.
    primaryContainer = accentContainer(CortexHex.Primary, CortexHex.DarkNeutral),
    onPrimaryContainer = tintedInkOn(accentContainer(CortexHex.Primary, CortexHex.DarkNeutral), CortexHex.Primary),
    secondaryContainer = accentContainer(CortexHex.Secondary, CortexHex.DarkNeutral),
    onSecondaryContainer = tintedInkOn(accentContainer(CortexHex.Secondary, CortexHex.DarkNeutral), CortexHex.Secondary),
    tertiaryContainer = accentContainer(CortexHex.Secondary, CortexHex.DarkNeutral),
    onTertiaryContainer = tintedInkOn(accentContainer(CortexHex.Secondary, CortexHex.DarkNeutral), CortexHex.Secondary),
    errorContainer = accentContainer(CortexHex.Error, CortexHex.DarkNeutral),
    onErrorContainer = tintedInkOn(accentContainer(CortexHex.Error, CortexHex.DarkNeutral), CortexHex.Error),
)

val CortexLight: ColorScheme = lightColorScheme(
    primary = CortexHex.Primary,
    onPrimary = Color.White,
    secondary = CortexHex.Secondary,
    onSecondary = Color.White,
    tertiary = CortexHex.Secondary,
    onTertiary = Color.White,
    background = CortexHex.LightBackground,
    onBackground = CortexHex.DarkNeutral,
    surface = CortexHex.LightBackground,
    onSurface = CortexHex.DarkNeutral,
    surfaceVariant = CortexHex.LightContainer,
    onSurfaceVariant = CortexHex.DarkNeutral,
    surfaceTint = CortexHex.LightContainer,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = CortexHex.LightContainerLow,
    surfaceContainer = CortexHex.LightContainer,
    surfaceContainerHigh = CortexHex.LightContainerHigh,
    surfaceContainerHighest = CortexHex.LightContainerHighest,
    surfaceDim = CortexHex.LightContainerHigh,
    surfaceBright = CortexHex.LightBackground,
    error = CortexHex.Error,
    onError = Color.White,
    primaryContainer = accentContainer(CortexHex.Primary, CortexHex.LightContainer),
    onPrimaryContainer = tintedInkOn(accentContainer(CortexHex.Primary, CortexHex.LightContainer), CortexHex.Primary),
    secondaryContainer = accentContainer(CortexHex.Secondary, CortexHex.LightContainer),
    onSecondaryContainer = tintedInkOn(accentContainer(CortexHex.Secondary, CortexHex.LightContainer), CortexHex.Secondary),
    tertiaryContainer = accentContainer(CortexHex.Secondary, CortexHex.LightContainer),
    onTertiaryContainer = tintedInkOn(accentContainer(CortexHex.Secondary, CortexHex.LightContainer), CortexHex.Secondary),
    errorContainer = accentContainer(CortexHex.Error, CortexHex.LightContainer),
    onErrorContainer = tintedInkOn(accentContainer(CortexHex.Error, CortexHex.LightContainer), CortexHex.Error),
)

/**
 * This colour as `[hue 0..360, saturation 0..1, value 0..1]`.
 *
 * Done in Kotlin rather than through `android.graphics.Color.colorToHSV` for
 * two reasons: the platform call quantises to 8 bits per channel on the way in,
 * and it is a native method, so any unit test touching colour maths would have
 * to drag in Robolectric to get past "not mocked". This is the same textbook
 * conversion, on the floats Compose already stores.
 */
private fun Color.hsv(): FloatArray {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta).mod(6f))
        max == green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return floatArrayOf(hue.mod(360f), if (max == 0f) 0f else delta / max, max)
}

/** The inverse of [hsv]: an opaque colour from hue/saturation/value. */
private fun hsvColor(hue: Float, saturation: Float, value: Float): Color {
    val sector = hue.mod(360f) / 60f
    val chroma = value * saturation
    val second = chroma * (1f - kotlin.math.abs(sector.mod(2f) - 1f))
    val base = value - chroma
    val (r, g, b) = when (sector.toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    return Color(r + base, g + base, b + base)
}

/** Hue-rotates this colour by [degrees] in HSV space; saturation/value unchanged. */
internal fun Color.hueRotate(degrees: Float): Color {
    val (hue, saturation, value) = hsv()
    return hsvColor(hue + degrees, saturation, value)
}

/** This colour's HSV saturation, 0f (grey) to 1f (fully saturated). */
internal fun Color.saturation(): Float = hsv()[1]

/** Bisection steps: 12 halvings resolve to 1/4096, finer than an 8-bit channel. */
private const val BISECTION_STEPS = 12

/**
 * Hue-rotates this colour by [degrees], then re-tunes the result so its
 * relative luminance matches the original's.
 *
 * A plain [hueRotate] holds HSV *value* constant, and value is not perceived
 * lightness: at S=1, V=1 a yellow has a relative luminance around 0.93 while a
 * blue has around 0.07 — a 13x spread. Rotating an accent around the wheel
 * therefore produces a set whose members differ wildly in visual weight, with
 * the yellows glaring and the blues sinking into a dark ground. Holding
 * luminance instead is the "equiluminant palette" rule: vary hue, hold
 * lightness, so every member reads with the same emphasis and — because
 * contrast ratio is a function of luminance alone — every member inherits the
 * anchor's contrast against whatever it sits on.
 *
 * Two knobs, in order of preference:
 *
 *  1. **Value.** Luminance is monotonic in value for a fixed hue and
 *     saturation, so bisection finds it directly. This is the whole answer
 *     whenever the target is inside the hue's reach.
 *  2. **Saturation**, only when it is not. The gamut's ceiling varies by hue —
 *     a fully saturated blue tops out around 0.07 and simply cannot be made as
 *     bright as a mid yellow — and the way out is the classic one: trade chroma
 *     for lightness, desaturating toward white until the target is met.
 *     Luminance is monotonically *decreasing* in saturation, so this bisects
 *     too. This branch is what a light Material You anchor needs: a tone-80
 *     wallpaper primary sits near luminance 0.55, above what a saturated blue
 *     or magenta can reach, and without it those two stops would come out
 *     visibly heavier than the other three.
 */
internal fun Color.hueRotateEquiluminant(degrees: Float): Color {
    val target = luminance()
    val (hue, saturation, _) = hsv()
    val rotated = hue + degrees

    if (hsvColor(rotated, saturation, 1f).luminance() < target) {
        var low = 0f
        var high = saturation
        var result = hsvColor(rotated, saturation, 1f)
        repeat(BISECTION_STEPS) {
            val mid = (low + high) / 2f
            result = hsvColor(rotated, mid, 1f)
            if (result.luminance() < target) high = mid else low = mid
        }
        return result
    }

    var low = 0f
    var high = 1f
    var result = this
    repeat(BISECTION_STEPS) {
        val mid = (low + high) / 2f
        result = hsvColor(rotated, saturation, mid)
        if (result.luminance() < target) low = mid else high = mid
    }
    return result
}

/**
 * The luminance at which black and white are equally legible.
 *
 * WCAG contrast against white is `1.05 / (L + 0.05)` and against black is
 * `(L + 0.05) / 0.05`; setting them equal gives `L = sqrt(0.0525) - 0.05`, or
 * 0.179 — noticeably darker than the midpoint intuition suggests, because the
 * ratio is anchored at black rather than centred.
 */
private const val INK_FLIP_LUMINANCE = 0.1791f

/**
 * Black or white, whichever carries better against [background].
 *
 * Used anywhere the ground is not known until runtime — a wallpaper-derived
 * Material You role, a user-picked accent seed, the home screen's gradient
 * card — since a hardcoded white is only ever right for part of the range.
 *
 * The split is [INK_FLIP_LUMINANCE], where the two candidates actually cross.
 * This previously flipped at 0.45, which is well past that point and cost real
 * legibility across the whole mid-range: a ground at L=0.3 got white text at
 * 3.0:1 when the black it should have had would have given 7.0:1. Anything
 * between 0.18 and 0.45 — which is most mid-tone accents, including the
 * wallpaper-derived gradient behind the active-model card — was taking the
 * worse of the two.
 */
fun contrastInkOn(background: Color): Color = contrastInkAcross(listOf(background))

/** WCAG 2.1 contrast ratio between two opaque colours, 1.0 to 21.0. */
private fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()
    return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
}

/**
 * Blends two colours the way `Brush.linearGradient` does — component-wise on
 * the encoded sRGB values.
 *
 * Deliberately *not* Compose's `lerp(Color, Color, Float)`, which interpolates
 * perceptually in Oklab and keeps a blend between two opposed hues chromatic
 * the whole way across. The shader does no such thing: it mixes the encoded
 * channels, so a magenta-to-green sweep passes through a washed-out mauve. That
 * middle is where a label is hardest to read, so sampling has to reproduce what
 * is actually painted rather than the nicer curve. Verified against a device
 * capture: this predicts (119, 99, 158) at the halfway point of the card, and
 * the screenshot measured (121, 99, 160).
 */
private fun mixSrgb(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
)

/** Interpolation samples taken between each adjacent pair of gradient stops. */
private val GRADIENT_SAMPLES = listOf(0.25f, 0.5f, 0.75f)

/**
 * Black or white, whichever holds up best everywhere a gradient of [stops]
 * actually goes.
 *
 * For a single stop this is exactly [contrastInkOn] and its flip point. For a
 * gradient it is the rule a flip point cannot express: a label has to stay
 * legible along the whole sweep, so the ink that wins is the one with the
 * better *worst case* rather than the one that wins on average.
 *
 * The sweep, not the stops. Two colours do not bound what lies between them —
 * interpolating between opposed hues passes through a desaturated middle that
 * is lighter than either end, and that middle is where the text is hardest to
 * read. The wallpaper-derived card measured on device is exactly this shape:
 * magenta to green, black ahead at both ends (5.0 and 5.1 against 4.2) and
 * behind by a full point in the middle. Judging endpoints alone picks black and
 * hands back the middle, so the endpoints get [GRADIENT_SAMPLES] between them
 * and the worst of the whole set decides.
 *
 * Sampled rather than solved: the true worst point has no closed form, and
 * quarter steps bracket it closely enough that the answer only changes where
 * the two inks are near-tied anyway.
 */
fun contrastInkAcross(stops: List<Color>): Color {
    if (stops.isEmpty()) return Color.White
    val sweep = buildList {
        addAll(stops)
        for (index in 0 until stops.lastIndex) {
            for (fraction in GRADIENT_SAMPLES) {
                add(mixSrgb(stops[index], stops[index + 1], fraction))
            }
        }
    }
    val asWhite = sweep.minOf { contrastRatio(Color.White, it) }
    val asBlack = sweep.minOf { contrastRatio(Color.Black, it) }
    return if (asBlack > asWhite) Color.Black else Color.White
}

/**
 * A full [ColorScheme] built from one user-chosen accent — the "custom
 * colour" option for [ThemeStyle.CORTEX] and [ThemeStyle.MATERIAL_YOU].
 * Secondary/tertiary are [seed] rotated ±40° in hue, so the result still
 * reads as one palette rather than three unrelated colours; the neutral
 * surfaces reuse Cortex's own dark/light ramp so shape and depth stay
 * consistent whichever accent is picked. `onPrimary`/`onSecondary`/
 * `onTertiary` are derived by contrast rather than hardcoded white, so a
 * light, low-contrast accent choice doesn't wash out its own label text.
 */
fun accentColorScheme(seed: Color, darkTheme: Boolean): ColorScheme {
    val secondary = seed.hueRotate(40f)
    val tertiary = seed.hueRotate(-40f)
    // The ground a container is blended toward, per mode.
    val containerBase = if (darkTheme) CortexHex.DarkNeutral else CortexHex.LightContainer
    val primaryBox = accentContainer(seed, containerBase)
    val secondaryBox = accentContainer(secondary, containerBase)
    val tertiaryBox = accentContainer(tertiary, containerBase)
    val errorBox = accentContainer(CortexHex.Error, containerBase)
    return if (darkTheme) {
        darkColorScheme(
            primary = seed,
            onPrimary = contrastInkOn(seed),
            secondary = secondary,
            onSecondary = contrastInkOn(secondary),
            tertiary = tertiary,
            onTertiary = contrastInkOn(tertiary),
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
            primaryContainer = primaryBox,
            onPrimaryContainer = tintedInkOn(primaryBox, seed),
            secondaryContainer = secondaryBox,
            onSecondaryContainer = tintedInkOn(secondaryBox, secondary),
            tertiaryContainer = tertiaryBox,
            onTertiaryContainer = tintedInkOn(tertiaryBox, tertiary),
            errorContainer = errorBox,
            onErrorContainer = tintedInkOn(errorBox, CortexHex.Error),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = contrastInkOn(seed),
            secondary = secondary,
            onSecondary = contrastInkOn(secondary),
            tertiary = tertiary,
            onTertiary = contrastInkOn(tertiary),
            background = CortexHex.LightBackground,
            onBackground = CortexHex.DarkNeutral,
            surface = CortexHex.LightBackground,
            onSurface = CortexHex.DarkNeutral,
            surfaceVariant = CortexHex.LightContainer,
            onSurfaceVariant = CortexHex.DarkNeutral,
            surfaceTint = CortexHex.LightContainer,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = CortexHex.LightContainerLow,
            surfaceContainer = CortexHex.LightContainer,
            surfaceContainerHigh = CortexHex.LightContainerHigh,
            surfaceContainerHighest = CortexHex.LightContainerHighest,
            surfaceDim = CortexHex.LightContainerHigh,
            surfaceBright = CortexHex.LightBackground,
            error = CortexHex.Error,
            onError = Color.White,
            primaryContainer = primaryBox,
            onPrimaryContainer = tintedInkOn(primaryBox, seed),
            secondaryContainer = secondaryBox,
            onSecondaryContainer = tintedInkOn(secondaryBox, secondary),
            tertiaryContainer = tertiaryBox,
            onTertiaryContainer = tintedInkOn(tertiaryBox, tertiary),
            errorContainer = errorBox,
            onErrorContainer = tintedInkOn(errorBox, CortexHex.Error),
        )
    }
}

/** How far a container sits from its accent, toward the surface behind it. */
private const val CONTAINER_BLEND = 0.78f

/** Contrast a container's own label has to clear against it. */
private const val CONTAINER_TEXT_RATIO = 4.5f

/** Value steps searched when tinting a container's label. */
private const val TINT_STEPS = 24

/**
 * A container tone for [accent]: mostly [surface], carrying just enough of the
 * accent to read as related to it.
 *
 * Material's own schemes ship container roles, but only for their baseline
 * palette — `darkColorScheme()` and `lightColorScheme()` leave every role you
 * do not name at Material's default purple. A scheme that sets `primary` and
 * stops therefore hands out a *purple* `primaryContainer`, which is how an
 * ember theme ended up drawing a purple selection chip in its own theme picker.
 * Every container role is filled from the accent here so nothing baseline
 * survives.
 */
private fun accentContainer(accent: Color, surface: Color): Color =
    mixSrgb(accent, surface, CONTAINER_BLEND)

/**
 * A label for [container] that keeps [accent]'s hue instead of falling back to
 * flat black or white.
 *
 * Walks the accent's own hue and saturation up and down in value, keeps the
 * tones that clear [CONTAINER_TEXT_RATIO] against the container, and takes
 * whichever of those sits closest to the accent's own luminance — the most
 * faithful tone that is still legible. Falls back to [contrastInkOn] when the
 * hue cannot reach the ratio at any value, which a very light or very dark
 * container can force.
 */
private fun tintedInkOn(container: Color, accent: Color): Color {
    val (hue, saturation, _) = accent.hsv()
    val target = accent.luminance()
    return (0..TINT_STEPS)
        .map { hsvColor(hue, saturation, it.toFloat() / TINT_STEPS) }
        .filter { contrastRatio(it, container) >= CONTAINER_TEXT_RATIO }
        .minByOrNull { kotlin.math.abs(it.luminance() - target) }
        ?: contrastInkOn(container)
}

/**
 * Resolves an alternative colour scheme, or null when [style] is
 * [ThemeStyle.CLASSIC] or Material You is requested on a device below API 31
 * with no [accentSeed] override — both mean "the caller's own default
 * applies".
 *
 * [accentSeed], when non-null, overrides Cortex's fixed ember/teal or
 * Material You's wallpaper palette with a scheme generated from that one
 * colour (see [accentColorScheme]) — the user-chosen alternative to either
 * default.
 *
 * `@Composable` because Material You needs [LocalContext] to read the device
 * wallpaper palette; the classic default resolved by each app never does.
 */
@Composable
fun resolveAltColorScheme(
    style: ThemeStyle,
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentSeed: Color? = null,
): ColorScheme? =
    when (style) {
        ThemeStyle.CLASSIC -> null
        ThemeStyle.CORTEX ->
            accentSeed?.let { accentColorScheme(it, darkTheme) } ?: if (darkTheme) CortexDark else CortexLight
        ThemeStyle.MATERIAL_YOU -> {
            if (accentSeed != null) {
                accentColorScheme(accentSeed, darkTheme)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
