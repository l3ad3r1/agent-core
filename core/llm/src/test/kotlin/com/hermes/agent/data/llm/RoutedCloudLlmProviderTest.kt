package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedCloudLlmProviderTest {

    private val relayProfile = mockk<LlmProvider> {
        coEvery { complete(any()) } returns LlmResponse("A practical person.", 10, "hermes-relay")
    }

    @Test
    fun `a model configured only as a provider profile serves background work`() = runTest {
        // On the tablet the relay was a provider profile and the legacy cloud key was
        // empty: chat worked, the user model reported "no model available".
        val context = slot<RoutingContext>()
        val router = mockk<LlmRouter> {
            coEvery { route(any(), capture(context)) } returns RoutingDecision.Ready(relayProfile, "profile")
        }
        val provider = RoutedCloudLlmProvider(router)

        assertTrue(provider.isAvailable())
        assertEquals("A practical person.", provider.complete(listOf(LlmMessage("user", "Known facts"))).content)
        assertTrue("background writes never fall to the on-device model", context.captured.cloudOnly)
    }

    @Test
    fun `nothing reachable reads as unavailable`() = runTest {
        val router = mockk<LlmRouter> {
            coEvery { route(any(), any()) } returns RoutingDecision.Unavailable(relayProfile, "no cloud")
        }

        assertFalse(RoutedCloudLlmProvider(router).isAvailable())
    }
}
