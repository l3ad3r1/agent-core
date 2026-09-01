package com.hermes.agent.data.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraCaptureToolTest {

    private lateinit var context: Context
    private lateinit var tool: CameraCaptureTool

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tool = CameraCaptureTool(context)
    }

    @Test
    fun descriptor_hasCorrectMetadata() {
        assertEquals("take_photo", tool.descriptor.name)
        assertEquals("vision", tool.descriptor.category)
        assertTrue(tool.descriptor.capabilities.contains("camera"))
        assertTrue(tool.descriptor.capabilities.contains("vision"))

        val facingParam = tool.descriptor.parameters.firstOrNull { it.name == "facing" }
        assertNotNull(facingParam)
        assertFalse(facingParam!!.required)
        assertEquals(listOf("back", "front"), facingParam.enumValues)

        val qualityParam = tool.descriptor.parameters.firstOrNull { it.name == "quality" }
        assertNotNull(qualityParam)

        val flashParam = tool.descriptor.parameters.firstOrNull { it.name == "flash" }
        assertNotNull(flashParam)
        assertEquals(listOf("off", "on", "auto"), flashParam!!.enumValues)
    }

    @Test
    fun executeInRobolectric_handlesCameraSafely() = runTest {
        // In Robolectric headless test runner, CameraManager may have 0 cameras or throw
        val result = tool.execute(mapOf("facing" to JsonPrimitive("back")))
        assertNotNull(result)
        // Either captures or fails cleanly with descriptive error without uncaught crash
        assertTrue(result.success || result.errorMessage != null)
    }
}
