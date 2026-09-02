package com.hermes.agent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresencePlaceTest {

    private val home = PresencePlace("Home", 17.4485, 78.3908, radiusMeters = 150)
    private val work = PresencePlace("Work", 17.4600, 78.4000, radiusMeters = 200)

    @Test
    fun `a fix inside a place resolves to its label`() {
        assertEquals("Home", PresencePlace.resolveLabel(listOf(home, work), 17.4486, 78.3909))
    }

    @Test
    fun `a fix outside every place resolves to null`() {
        assertNull(PresencePlace.resolveLabel(listOf(home, work), 17.5200, 78.5000))
    }

    @Test
    fun `the nearest place wins when radii overlap`() {
        // A wide place centred ~1km away still covers Home's centre, but Home is nearer.
        val wide = PresencePlace("Neighbourhood", 17.4575, 78.3908, radiusMeters = 5_000)
        assertEquals("Home", PresencePlace.resolveLabel(listOf(wide, home), 17.4485, 78.3908))
        // …and standing inside the wide place but outside Home resolves to the wide one.
        assertEquals("Neighbourhood", PresencePlace.resolveLabel(listOf(wide, home), 17.4575, 78.3908))
    }

    @Test
    fun `distance is roughly correct at geofence scale`() {
        // ~0.001 degrees of latitude is ~111 m.
        val d = PresencePlace.distanceMeters(17.4485, 78.3908, 17.4495, 78.3908)
        assertTrue("expected ~111m, got $d", d in 100.0..125.0)
    }

    @Test
    fun `normalize trims labels, clamps radius, drops blanks and dedupes`() {
        val input = listOf(
            PresencePlace("  Home  ", 1.0, 2.0, radiusMeters = 5),
            PresencePlace("home", 3.0, 4.0),
            PresencePlace("   ", 5.0, 6.0),
            PresencePlace("Gym", 7.0, 8.0, radiusMeters = 99_999),
        )
        val out = PresencePlace.normalize(input)
        assertEquals(2, out.size)
        assertEquals("Home", out[0].label)
        assertEquals(25, out[0].radiusMeters)
        assertEquals(5_000, out[1].radiusMeters)
    }

    @Test
    fun `normalize caps the list`() {
        val many = (1..40).map { PresencePlace("Place $it", it.toDouble(), it.toDouble()) }
        assertEquals(PresencePlace.MAX_PLACES, PresencePlace.normalize(many).size)
    }
}
