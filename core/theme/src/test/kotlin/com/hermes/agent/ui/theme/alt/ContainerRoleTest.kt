package com.hermes.agent.ui.theme.alt

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Container roles have to come from the palette, not from Material's baseline.
 *
 * `darkColorScheme()` and `lightColorScheme()` fill every role the caller does
 * not name with Material's own defaults, which are purple. A scheme that sets
 * `primary` and leaves the container roles alone therefore hands out a purple
 * `primaryContainer` — which is exactly what happened: the theme picker draws
 * its selected row in `primaryContainer`, so an ember theme rendered a purple
 * chip inside its own colour settings, and Cortex did the same.
 *
 * Nothing here is about taste. It is about a scheme not quietly serving colours
 * from a palette it replaced.
 */
class ContainerRoleTest {

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

    private fun saturationOf(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        return if (max == 0f) 0f else (max - minOf(c.red, c.green, c.blue)) / max
    }

    /** Every scheme a user can actually end up looking at. */
    private val schemes: List<Pair<String, ColorScheme>> = buildList {
        add("Cortex dark" to CortexDark)
        add("Cortex light" to CortexLight)
        for (seed in listOf(Color(0xFFC1440E), Color(0xFF2965C9), Color(0xFF1E9651), Color(0xFFF0B429))) {
            add("seed $seed dark" to accentColorScheme(seed, darkTheme = true))
            add("seed $seed light" to accentColorScheme(seed, darkTheme = false))
        }
    }

    private fun ColorScheme.containerPairs() = listOf(
        Triple("primary", primaryContainer, onPrimaryContainer),
        Triple("secondary", secondaryContainer, onSecondaryContainer),
        Triple("tertiary", tertiaryContainer, onTertiaryContainer),
        Triple("error", errorContainer, onErrorContainer),
    )

    /**
     * Material's baseline container tones. If a scheme still reports one of
     * these, it never set that role and is serving the default palette.
     */
    private val baselineContainers = listOf(
        Color(0xFF4F378B), Color(0xFFEADDFF), // primaryContainer dark / light
        Color(0xFF4A4458), Color(0xFFE8DEF8), // secondaryContainer
        Color(0xFF633B48), Color(0xFFFFD8E4), // tertiaryContainer
    )

    @Test
    fun `no container role is left at Material's baseline`() {
        for ((name, scheme) in schemes) {
            for ((role, container, _) in scheme.containerPairs()) {
                assertTrue(
                    "$name: ${role}Container is still Material's default $container",
                    baselineContainers.none { it == container },
                )
            }
        }
    }

    @Test
    fun `every container carries its own accent's hue`() {
        // The container is mostly surface, so it is allowed to be nearly
        // neutral — but whatever chroma it does have must be the accent's, not
        // some other palette's.
        for ((name, scheme) in schemes) {
            val accents = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error)
            for ((index, pair) in scheme.containerPairs().withIndex()) {
                val (role, container, _) = pair
                if (saturationOf(container) < 0.04f) continue
                val apart = hueDegreesApart(container, accents[index])
                assertTrue(
                    "$name: ${role}Container sits %.0f degrees off its accent".format(apart),
                    apart <= 20.0,
                )
            }
        }
    }

    @Test
    fun `every container label is legible on its container`() {
        for ((name, scheme) in schemes) {
            for ((role, container, onContainer) in scheme.containerPairs()) {
                val ratio = contrast(onContainer, container)
                assertTrue(
                    "$name: on${role}Container reached only %.2f:1".format(ratio),
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `a container stays distinct from the surface behind it`() {
        // A container that blends entirely into the surface stops reading as a
        // selected state at all, which is what the theme picker uses it for.
        for ((name, scheme) in schemes) {
            for ((role, container, _) in scheme.containerPairs()) {
                val ratio = contrast(container, scheme.surface)
                assertTrue(
                    "$name: ${role}Container is indistinguishable from the surface (%.2f:1)".format(ratio),
                    ratio >= 1.1,
                )
            }
        }
    }
}
