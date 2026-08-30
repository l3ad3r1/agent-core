package com.hermes.agent.ui.theme.alt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The accent palette for [ThemeStyle.CORTEX]'s tiles.
 *
 * Four of these five are the hues originally carried over from MyBrain's
 * `Color.kt` and are unchanged. Two things moved:
 *
 *  - **Purple became [Teal]** — and specifically the scheme's own secondary, so
 *    the home grid and the chrome read as one palette instead of two unrelated
 *    ones. It also lands in the widest remaining gap on the wheel: the hues now
 *    run 4, 36, 145, 188, 218 where purple had sat alone at 262. The old purple
 *    could not be kept anyway, reaching only 2.93:1 on the dark surface.
 *  - **Orange was darkened one step**, from `#E78A00` to `#CC7A00`. The hue is
 *    identical to within a tenth of a degree — it is the same orange — but the
 *    original managed 2.57:1 against the near-white light background, under the
 *    3:1 floor for a graphical object, so its icon washed out in light mode.
 *
 * Every hue here clears 3:1 on both the near-black surface and the near-white
 * background. Order matters: the home screen's "active model" card blends
 * indices 1 and 4, so [Red] and [Teal] sit there for a 184-degree warm-to-cool
 * sweep.
 */
object CortexAccents {
    val Blue = Color(0xFF2965C9)
    val Red = Color(0xFFD53A2F)
    val Green = Color(0xFF1E9651)
    val Orange = Color(0xFFCC7A00)
    val Teal = Color(0xFF0D6E7C)
    val all = listOf(Blue, Red, Green, Orange, Teal)
}

/** Below this HSV saturation there is no hue left to rotate meaningfully. */
private const val MIN_TILE_CHROMA = 0.10f

/** How far apart the five tile accents sit on the wheel: a regular pentad. */
private const val TILE_HUE_STEP = 72f

/** Per-step lightness move for the achromatic fallback ramp. */
private const val TILE_NEUTRAL_STEP = 0.13f

/**
 * The Nth of five accents derived from one [anchor] colour, for tiles drawn on
 * [ground].
 *
 * Used when the custom-colour picker overrides [ThemeStyle.CORTEX]'s fixed
 * five: the grid still wants five, and they have to come from the one colour
 * the user chose.
 *
 * Hue does the separating and lightness is held still — an equiluminant pentad,
 * five stops 72 degrees apart around the wheel at the anchor's own luminance
 * (see [hueRotateEquiluminant] for why value cannot be the thing held constant).
 * Because contrast ratio depends only on luminance, holding it means all five
 * inherit whatever contrast the anchor had against [ground]. Nothing here needs
 * a light/dark branch as a result.
 *
 * A near-grey anchor is the one case hue cannot solve — rotating the hue of a
 * colour that has none returns the same grey five times. Then the ramp moves in
 * the only dimension an achromatic palette has: lightness, stepping *away* from
 * [ground] so every step gains contrast rather than losing it.
 */
internal fun derivedTileAccent(anchor: Color, ground: Color, index: Int): Color {
    val step = index.mod(5)
    if (step == 0) return anchor
    if (anchor.saturation() < MIN_TILE_CHROMA) {
        val away = if (ground.luminance() < 0.5f) Color.White else Color.Black
        return lerp(anchor, away, step * TILE_NEUTRAL_STEP)
    }
    return anchor.hueRotateEquiluminant(step * TILE_HUE_STEP)
}

/**
 * Picks the Nth accent for a home-grid tile, given the active alternate style.
 *
 * The three styles want three different things from this:
 *
 *  - **[ThemeStyle.MATERIAL_YOU] is a single-colour style.** Every tile, the
 *    active-model card and every thread dot get the *same* colour: the one
 *    chosen in the custom-colour picker, or the wallpaper's own `primary` when
 *    nothing is picked. [index] is ignored on purpose.
 *  - **[ThemeStyle.CORTEX]** cycles its own fixed, hand-picked five
 *    ([CortexAccents]) — or, when the picker overrides them, five derived from
 *    that one colour by [derivedTileAccent], since a grid of five identical
 *    tiles is not what Cortex's layout was built around.
 *  - **[ThemeStyle.CLASSIC]** has no accent at all; its tiles are outlined
 *    rather than filled, so the value is only a fallback for callers that still
 *    want something.
 *
 * Material You deliberately does not hand out `primary`/`secondary`/`tertiary`/
 * `error` as four separate accents, which is what it used to do. Those roles
 * are built to harmonise, not to contrast: `secondary` is `primary` at reduced
 * chroma, so side by side it read as a washed-out copy rather than a different
 * tile, and `error` is a fixed red carrying the meaning "something has gone
 * wrong", so a tile tinted with it read as broken while ignoring the wallpaper
 * the rest of the palette came from.
 */
