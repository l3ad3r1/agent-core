package com.hermes.agent.ui.theme.alt

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How each style picks its tile accents.
 *
 * Material You is a single-colour style: one colour, from the picker or else
 * from the wallpaper, on every tile. It used to hand out four scheme roles plus
 * a fifth that was wrong in three separate ways at once — index 4 was
 * `primary.compositeOver(tertiary)`, and compositing an *opaque* colour over
 * anything returns that colour unchanged, so tile 4 was a pixel-identical
 * duplicate of tile 0; `secondary` is `primary` at reduced chroma by
 * construction, so it read as a washed-out copy rather than a second colour;
 * and `error` — a fixed red meaning "something has gone wrong" — was decoration
 * on tiles where nothing had.
 *
 * Cortex still wants five, so when the picker overrides its fixed palette those
 * five are derived from the one chosen colour, and that derivation is tested
 * here too.
 */
class TileAccentTest {

    /** WCAG 2.1 relative luminance, computed independently of the production code. */
    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun hueOf(c: Color): Double {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val d = max - min
        if (d == 0f) return 0.0
        val h = when (max) {
            c.red -> 60 * (((c.green - c.blue) / d) % 6)
            c.green -> 60 * (((c.blue - c.red) / d) + 2)
            else -> 60 * (((c.red - c.green) / d) + 4)
        }
        return ((h + 360) % 360).toDouble()
    }

    private fun hueDegreesApart(a: Color, b: Color): Double {
        val raw = Math.abs(hueOf(a) - hueOf(b))
        return minOf(raw, 360.0 - raw)
    }

    /**
     * Stand-ins for what Android derives from a wallpaper: a tone-80 primary on
     * the dark side and a tone-40 primary on the light side, which is where
     * `dynamicDarkColorScheme` / `dynamicLightColorScheme` place theirs. The
     * dark one is the interesting case — it sits near luminance 0.55, above what
     * a saturated blue can reach at any value, so it exercises the branch that
     * trades chroma for lightness.
     */
    private val dynamicDarkPrimary = Color(0xFFD0BCFF)
    private val dynamicLightPrimary = Color(0xFF6750A4)

    private fun darkScheme(primary: Color) = darkColorScheme(
        primary = primary,
        surfaceVariant = Color(0xFF49454F),
        error = Color(0xFFF2B8B5),
    )

    private fun lightScheme(primary: Color) = lightColorScheme(
        primary = primary,
        surfaceVariant = Color(0xFFE7E0EC),
        error = Color(0xFFB3261E),
    )

    private fun palette(scheme: ColorScheme) =
        (0 until 5).map { tileAccent(ThemeStyle.MATERIAL_YOU, scheme, it) }

    private val bothModes get() = listOf(darkScheme(dynamicDarkPrimary), lightScheme(dynamicLightPrimary))

    // ── Material You is one colour ────────────────────────────────────────

    @Test
    fun `material you paints every tile the same colour`() {
        for (scheme in bothModes) {
            val accents = palette(scheme)
            assertEquals(
                "Material You is a single-colour style, got $accents",
                1,
                accents.toSet().size,
            )
        }
    }

    @Test
    fun `material you takes the wallpaper primary when nothing is picked`() {
        for (scheme in bothModes) {
            for (index in 0 until 5) {
                assertEquals(scheme.primary, tileAccent(ThemeStyle.MATERIAL_YOU, scheme, index))
            }
        }
    }

    @Test
    fun `a picked colour overrides the wallpaper outright`() {
        val picked = Color(0xFF2965C9)
        val scheme = darkScheme(dynamicDarkPrimary)
        for (index in 0 until 5) {
            assertEquals(
                "the picked colour must arrive unrotated and unspread",
                picked,
                tileAccent(ThemeStyle.MATERIAL_YOU, scheme, index, accentSeed = picked),
            )
        }
    }

    @Test
    fun `no material you accent is the error colour`() {
        // error carries a meaning; it was being handed out as decoration.
        for (scheme in bothModes) {
            assertTrue(
                "error must not be handed out as decoration",
                palette(scheme).none { it == scheme.error },
            )
        }
    }

    // ── Cortex, when the picker overrides its fixed five ──────────────────

    @Test
    fun `a picked colour still spreads to five under cortex`() {
        // Cortex's grid was built around five distinct tiles, so the picker
        // overriding its palette must still yield five — unlike Material You,
        // where one colour is the whole point.
        val picked = Color(0xFF3F6FBF)
        val scheme = darkScheme(dynamicDarkPrimary)
        val accents = (0 until 5).map { tileAccent(ThemeStyle.CORTEX, scheme, it, accentSeed = picked) }
        assertEquals("expected five distinct accents, got $accents", 5, accents.toSet().size)
        for (index in accents.indices) {
            val nextIndex = (index + 1) % accents.size
            val apart = hueDegreesApart(accents[index], accents[nextIndex])
            assertTrue(
                "accents %d and %d were only %.1f degrees apart".format(index, nextIndex, apart),
                apart >= 60.0,
            )
        }
        for (index in 1 until accents.size) {
            assertNotEquals("accent $index must not repeat the anchor", accents[0], accents[index])
        }
    }

