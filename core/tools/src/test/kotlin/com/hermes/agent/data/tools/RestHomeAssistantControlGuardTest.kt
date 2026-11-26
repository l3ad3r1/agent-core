package com.hermes.agent.data.tools

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
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

class RestHomeAssistantControlGuardTest {

    private lateinit var server: MockWebServer
    private lateinit var guard: RestHomeAssistantControlGuard

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings(homeAssistantUrl = server.url("/").toString(), homeAssistantToken = "t")
        guard = RestHomeAssistantControlGuard(settings, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun turnOff(entityId: String) = ToolCall(
        "c", "home_assistant",
        mapOf(
            "action" to JsonPrimitive("call_service"),
            "domain" to JsonPrimitive("switch"),
            "service" to JsonPrimitive("turn_off"),
            "entity_id" to JsonPrimitive(entityId),
        ),
    )

    private fun state(name: String) = MockResponse().setBody(
        """{"entity_id": "switch.x", "state": "on", "attributes": {"friendly_name": "$name"}}""",
    )

    @Test
    fun `a switch whose friendly name names the router asks`() = runTest {
        server.enqueue(state("Main 1 local APC UPS (Legion, router hub)"))

        assertTrue(guard.mustAsk(turnOff("switch.main_1_local_outlet_3")))
        assertEquals("/api/states/switch.main_1_local_outlet_3", server.takeRequest().path)
    }

    @Test
    fun `an ordinary switch does not ask`() = runTest {
        server.enqueue(state("Bedroom fan"))

        assertFalse(guard.mustAsk(turnOff("switch.main_1_local_outlet_1")))
    }

    @Test
    fun `an entity id that already matches needs no lookup`() = runTest {
        assertTrue(guard.mustAsk(turnOff("switch.hyderabad_flat_router_wps")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `when HA cannot say, it asks`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(guard.mustAsk(turnOff("switch.main_1_local_outlet_3")))
    }

    @Test
    fun `services the rule does not cover are not looked up`() = runTest {
        val turnOn = ToolCall(
            "c", "home_assistant",
            mapOf(
                "action" to JsonPrimitive("call_service"),
                "domain" to JsonPrimitive("switch"),
                "service" to JsonPrimitive("turn_on"),
                "entity_id" to JsonPrimitive("switch.main_1_local_outlet_3"),
            ),
        )

        assertFalse(guard.mustAsk(turnOn))
        assertEquals(0, server.requestCount)
    }
}
