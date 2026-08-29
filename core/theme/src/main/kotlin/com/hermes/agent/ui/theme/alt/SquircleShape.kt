package com.hermes.agent.ui.theme.alt

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A "squircle" — a continuous-curvature rounded rect, built the way design
 * tools like Figma do it: each corner is a pair of cubic Béziers rather than
 * one circular arc, so the curve keeps travelling along the straight edge for
 * a while before it turns. That's what makes a squircle read as softer/rounder
 * than a [androidx.compose.foundation.shape.RoundedCornerShape] of the same
 * radius, and it's the corner style [ThemeStyle.MYBRAIN] borrows from the
 * MyBrain app's own card shape (github.com/mhss1/MyBrain, GPL-3.0) — MyBrain
 * gets this look from a small third-party shape library; this is an
 * independent implementation of the same general "corner smoothing" idea
 * (public technique, not this repo's code), not a port of their library.
 *
 * @param radius corner radius at zero smoothing.
 * @param smoothing 0f (a plain rounded corner, Bézier-approximated) to 1f
 *   (control handles stretched furthest along the edge — the flattest,
 *   most "superellipse"-like corner).
 */
class SquircleShape(
    private val radius: Dp = 28.dp,
    private val smoothing: Float = 0.6f,
) : CornerBasedShape(
    topStart = CornerSize(radius),
    topEnd = CornerSize(radius),
    bottomEnd = CornerSize(radius),
    bottomStart = CornerSize(radius),
) {
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = this

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val w = size.width
        val h = size.height
        val maxCorner = minOf(w, h) / 2f
        val tl = topStart.coerceAtMost(maxCorner)
        val tr = topEnd.coerceAtMost(maxCorner)
        val br = bottomEnd.coerceAtMost(maxCorner)
        val bl = bottomStart.coerceAtMost(maxCorner)

        // Kappa (~0.5523) is the standard magic constant for a Bézier
        // approximation of a quarter circle; stretching the handle further
        // (up to 1.0x the radius) flattens the arc into a squircle.
        val s = smoothing.coerceIn(0f, 1f)
        fun handle(r: Float) = r * (0.5523f + s * 0.4477f)

        val path = Path().apply {
            moveTo(tl, 0f)
            lineTo(w - tr, 0f)
            cubicTo(w - tr + handle(tr), 0f, w, tr - handle(tr), w, tr)
            lineTo(w, h - br)
            cubicTo(w, h - br + handle(br), w - br + handle(br), h, w - br, h)
            lineTo(bl, h)
            cubicTo(bl - handle(bl), h, 0f, h - bl + handle(bl), 0f, h - bl)
            lineTo(0f, tl)
            cubicTo(0f, tl - handle(tl), tl - handle(tl), 0f, tl, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Squircle corner radii mirroring Material's default Shapes scale. */
object SquircleShapes {
    val extraSmall = SquircleShape(6.dp)
    val small = SquircleShape(10.dp)
    val medium = SquircleShape(16.dp)
    val large = SquircleShape(24.dp)
    val extraLarge = SquircleShape(32.dp)

    /** The big, ~48dp radius MyBrain uses for its home "Spaces" tiles. */
    val tile = SquircleShape(40.dp)
}