    // ── Equal visual weight, and what it buys ─────────────────────────────

    @Test
    fun `every accent carries the anchor luminance`() {
        val anchors = listOf(dynamicDarkPrimary, dynamicLightPrimary, Color(0xFF3F6FBF), Color(0xFF7DD3C0))
        for (anchor in anchors) {
            val target = luminance(anchor)
            for (step in 1 until 5) {
                val actual = luminance(anchor.hueRotateEquiluminant(step * 72f))
                assertTrue(
                    "rotating by %d degrees must hold luminance %.4f, got %.4f"
                        .format(step * 72, target, actual),
                    Math.abs(actual - target) <= 0.02 * target + 0.002,
                )
            }
        }
    }

    @Test
    fun `holding luminance carries the anchor contrast to every accent`() {
        // This is the point of equiluminance: contrast ratio is a function of
        // luminance alone, so if the chosen colour is legible on the tile
        // ground then so are all five derived from it, in both modes, with no
        // light/dark branch anywhere.
        for (scheme in bothModes) {
            val ground = scheme.surfaceVariant
            val anchorRatio = contrast(scheme.primary, ground)
            assertTrue("fixture is wrong: the anchor itself must be legible", anchorRatio >= 3.0)
            for (index in 0 until 5) {
                val accent = derivedTileAccent(scheme.primary, ground, index)
                val ratio = contrast(accent, ground)
                assertTrue(
                    "accent %d reached only %.2f:1 on the tile ground (anchor: %.2f:1)"
                        .format(index, ratio, anchorRatio),
                    ratio >= 3.0,
                )
            }
        }
    }

    @Test
    fun `a plain hue rotation would not have held weight`() {
        // Guards the choice itself: if swapping hueRotateEquiluminant back for
        // hueRotate stopped mattering, the extra machinery would be pointless.
        val target = luminance(dynamicDarkPrimary)
        val naive = (1 until 5).map { luminance(dynamicDarkPrimary.hueRotate(it * 72f)) }
        assertTrue(
            "expected the naive rotation to drift in weight; it did not, so this test is stale",
            naive.any { Math.abs(it - target) > 0.10 * target },
        )
    }

    // ── The achromatic fallback ───────────────────────────────────────────

    @Test
    fun `a grey anchor still yields five distinguishable accents`() {
        val grey = Color(0xFF9A9A9A)
        val accents = (0 until 5).map { derivedTileAccent(grey, Color.Black, it) }
        assertEquals("hue cannot separate a grey, so lightness has to", 5, accents.toSet().size)
    }

