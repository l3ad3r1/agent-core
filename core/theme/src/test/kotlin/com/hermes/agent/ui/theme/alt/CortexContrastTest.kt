package com.hermes.agent.ui.theme.alt

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast floor for the Cortex palette.
 *
 * Cortex puts `Color.White` on top of both `primary` and `secondary`, so those
 * two hues are not a free aesthetic choice — they have to carry white text. The
 * palette this replaced did not: its cyan `#28B0DF` reached only 2.51:1 against
 * white, below even the 3.0 WCAG minimum for UI components, and nothing caught
 * it because nothing was checking. This test is that check.
 */
class CortexContrastTest {

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Hue of [c] in degrees. */
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

    /** Shortest distance between two hues on the colour wheel, in degrees. */
    private fun hueDegreesApart(a: Color, b: Color): Double {
        fun hue(c: Color): Double {
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
        val raw = Math.abs(hue(a) - hue(b))
        return minOf(raw, 360.0 - raw)
    }

    @Test
    fun `primary carries white text`() {
        val ratio = contrast(CortexHex.Primary, Color.White)
        assertTrue(
            "Cortex primary must reach 4.5:1 against the white it is drawn with, was %.2f".format(ratio),
            ratio >= 4.5,
        )
    }

    @Test
    fun `secondary carries white text`() {
        val ratio = contrast(CortexHex.Secondary, Color.White)
        assertTrue(
            "Cortex secondary must reach 4.5:1 against the white it is drawn with, was %.2f".format(ratio),
            ratio >= 4.5,
        )
    }

    @Test
    fun `both accents stay visible on the dark surface`() {
        // Dark mode paints these on the near-black neutral. An accent that
        // vanishes into the surface is unusable even if white text sits on it.
        listOf("primary" to CortexHex.Primary, "secondary" to CortexHex.Secondary).forEach { (name, color) ->
            val ratio = contrast(color, CortexHex.DarkNeutral)
            assertTrue(
                "Cortex %s must reach 3.0:1 against the dark surface, was %.2f".format(name, ratio),
                ratio >= 3.0,
            )
        }
    }

    @Test
    fun `both accents stay visible on the light background`() {
        listOf("primary" to CortexHex.Primary, "secondary" to CortexHex.Secondary).forEach { (name, color) ->
            val ratio = contrast(color, CortexHex.LightBackground)
            assertTrue(
                "Cortex %s must reach 3.0:1 against the light background, was %.2f".format(name, ratio),
                ratio >= 3.0,
            )
        }
    }

    @Test
    fun `the two accents are separated by hue, not brightness`() {
        // primary and secondary sit side by side in the home screen's gradient
        // and in the tab accents, so they must be tellable apart. Contrast ratio
        // is the wrong measure for that: the two WCAG rules above (4.5:1 on
        // white, 3:1 on the dark surface) pin both accents into a luminance
        // window of about 0.12..0.18, inside which the largest possible ratio
        // between any two conforming colours is ~1.38:1. Brightness cannot do
        // the separating, so hue has to.
        val separation = hueDegreesApart(CortexHex.Primary, CortexHex.Secondary)
        assertTrue(
            "Cortex accents should be well apart on the wheel, were %.0f degrees".format(separation),
            separation >= 90.0,
        )
    }

    @Test
    fun `the accents keep some luminance separation too`() {
        // Hue alone is not enough for a viewer with colour vision deficiency.
        // The window above caps this at ~1.38, so this only asks for a real
        // difference rather than the two accents being the same brightness.
        val ratio = contrast(CortexHex.Primary, CortexHex.Secondary)
        assertTrue(
            "Cortex accents should differ in luminance as well as hue, ratio was %.2f".format(ratio),
            ratio >= 1.1,
        )
    }

    @Test
    fun `error stays distinct from the primary accent`() {
        // A warm primary risks reading as an error state. They must not be the
        // same colour to a glance.
        assertTrue(
            "Cortex primary must not be the error colour",
            CortexHex.Primary != CortexHex.Error,
        )
    }

    @Test
    fun `every tile accent is legible on both grounds`() {
        // A tile's icon is drawn in its accent colour directly over the
        // near-black surface in dark mode and the near-white background in
        // light mode, so each hue has to clear the 3:1 floor for a graphical
        // object on BOTH. The palette this replaced did not: its orange reached
        // only 2.57:1 on light and its purple 2.93:1 on dark.
        CortexAccents.all.forEachIndexed { index, accent ->
            val onDark = contrast(accent, CortexHex.DarkNeutral)
            val onLight = contrast(accent, CortexHex.LightBackground)
            assertTrue(
                "tile accent %d must reach 3:1 on the dark surface, was %.2f".format(index, onDark),
                onDark >= 3.0,
            )
            assertTrue(
                "tile accent %d must reach 3:1 on the light background, was %.2f".format(index, onLight),
                onLight >= 3.0,
            )
        }
    }

    @Test
    fun `tile accents are all different from one another`() {
        assertTrue(
            "the five tile accents must be distinct",
            CortexAccents.all.toSet().size == CortexAccents.all.size,
        )
    }

    @Test
    fun `no tile accent sits in the purple arc`() {
        // Purple was explicitly dropped from this palette; this stops it
        // creeping back in via a later tweak.
        CortexAccents.all.forEachIndexed { index, accent ->
            val h = hueOf(accent)
            assertTrue(
                "tile accent %d should not be purple, hue was %.0f".format(index, h),
                h < 250.0 || h > 320.0,
            )
        }
    }

    @Test
    fun `the tile palette keeps the hues it was meant to keep`() {
        // Only purple was up for replacement. Red, green and blue are untouched,
        // and orange keeps its hue to within a tenth of a degree -- it was
        // darkened one step for legibility on the light background, not
        // recoloured. This test is what stops a future tweak from quietly
        // restyling hues the owner asked to keep.
        assertTrue("red should be unchanged", CortexAccents.Red == Color(0xFFD53A2F))
        assertTrue("green should be unchanged", CortexAccents.Green == Color(0xFF1E9651))
        assertTrue("blue should be unchanged", CortexAccents.Blue == Color(0xFF2965C9))
        val orangeDrift = Math.abs(hueOf(CortexAccents.Orange) - 35.8)
        assertTrue(
            "orange should keep its hue, drifted %.1f degrees".format(orangeDrift),
            orangeDrift < 1.0,
        )
    }

    @Test
    fun `the active-model gradient sweeps warm to cool`() {
        // HomeScreen blends tile indices 1 and 4 for that card. If those two
        // ever land on neighbouring hues the gradient collapses into a flat band.
        val separation = hueDegreesApart(CortexAccents.all[1], CortexAccents.all[4])
        assertTrue(
            "gradient endpoints should be far apart on the wheel, were %.0f degrees".format(separation),
            separation >= 90.0,
        )
    }
}
