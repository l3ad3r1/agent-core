package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import android.content.Context
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.product.ProductIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmManagerLifecycleTest {

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val engine = mockk<InferenceEngine>(relaxed = true)
    private val downloadCoordinator = mockk<LocalModelDownloadCoordinator>().also {
        every { it.isDownloading } returns MutableStateFlow(false)
        every { it.progress } returns MutableStateFlow(0f)
        every { it.error } returns MutableStateFlow("")
    }
    private val manager = LocalLlmManager(
        context = mockk<Context>(relaxed = true),
        settingsRepository = settingsRepository,
        downloadCoordinator = downloadCoordinator,
        engine = engine,
        productIdentity = ProductIdentity("Hermes", "hermes_notify"),
    )

    @Test
    fun `clearing a custom model unloads before persisting the empty uri`() = runTest {
        manager.setLocalModelUri("")

        coVerifyOrder {
            engine.cleanUp()
            settingsRepository.setLocalModelUri("")
        }
    }

    @Test
    fun `selecting another model unloads before persisting the model id`() = runTest {
        manager.setSelectedModelId("model-b")

        coVerifyOrder {
            engine.cleanUp()
            settingsRepository.setSelectedModelId("model-b")
        }
    }

    @Test
    fun `changing the download directory unloads before persisting the path`() = runTest {
        manager.setModelDownloadDir("/models")

        coVerifyOrder {
            engine.cleanUp()
            settingsRepository.setModelDownloadDir("/models")
        }
    }

    @Test
    fun `unload failure keeps the selection and tells the user how to retry`() = runTest {
        coEvery { engine.cleanUp() } throws IllegalStateException("native engine busy")
        every { downloadCoordinator.reportError(any()) } returns Unit

        manager.setLocalModelUri("")

        coVerify(exactly = 0) { settingsRepository.setLocalModelUri(any()) }
        verify {
            downloadCoordinator.reportError(match { message ->
                message.contains("try again", ignoreCase = true) &&
                    message.contains("native engine busy")
            })
        }
    }

    @Test
    fun `blank system prompt uses product composition`() = runTest {
        every { engine.state } returns MutableStateFlow(State.ModelReady)
        every { engine.sendUserPrompt("hello", any()) } returns flowOf("hi")

        manager.generateResponse(systemPrompt = "", userPrompt = "hello").toList()

        coVerify { engine.setSystemPrompt("You are Hermes, a helpful on-device assistant.") }
    }

    @Test
    fun `model load is not attempted when model is not downloaded`() = runTest {
        every { engine.state } returns MutableStateFlow(State.Initialized)
        coEvery { settingsRepository.current() } returns UserSettings(
            selectedModelId = "llama-3.2-1b-q4km",
            modelDownloadDir = "/non_existent_folder_xyz",
        )

        val flow = manager.generateResponse(systemPrompt = "sys", userPrompt = "test")
        var failed = false
        try {
            flow.toList()
        } catch (e: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        coVerify(exactly = 0) { engine.loadModel(any()) }
    }
}