    @Test
    fun `the achromatic ramp steps away from the ground it sits on`() {
        val grey = Color(0xFF9A9A9A)
        val onDark = (0 until 5).map { derivedTileAccent(grey, Color.Black, it) }
        val onLight = (0 until 5).map { derivedTileAccent(grey, Color.White, it) }
        for (index in 1 until 5) {
            assertTrue(
                "against a dark ground each step must gain contrast; step $index did not",
                luminance(onDark[index]) > luminance(onDark[index - 1]),
            )
            assertTrue(
                "against a light ground each step must gain contrast; step $index did not",
                luminance(onLight[index]) < luminance(onLight[index - 1]),
            )
        }
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    @Test
    fun `the custom accent seed spreads by the same rule`() {
        val seed = Color(0xFF3F6FBF)
        val scheme = darkScheme(dynamicDarkPrimary)
        for (index in 0 until 5) {
            assertEquals(
                "Cortex's picker path must go through the shared derivation",
                derivedTileAccent(seed, scheme.surfaceVariant, index),
                tileAccent(ThemeStyle.CORTEX, scheme, index, accentSeed = seed),
            )
        }
    }

    @Test
    fun `cortex keeps its own hand-picked palette`() {
        val scheme = darkScheme(dynamicDarkPrimary)
        for (index in CortexAccents.all.indices) {
            assertEquals(
                "Cortex's fixed five must not be routed through the derivation",
                CortexAccents.all[index],
                tileAccent(ThemeStyle.CORTEX, scheme, index),
            )
        }
    }

    @Test
    fun `accent index wraps past the end of the palette`() {
        val scheme = darkScheme(dynamicDarkPrimary)
        assertEquals(
            tileAccent(ThemeStyle.CORTEX, scheme, 0),
            tileAccent(ThemeStyle.CORTEX, scheme, CortexAccents.all.size),
        )
        assertEquals(
            derivedTileAccent(scheme.primary, scheme.surfaceVariant, 0),
            derivedTileAccent(scheme.primary, scheme.surfaceVariant, 5),
        )
    }

    // ── The ink derived for the active-model card ─────────────────────────

    @Test
    fun `card ink flips rather than staying white on a light ground`() {
        // The home screen model card used a hardcoded Color.White: right on
        // Cortex's deep ember, unreadable on both a Material You tone-80 pastel
        // and Classic's near-white light surface.
        assertEquals(Color.White, contrastInkOn(CortexHex.Primary))
        assertEquals(Color.Black, contrastInkOn(dynamicDarkPrimary))
        assertEquals(Color.Black, contrastInkOn(Color(0xFFECECEC)))
        assertEquals(Color.White, contrastInkOn(Color.Black))
    }

    @Test
    fun `card ink always takes the better of black and white`() {
        // The flip point is where the two contrast curves cross, which is at
        // luminance 0.179, not at the 0.45 this used to use. Everything in
        // between was getting white when black was the stronger choice — a
        // ground at L=0.30 took 3.0:1 instead of the 7.0:1 available. That band
        // is exactly where mid-tone accents live, so it was not a corner case:
        // the wallpaper-derived gradient measured on device sat at L=0.21.
        val probes = (0..100).map { Color(it / 100f, it / 100f, it / 100f) } +
            listOf(
                CortexHex.Primary,
                CortexHex.Secondary,
                dynamicDarkPrimary,
                dynamicLightPrimary,
                Color(0xFFC93AE3),
                Color(0xFF299161),
            )
        for (ground in probes) {
            val chosen = contrastInkOn(ground)
            val rejected = if (chosen == Color.White) Color.Black else Color.White
            assertTrue(
                "on %s the ink picked %.2f:1 when the other option gave %.2f:1"
                    .format(ground, contrast(chosen, ground), contrast(rejected, ground)),
                contrast(chosen, ground) >= contrast(rejected, ground) - 1e-6,
            )
        }
    }

    /**
     * The whole sweep a two-stop gradient paints, not just its ends.
     *
     * Mixed component-wise on encoded sRGB, which is what the shader behind
     * `Brush.linearGradient` does — not Compose's `lerp`, which interpolates in
     * Oklab and would describe a curve the screen never shows.
     */
    private fun sweep(from: Color, to: Color) = (0..20).map { step ->
        val f = step / 20f
        Color(
            red = from.red + (to.red - from.red) * f,
            green = from.green + (to.green - from.green) * f,
            blue = from.blue + (to.blue - from.blue) * f,
        )
    }

    @Test
    fun `gradient ink is judged on the whole sweep, not just the stops`() {
        // Measured off the device: a wallpaper-derived card running magenta to
        // green. The two ends do not bound it — interpolating between opposed
        // hues passes through a desaturated middle lighter than either end, and
        // that middle is the hardest place to read. Endpoints alone would pick
        // the ink that loses there.
        val magenta = Color(0xFFC53AE0)
        val green = Color(0xFF298D5E)
        assertEquals(Color.Black, contrastInkOn(magenta))
        assertEquals(Color.Black, contrastInkOn(green))

        val full = sweep(magenta, green)
        val ink = contrastInkAcross(listOf(magenta, green))
        val other = if (ink == Color.White) Color.Black else Color.White

        // Looking only at the ends, black wins outright (5.1 and 5.1 against
        // 4.2 and 4.2) — so a rule that stopped at the stops would take black
        // and then bottom out at 4.1 in a middle it never examined. This is the
        // case the two rules disagree on, which is what makes it worth pinning.
        val endsOnly = listOf(magenta, green)
        assertTrue(
            "this pair no longer distinguishes the two rules, so the test is stale",
            endsOnly.minOf { contrast(other, it) } > endsOnly.minOf { contrast(ink, it) },
        )

        val worstChosen = full.minOf { contrast(ink, it) }
        val worstOther = full.minOf { contrast(other, it) }
        assertTrue(
            "across the whole sweep the chosen ink bottoms out at %.2f:1 where the other reaches %.2f:1"
                .format(worstChosen, worstOther),
            worstChosen > worstOther,
        )
    }

    @Test
    fun `the cortex card carries its ink for the whole sweep`() {
        // Cortex still gradients (only Material You went solid), so its card is
        // the one place the sweep rule has to hold at full AA rather than just
        // pick the better of two options.
        val stops = listOf(CortexAccents.Red, CortexAccents.Teal)
        val ink = contrastInkAcross(stops)
        assertEquals(Color.White, ink)
        val worst = sweep(stops[0], stops[1]).minOf { contrast(ink, it) }
        assertTrue("Cortex card ink bottoms out at %.2f:1".format(worst), worst >= 4.5)
    }

    @Test
    fun `a light gradient takes dark ink`() {
        // Classic's light half: the card is a near-white surface blend, and the
        // hardcoded white this replaced left the model name invisible on it.
        val classicLightCard = listOf(Color(0xFFF0F0F0), Color(0xFFECECEC))
        assertEquals(Color.Black, contrastInkAcross(classicLightCard))
    }

    @Test
    fun `derived card ink is legible on every gradient it can land on`() {
        val grounds = listOf(
            CortexHex.Primary,
            CortexAccents.Teal,
            dynamicDarkPrimary,
            dynamicLightPrimary,
            Color(0xFFECECEC),
            Color(0xFF181818),
        )
        for (ground in grounds) {
            val ratio = contrast(contrastInkOn(ground), ground)
            assertTrue("card ink reached only %.2f:1 on %s".format(ratio, ground), ratio >= 4.5)
        }
    }
}
