package com.hermes.agent.data.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.local.dao.PresenceLogDao
import com.hermes.agent.data.local.entity.PresenceLogEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PresenceToolTest {

    private lateinit var context: Context
    private lateinit var dao: PresenceLogDao
    private lateinit var tool: PresenceTool

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dao = mockk(relaxed = true)
        tool = PresenceTool(context, dao)
    }

    @Test
    fun descriptor_hasCorrectMetadata() {
        assertEquals("presence", tool.descriptor.name)
        assertEquals("device", tool.descriptor.category)
        assertTrue(tool.descriptor.capabilities.contains("presence"))
        assertFalse(tool.descriptor.requiresConfirmation)
    }

    @Test
    fun execute_returnsCompactObjectWithoutCoordinatesOrRawTimestamps() = runTest {
        val dummyEntity = PresenceLogEntity(
            id = "test_1",
            timestamp = System.currentTimeMillis() - 5 * 60 * 1000L,
            locationName = "Home Office",
            batteryLevel = 85,
            isCharging = true,
            networkType = "WIFI",
            activity = "STILL",
            screenOn = true,
            contextSummary = "Battery: 85% (Charging) | Network: WIFI | Screen: On",
        )
        coEvery { dao.getLatest() } returns dummyEntity

        val result = tool.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.output.contains("place"))
        assertTrue(result.output.contains("Home Office"))
        assertTrue(result.output.contains("motion"))
        assertTrue(result.output.contains("power"))
        assertTrue(result.output.contains("idle_minutes"))

        // Must NOT contain coordinate or raw timestamp fields
        assertFalse(result.output.contains("latitude"))
        assertFalse(result.output.contains("longitude"))
        assertFalse(result.output.contains("timestamp"))
        assertFalse(result.output.contains("contextSummary"))
    }
}
