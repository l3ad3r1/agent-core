package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import java.io.IOException
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HybridLlmRouterTest {

    private lateinit var cloud: CloudLlmProvider
    private lateinit var specialised: CloudLlmProvider
    private lateinit var local: LocalLlmProvider
    private lateinit var toolCaller: ToolCallerLlmProvider
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        cloud = mockk(relaxed = true)
        specialised = mockk(relaxed = true)
        local = mockk(relaxed = true)
        toolCaller = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        every { cloud.name } returns "Primary cloud"
        every { cloud.model } returns "primary-model"
        every { specialised.name } returns "Specialist cloud"
        every { specialised.model } returns "specialist-model"
        every { local.name } returns "Local"
        every { local.model } returns "local-model"
        every { toolCaller.name } returns "On-device tool caller"
        every { toolCaller.model } returns "tool-caller-model"
        // Off unless a test turns it on, so every existing expectation about
        // which provider leads the chain still holds.
        coEvery { toolCaller.isAvailable() } returns false
    }

    @Test
    fun `cloudOnly keeps the on-device model out of the chain`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { local.isAvailable() } returns true
        val messages = listOf(LlmMessage("user", "refine this"))
        coEvery { cloud.complete(messages) } throws IOException("network down")

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(messages, RoutingContext(cloudOnly = true))

        assertTrue("expected Ready decision", decision is RoutingDecision.Ready)
        // Without the local fallback the only cloud provider fails outright,
        // rather than quietly handing a refinement to the 1B on-device model.
        val provider = (decision as RoutingDecision.Ready).provider
        var threw = false
        try {
            provider.complete(messages)
        } catch (e: IOException) {
            threw = true
        }
        assertTrue("cloudOnly must not fall back to local", threw)
        coVerify(exactly = 0) { local.complete(any()) }
    }

    @Test
    fun `cloudOnly reports unavailable when no cloud provider is configured`() = runTest {
        coEvery { settings.current() } returns UserSettings(cloudEnabled = false)
        coEvery { cloud.isAvailable() } returns false
        coEvery { specialised.isAvailable() } returns false
        coEvery { local.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(
            listOf(LlmMessage("user", "refine this")),
            RoutingContext(cloudOnly = true),
        )

        // A local model being ready must not turn into a Ready decision here.
        assertTrue("expected Unavailable", decision is RoutingDecision.Unavailable)
        assertTrue(
            (decision as RoutingDecision.Unavailable).reason.contains("Cloud", ignoreCase = true),
        )
    }

    @Test
    fun `routes through cloud-first failover when local is also available`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { local.isAvailable() } returns true
        val messages = listOf(LlmMessage("user", "hello"))
        val localResponse = LlmResponse("offline answer", 4, "local-model")
        coEvery { cloud.complete(messages) } throws IOException("network down")
        coEvery { local.complete(messages) } returns localResponse

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(
            messages,
            RoutingContext(requiresReliableToolCalls = true),
        )

        assertTrue("expected Ready decision", decision is RoutingDecision.Ready)
        val provider = (decision as RoutingDecision.Ready).provider
        assertTrue(provider is RoutedProviderChain)
        assertEquals(localResponse, provider.complete(messages))
        coVerify(exactly = 1) { cloud.complete(messages) }
        coVerify(exactly = 1) { local.complete(messages) }
    }

    @Test
    fun `routes directly to local when quick alias is specified and local is available`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { local.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(
            listOf(LlmMessage("user", "Analyze and compare these options")),
            RoutingContext(requiredAlias = "quick"),
        )

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals(local, (decision as RoutingDecision.Ready).provider)
    }

    @Test
    fun `routes complex tasks to the specialist model`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "Please analyze and compare these options")))

        assertTrue(decision is RoutingDecision.Ready)
        val routed = (decision as RoutingDecision.Ready).provider
        assertTrue(routed is RoutedProviderChain)
        assertEquals(specialised.model, routed.model)
    }

    @Test
    fun `routes simple tasks to the primary model`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "hi")))

        assertTrue(decision is RoutingDecision.Ready)
        val routed = (decision as RoutingDecision.Ready).provider
        assertTrue(routed is RoutedProviderChain)
        assertEquals(cloud.model, routed.model)
    }

    @Test
    fun `keeps local model as final fallback even for simple tasks`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
            selectedModelId = "llama-3.2-1b-q4km",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns true
        coEvery { local.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "hi")))

        assertTrue(decision is RoutingDecision.Ready)
        val routed = (decision as RoutingDecision.Ready).provider
        assertTrue(routed is RoutedProviderChain)
        assertEquals(cloud.model, routed.model)
    }

    @Test
    fun `routes tool-dependent phone tasks away from the local model`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
            selectedModelId = "llama-3.2-1b-q4km",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns true
        coEvery { local.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(
            listOf(LlmMessage("user", "Create a calendar event today at 3 PM")),
            RoutingContext(requiresReliableToolCalls = true),
        )

        assertTrue(decision is RoutingDecision.Ready)
        val routed = (decision as RoutingDecision.Ready).provider
        assertTrue(routed is RoutedProviderChain)
        assertEquals(cloud.model, routed.model)
    }

    @Test
    fun `routes through an imported provider profile`() = runTest {
        val profile = CloudProviderProfile(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "moonshotai/kimi-k2-instruct",
            apiKey = "test-key",
            quality = 0.88,
            cost = 0.10,
            latency = 0.98,
            toolReliability = 0.94,
        )
        val importedProvider = mockk<CloudLlmProvider>(relaxed = true)
        every { importedProvider.name } returns "Groq"
        every { importedProvider.model } returns profile.model
        every { importedProvider.isOnDevice } returns false
        coEvery { importedProvider.isAvailable() } returns true
        val factory = mockk<ProfileCloudProviderFactory>()
        every { factory.create(profile) } returns importedProvider
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudProviderProfiles = listOf(profile),
        )
        coEvery { cloud.isAvailable() } returns false
        coEvery { specialised.isAvailable() } returns false
        coEvery { local.isAvailable() } returns false

        val router = HybridLlmRouter(
            cloud,
            specialised,
            local,
            toolCaller,
            settings,
            factory,
            QualityAwareLlmRoutingPolicy(),
        )
        val decision = router.route(
            listOf(LlmMessage("user", "Create a calendar event")),
            RoutingContext(requiresReliableToolCalls = true),
        )

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals(importedProvider, (decision as RoutingDecision.Ready).provider)
    }

    @Test
    fun `falls back to primary for a complex task when specialist is unavailable`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { specialised.isAvailable() } returns false

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "Please analyze and compare these options")))

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals(cloud, (decision as RoutingDecision.Ready).provider)
    }

    @Test
    fun `returns unavailable when cloud disabled and local unavailable`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = false,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns false
        coEvery { local.isAvailable() } returns false

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "hello")))

        assertTrue("expected Unavailable", decision is RoutingDecision.Unavailable)
    }

    @Test
    fun `routes to local when cloud disabled but local available`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = false,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns false
        coEvery { local.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "hello")))

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals(local, (decision as RoutingDecision.Ready).provider)
    }

    @Test
    fun `returns unavailable when cloud enabled but no API key and local unavailable`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "",
        )
        coEvery { cloud.isAvailable() } returns false
        coEvery { local.isAvailable() } returns false

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "anything")))

        assertTrue(decision is RoutingDecision.Unavailable)
        val unavailable = decision as RoutingDecision.Unavailable
        assertTrue(
            "reason should mention API key",
            unavailable.reason.contains("API key", ignoreCase = true),
        )
    }

    @Test
    fun `routes to local when cloud enabled but no API key and local available`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "",
        )
        coEvery { cloud.isAvailable() } returns false
        coEvery { local.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val decision = router.route(listOf(LlmMessage("user", "anything")))

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals(local, (decision as RoutingDecision.Ready).provider)
    }

    @Test
    fun `returns deterministic decisions across calls`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
        )
        coEvery { cloud.isAvailable() } returns true

        val router = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
        val d1 = router.route(listOf(LlmMessage("user", "hi")))
        val d2 = router.route(listOf(LlmMessage("user", "hi")))
        assertEquals(d1::class, d2::class)
    }

    @Test
    fun `complexity classifier flags trigger words`() {
        assertEquals(
            RequestComplexity.COMPLEX,
            ComplexityClassifier.classify("Please summarize this article"),
        )
        assertEquals(
            RequestComplexity.COMPLEX,
            ComplexityClassifier.classify("compare Kotlin and Dart for Android development"),
        )
        assertEquals(
            RequestComplexity.SIMPLE,
            ComplexityClassifier.classify("hello"),
        )
    }

    @Test
    fun `complexity classifier flags long prompts`() {
        val long = "a".repeat(500)
        assertEquals(RequestComplexity.COMPLEX, ComplexityClassifier.classify(long))
    }

    // ── on-device tool caller ─────────────────────────────────────────────────

    private fun toolTurnSettings() = UserSettings(
        cloudEnabled = true,
        cloudApiKey = "sk-test",
        onDeviceToolCallerEnabled = true,
    )

    @Test
    fun `tool caller leads the chain on a tool turn`() = runTest {
        coEvery { settings.current() } returns toolTurnSettings()
        coEvery { cloud.isAvailable() } returns true
        coEvery { toolCaller.isAvailable() } returns true

        val decision = router().route(
            listOf(LlmMessage("user", "turn on the torch")),
            RoutingContext(toolCount = 12),
        )

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals(
            "On-device tool caller",
            (decision as RoutingDecision.Ready).provider.name,
        )
    }

    @Test
    fun `tool caller stays out of a chat turn`() = runTest {
        coEvery { settings.current() } returns toolTurnSettings()
        coEvery { cloud.isAvailable() } returns true
        coEvery { toolCaller.isAvailable() } returns true

        // No tools advertised, so there is nothing for it to call and no reason
        // to pay for its prefill.
        val decision = router().route(
            listOf(LlmMessage("user", "how are you?")),
            RoutingContext(toolCount = 0),
        )

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals("Primary cloud", (decision as RoutingDecision.Ready).provider.name)
    }

    @Test
    fun `cloudOnly keeps the tool caller out of the chain`() = runTest {
        coEvery { settings.current() } returns toolTurnSettings()
        coEvery { cloud.isAvailable() } returns true
        coEvery { toolCaller.isAvailable() } returns true

        val decision = router().route(
            listOf(LlmMessage("user", "rewrite this skill")),
            RoutingContext(toolCount = 12, cloudOnly = true),
        )

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals("Primary cloud", (decision as RoutingDecision.Ready).provider.name)
    }

    @Test
    fun `the disabled toggle keeps the tool caller out even when downloaded`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = true,
            cloudApiKey = "sk-test",
            onDeviceToolCallerEnabled = false,
        )
        coEvery { cloud.isAvailable() } returns true
        coEvery { toolCaller.isAvailable() } returns true

        val decision = router().route(
            listOf(LlmMessage("user", "turn on the torch")),
            RoutingContext(toolCount = 12),
        )

        assertEquals(
            "Primary cloud",
            ((decision as RoutingDecision.Ready).provider).name,
        )
    }

    @Test
    fun `an abstaining tool caller hands the turn to the cloud`() = runTest {
        coEvery { settings.current() } returns toolTurnSettings()
        coEvery { cloud.isAvailable() } returns true
        coEvery { toolCaller.isAvailable() } returns true

        val messages = listOf(LlmMessage("user", "book me a haircut"))
        val cloudAnswer = LlmToolResponse("done", emptyList(), 7, "primary-model", "stop")
        coEvery { toolCaller.completeWithTools(any(), any()) } throws
            ToolCallerAbstained("confidence 0.20")
        coEvery { cloud.completeWithTools(any(), any()) } returns cloudAnswer

        val decision = router().route(messages, RoutingContext(toolCount = 12))
        val result = (decision as RoutingDecision.Ready).provider
            .completeWithTools(messages, emptyList())

        // The abstain is not an error the user ever sees — the chain absorbs it.
        assertEquals(cloudAnswer, result)
        coVerify(exactly = 1) { toolCaller.completeWithTools(any(), any()) }
    }

    @Test
    fun `offline tool turns put the tool caller ahead of the local model`() = runTest {
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = false,
            localLlmEnabled = true,
            onDeviceToolCallerEnabled = true,
        )
        coEvery { cloud.isAvailable() } returns false
        coEvery { specialised.isAvailable() } returns false
        coEvery { local.isAvailable() } returns true
        coEvery { toolCaller.isAvailable() } returns true

        val decision = router().route(
            listOf(LlmMessage("user", "turn on the torch")),
            RoutingContext(toolCount = 12),
        )

        assertTrue(decision is RoutingDecision.Ready)
        assertEquals(
            "On-device tool caller",
            (decision as RoutingDecision.Ready).provider.name,
        )
    }

    @Test
    fun `the tool caller is never alone in a chain`() = runTest {
        // Nothing to hand an abstain to: with no cloud and no local model, a
        // lone tool caller would turn "not sure" into a hard failure.
        coEvery { settings.current() } returns UserSettings(
            cloudEnabled = false,
            localLlmEnabled = false,
            onDeviceToolCallerEnabled = true,
        )
        coEvery { cloud.isAvailable() } returns false
        coEvery { specialised.isAvailable() } returns false
        coEvery { local.isAvailable() } returns false
        coEvery { toolCaller.isAvailable() } returns true

        val decision = router().route(
            listOf(LlmMessage("user", "turn on the torch")),
            RoutingContext(toolCount = 12),
        )

        assertTrue(decision is RoutingDecision.Unavailable)
    }

    private fun router() = HybridLlmRouter(cloud, specialised, local, settings, toolCaller)
}
