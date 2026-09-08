package com.hermes.agent.data.llm

import android.content.Context
import com.arm.aichat.InferenceEngine
import com.hermes.agent.domain.product.ProductIdentity
import com.hermes.agent.domain.settings.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The tool caller has its own model slot, so unloading the chat engine no longer
 * unloads it. That is the point — a tool turn stops evicting the conversation —
 * but it means anything invalidating *both* models has to say so explicitly, and
 * nothing fails loudly if it does not: the tool caller would just go on serving a
 * model loaded from a folder the user has moved.
 */
class ToolCallerSlotCleanupTest {

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val engine = mockk<InferenceEngine>(relaxed = true)
    private val toolCallerEngine = mockk<InferenceEngine>(relaxed = true)
    private val downloadCoordinator = mockk<LocalModelDownloadCoordinator>().also {
        every { it.isDownloading } returns MutableStateFlow(false)
        every { it.progress } returns MutableStateFlow(0f)
        every { it.error } returns MutableStateFlow("")
    }

    private var builtToolCallerEngines = 0

    private val manager = LocalLlmManager(
        context = mockk<Context>(relaxed = true),
        settingsRepository = settingsRepository,
        downloadCoordinator = downloadCoordinator,
        engine = engine,
        productIdentity = ProductIdentity("Hermes", "hermes_notify"),
    ).also {
        it.toolCallerEngineFactory = {
            builtToolCallerEngines++
            toolCallerEngine
        }
    }

    @Test
    fun `moving the model folder unloads the tool caller too`() = runTest {
        // Build the slot, exactly as the first tool turn on a device does.
        manager.toolCallerEngine()

        manager.setModelDownloadDir("/sdcard/Elsewhere")

        coVerify { engine.cleanUp() }
        coVerify { toolCallerEngine.cleanUp() }
        coVerify { settingsRepository.setModelDownloadDir("/sdcard/Elsewhere") }
    }

    @Test
    fun `picking a chat model leaves the tool caller resident`() = runTest {
        manager.toolCallerEngine()

        manager.setSelectedModelId("qwen-1.5b")

        coVerify { engine.cleanUp() }
        // Its model did not change, and unloading it would cost a reload on the
        // next tool turn for nothing.
        coVerify(exactly = 0) { toolCallerEngine.cleanUp() }
    }

    @Test
    fun `a device that never used the tool caller does not build one to unload it`() = runTest {
        manager.setModelDownloadDir("/sdcard/Elsewhere")

        coVerify { engine.cleanUp() }
        // Touching the lazy would construct a second engine — and on-device that
        // means loading the native library for a slot this user never asked for.
        assertFalse("a tool-caller engine was built just to unload it", builtToolCallerEngines > 0)
    }
}
