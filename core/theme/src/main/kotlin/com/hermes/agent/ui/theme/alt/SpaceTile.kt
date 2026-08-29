package com.hermes.agent.ui.theme.alt

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The accent palette for [ThemeStyle.CORTEX]'s tiles — five hues reimplemented
 * from the plain hex constants in MyBrain's own `Color.kt` (`Blue`/`Red`/
 * `Green`/`Orange`/`Purple`), the same "data, not code" carve-out documented
 * in AltColorSchemes.kt.
 */
object CortexAccents {
    val Blue = Color(0xFF2965C9)
    val Red = Color(0xFFD53A2F)
    val Green = Color(0xFF1E9651)
    val Orange = Color(0xFFE78A00)
    val Purple = Color(0xFF6F4CAD)
    val all = listOf(Blue, Red, Green, Orange, Purple)
}

/**
 * Picks the Nth accent for a home-grid tile, given the active alternate style.
 * [ThemeStyle.MYBRAIN] cycles the fixed five-hue [CortexAccents] palette;
 * [ThemeStyle.MATERIAL_YOU] cycles the device's own dynamic roles instead, so
 * the tiles still feel wallpaper-derived rather than hardcoded.
 */
fun tileAccent(style: ThemeStyle, colorScheme: ColorScheme, index: Int): Color =
    when (style) {
        ThemeStyle.CORTEX -> CortexAccents.all[index % CortexAccents.all.size]
        ThemeStyle.MATERIAL_YOU -> listOf(
            colorScheme.primary,
            colorScheme.tertiary,
            colorScheme.secondary,
            colorScheme.error,
            colorScheme.primary.compositeOver(colorScheme.tertiary),
        )[index % 5]
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

/**
 * A squircle "space" tile: title + subtitle up top, a big tinted icon resting
 * bottom-right, an [accent]-tinted glow behind it — the shape MyBrain's own
 * home "Spaces" grid uses for Notes/Tasks/Diary/Bookmarks/Calendar/Assistant,
 * reimplemented here (own code, own icon set — Material Icons, not their
 * Flaticon-attributed artwork) so [ThemeStyle.CORTEX] and
 * [ThemeStyle.MATERIAL_YOU] can offer the same look for any app/module tile.
 */
@Composable
fun SpaceTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(SquircleShapes.tile)
            .accentGlow(accent = accent, base = scheme.surfaceVariant)
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
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .size(30.dp)
                .align(Alignment.End),
        )
    }
}