fun tileAccent(style: ThemeStyle, colorScheme: ColorScheme, index: Int, accentSeed: Color? = null): Color =
    when (style) {
        ThemeStyle.MATERIAL_YOU -> accentSeed ?: colorScheme.primary
        ThemeStyle.CORTEX ->
            if (accentSeed != null) {
                derivedTileAccent(accentSeed, colorScheme.surfaceVariant, index)
            } else {
                CortexAccents.all[index.mod(CortexAccents.all.size)]
            }
        ThemeStyle.CLASSIC -> colorScheme.primary
    }

/**
 * A soft radial blob of [accent] behind [base], fading to transparent — the
 * same general "one gradient smear, corner-anchored" idea as MyBrain's own
 * card background, reimplemented rather than ported (their version lives in
 * a `Modifier.drawWithCache` extension too; this is an independent one).
 */
private fun Modifier.accentGlow(accent: Color, base: Color, alpha: Float = 0.4f): Modifier =
    this.drawWithCache {
        val radius = size.maxDimension * 0.9f
        val brush = Brush.radialGradient(
            colors = listOf(base.copy(alpha = alpha).compositeOver(accent), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.15f),
            radius = radius,
        )
        onDrawBehind {
            drawRect(base)
            drawRect(brush)
        }
    }

/** Secondary-text opacity, shared by both fills and the outlined variant. */
private const val TileSubtitleAlpha = 0.75f

/**
 * A squircle "space" tile: title + subtitle up top, a big icon resting
 * bottom-right — the shape MyBrain's own home "Spaces" grid uses for
 * Notes/Tasks/Diary/Bookmarks/Calendar/Assistant, reimplemented here (own code,
 * own icon set — Material Icons, not their Flaticon-attributed artwork) so
 * [ThemeStyle.CORTEX] and [ThemeStyle.MATERIAL_YOU] can offer the same look for
 * any app/module tile.
 *
 * Two fills, because the two styles want different things from the same shape:
 *
 *  - **[solid] false** (Cortex) — a soft [accent]-tinted glow over the scheme's
 *    surface, with the accent showing up properly only in the icon. The accent
 *    is decoration on a neutral card, so the text stays `onSurfaceVariant`.
 *  - **[solid] true** (Material You) — the accent *is* the card. Text and icon
 *    then have to be read against the accent rather than against a surface, so
 *    they come from [contrastInkOn] instead of from the scheme: a wallpaper can
 *    hand back an accent of any lightness, and `onSurfaceVariant` is only ever
 *    right for one end of that range.
 */
@Composable
fun SpaceTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (solid) contrastInkOn(accent) else scheme.onSurfaceVariant
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(SquircleShapes.tile)
            .then(
                if (solid) {
                    Modifier.background(accent)
                } else {
                    Modifier.accentGlow(accent = accent, base = scheme.surfaceVariant)
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = TileSubtitleAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            // On a solid fill the accent would be invisible against itself, so
            // the icon joins the text as ink; on a glow it stays the accent,
            // which is where that fill shows its colour at full strength.
            tint = if (solid) ink else accent,
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.End),
        )
    }
}

/** Hairline weight of the [OutlinedSpaceTile] border. */
private val OutlineTileStroke = 1.dp

/**
 * Border opacity for [OutlinedSpaceTile]. Just short of solid: a full-strength
 * hairline against an OLED-black ground aliases into a shimmer on the curved
 * part of the squircle, and easing it back one notch settles the curve without
 * costing any noticeable brightness.
 */
private const val OutlineTileBorderAlpha = 0.9f

/**
 * The unfilled counterpart to [SpaceTile], for [ThemeStyle.CLASSIC].
 *
 * Same tile: same squircle, same 1:1 footprint, same title-and-subtitle up top
 * with a large icon resting bottom-right. What changes is that the accent glow
 * and the accent-tinted icon are gone — no fill at all, just a hairline outline
 * and monochrome contents on the page's own background.
 *
 * Every colour here comes from `onSurface`, which is what makes it hold up in
 * both modes without a branch: Classic is a strictly monochrome scheme, so
 * `onSurface` is a near-white (#F7F7F7) on the OLED-black dark half and a near-
 * black on the white half. "White outline, white icons" in dark mode is
 * therefore the same one rule that gives the correct ink in light mode, rather
 * than a literal [Color.White] that would vanish against a white page.
 */
@Composable
fun OutlinedSpaceTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(SquircleShapes.tile)
            .border(OutlineTileStroke, ink.copy(alpha = OutlineTileBorderAlpha), SquircleShapes.tile)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = TileSubtitleAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.End),
        )
    }
}
