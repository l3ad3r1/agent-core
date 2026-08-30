package com.hermes.agent.data.tools

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.llm.HybridLlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VisionAnalyzeToolTest {

    private lateinit var context: Context
    private lateinit var router: HybridLlmRouter
    private lateinit var mockProvider: LlmProvider
    private lateinit var server: MockWebServer
    private lateinit var tool: VisionAnalyzeTool
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        router = mockk()
        mockProvider = mockk()
        server = MockWebServer()
        server.start()

        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val pngBytes = stream.toByteArray()

        tempFile = File.createTempFile("test_image", ".png", context.cacheDir)
        tempFile.writeBytes(pngBytes)

        tool = VisionAnalyzeTool(
            context = context,
            router = router,
            okHttpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    @Test
    fun `descriptor has correct name and capability`() {
        assertEquals("vision_analyze", tool.descriptor.name)
        assertTrue("vision" in tool.descriptor.capabilities)
        assertTrue(tool.descriptor.parameters.any { it.name == "image_path" && it.required })
    }

    @Test
    fun `missing image parameter returns error`() = runTest {
        val result = tool.execute(emptyMap())
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Missing required parameter") == true)
    }

    @Test
    fun `analyze local file with ready router completes successfully`() = runTest {
        coEvery { router.route(any(), any()) } returns RoutingDecision.Ready(
            provider = mockProvider,
            reason = "Vision cloud model selected",
        )
        coEvery { mockProvider.complete(any()) } returns LlmResponse(
            content = "This is a 100x100 test image with solid pixels.",
            tokensUsed = 25,
            model = "mock-vision-model",
        )

        val result = tool.execute(
            mapOf(
                "image_path" to JsonPrimitive(tempFile.absolutePath),
                "prompt" to JsonPrimitive("What is in this image?"),
            ),
        )

        assertTrue(result.success)
        assertTrue("This is a 100x100 test image" in result.output)
    }

    @Test
    fun `analyze image when router unavailable returns native vision fallback`() = runTest {
        coEvery { router.route(any(), any()) } returns RoutingDecision.Unavailable(
            provider = mockProvider,
            reason = "No cloud vision provider configured",
        )

        val result = tool.execute(
            mapOf(
                "image_path" to JsonPrimitive(tempFile.absolutePath),
            ),
        )

        assertTrue(result.success)
        assertTrue("ready_for_native_vision" in result.output)
        assertTrue("data:image/png;base64," in result.output)
    }

    @Test
    fun `analyze remote URL fetches and processes image`() = runTest {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val pngBytes = stream.toByteArray()

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(okio.Buffer().write(pngBytes)),
        )

        val imageUrl = server.url("/sample.png").toString()

        coEvery { router.route(any(), any()) } returns RoutingDecision.Ready(
            provider = mockProvider,
            reason = "Vision model ready",
        )
        coEvery { mockProvider.complete(any()) } returns LlmResponse(
            content = "Remote image analyzed successfully.",
            tokensUsed = 20,
            model = "mock-vision-model",
        )

        val result = tool.execute(
            mapOf(
                "image_path" to JsonPrimitive(imageUrl),
            ),
        )

        assertTrue(result.success)
        assertTrue("Remote image analyzed successfully" in result.output)
    }

    @Test
    fun `nonexistent local file returns error`() = runTest {
        val result = tool.execute(
            mapOf(
                "image_path" to JsonPrimitive("/path/to/nonexistent/file.png"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Vision analysis failed") == true)
    }
}
