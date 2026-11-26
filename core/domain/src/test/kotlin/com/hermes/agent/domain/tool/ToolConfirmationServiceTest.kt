package com.hermes.agent.domain.tool

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.security.DeviceAuthenticationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolConfirmationServiceTest {

    private fun service(
        autoApprovePhoneActions: Boolean = false,
        autoApproveHomeAssistantControl: Boolean = false,
    ): ToolConfirmationService {
        val settings = mockk<ToolAuthorizationSettings>(relaxed = true)
        coEvery { settings.autoApprovePhoneActions() } returns autoApprovePhoneActions
        coEvery { settings.autoApproveHomeAssistantControl() } returns autoApproveHomeAssistantControl
        return ToolConfirmationService(settings, mockk(relaxed = true))
    }

    @Test
    fun `concurrent confirmations are queued instead of overwriting each other`() = runTest {
        val service = service()
        val firstCall = ToolCall("first", "calendar", emptyMap())
        val secondCall = ToolCall("second", "device_settings", emptyMap())

        val first = async { service.awaitConfirmation(firstCall) }
        runCurrent()
        assertEquals(firstCall, service.pendingRequest.value?.call)

        val second = async { service.awaitConfirmation(secondCall) }
        runCurrent()
        assertEquals(
            "the second request must wait its turn",
            firstCall,
            service.pendingRequest.value?.call,
        )

        service.submitConfirmation(service.pendingRequest.value!!.id, true)
        assertTrue(first.await())
        runCurrent()
        assertEquals(secondCall, service.pendingRequest.value?.call)

        service.submitConfirmation(service.pendingRequest.value!!.id, false)
        assertFalse(second.await())
        assertNull(service.pendingRequest.value)
    }

    @Test
    fun `a stale request id cannot answer a newer request`() = runTest {
        val service = service()
        val firstCall = ToolCall("first", "calendar", emptyMap())
        val secondCall = ToolCall("second", "navigation", emptyMap())

        val first = async { service.awaitConfirmation(firstCall) }
        runCurrent()
        val staleId = service.pendingRequest.value!!.id

        service.submitConfirmation(staleId, false)
        assertFalse(first.await())
        runCurrent()

        val second = async { service.awaitConfirmation(secondCall) }
        runCurrent()
        assertEquals(secondCall, service.pendingRequest.value?.call)

        // A verdict addressed to the dismissed dialog must not approve the
        // newer, different call (D9).
        service.submitConfirmation(staleId, true)
        runCurrent()
        assertEquals(
            "the newer request must still be pending",
            secondCall,
            service.pendingRequest.value?.call,
        )

        service.submitConfirmation(service.pendingRequest.value!!.id, true)
        assertTrue(second.await())
        assertNull(service.pendingRequest.value)
    }

    @Test
    fun `opt-in auto approval covers phone tools without creating a pending request`() = runTest {
        val service = service(autoApprovePhoneActions = true)

        assertTrue(service.awaitConfirmation(ToolCall("phone", "device_control", emptyMap())))
        assertNull(service.pendingRequest.value)
    }

    @Test
    fun `phone auto-approval also covers manage_bots, and off it still asks`() = runTest {
        val on = service(autoApprovePhoneActions = true)
        assertTrue(on.awaitConfirmation(ToolCall("bots", "manage_bots", emptyMap())))
        assertNull(on.pendingRequest.value)

        val off = service(autoApprovePhoneActions = false)
        val pending = async { off.awaitConfirmation(ToolCall("bots", "manage_bots", emptyMap())) }
        runCurrent()
        assertEquals("manage_bots", off.pendingRequest.value?.call?.name)
        off.submitConfirmation(off.pendingRequest.value!!.id, false)
        assertFalse(pending.await())
    }

    @Test
    fun `read-only home_assistant actions run without a confirmation prompt`() = runTest {
        val service = service()

        for (action in listOf("list_entities", "get_state", "list_services")) {
            val call = ToolCall("c", "home_assistant", mapOf("action" to JsonPrimitive(action)))
            assertTrue("'${'$'}action' only reads and must not prompt", service.awaitConfirmation(call))
            assertNull(service.pendingRequest.value)
        }
    }

    @Test
    fun `call_service still faces the confirmation dialog`() = runTest {
        // The write action actuates real hardware; it must never ride in on the
        // read-only exemption.
        val service = service()
        val call = ToolCall(
            "c", "home_assistant",
            mapOf("action" to JsonPrimitive("call_service"), "domain" to JsonPrimitive("light")),
        )

        val pending = async { service.awaitConfirmation(call) }
        runCurrent()

        assertEquals(call, service.pendingRequest.value?.call)
        service.submitConfirmation(service.pendingRequest.value!!.id, false)
        assertFalse(pending.await())
    }

    @Test
    fun `a call with no action fails closed and still confirms`() = runTest {
        val service = service()
        val call = ToolCall("c", "home_assistant", emptyMap())

        val pending = async { service.awaitConfirmation(call) }
        runCurrent()

        assertEquals(call, service.pendingRequest.value?.call)
        service.submitConfirmation(service.pendingRequest.value!!.id, false)
        assertFalse(pending.await())
    }

    @Test
    fun `an action nobody allowlisted still confirms`() = runTest {
        // A mutating action added to the tool later must not be exempt by default.
        val service = service()
        val call = ToolCall(
            "c", "home_assistant",
            mapOf("action" to JsonPrimitive("delete_everything")),
        )

        val pending = async { service.awaitConfirmation(call) }
        runCurrent()

        assertEquals(call, service.pendingRequest.value?.call)
        service.submitConfirmation(service.pendingRequest.value!!.id, false)
        assertFalse(pending.await())
    }

    @Test
    fun `opting in lets ordinary HA control run without asking`() = runTest {
        val service = service(autoApproveHomeAssistantControl = true)
        val call = ToolCall(
            "c", "home_assistant",
            mapOf(
                "action" to JsonPrimitive("call_service"),
                "domain" to JsonPrimitive("switch"),
                "entity_id" to JsonPrimitive("switch.cooler"),
            ),
        )

        assertTrue(service.awaitConfirmation(call))
        assertNull(service.pendingRequest.value)
    }

    @Test
    fun `high-risk domains still ask even when auto-approve is on`() = runTest {
        // A wrong light is an annoyance; a wrong lock is a security event.
        for (entity in listOf("lock.front_door", "alarm_control_panel.home", "cover.garage")) {
            val service = service(autoApproveHomeAssistantControl = true)
            val call = ToolCall(
                "c", "home_assistant",
                mapOf(
                    "action" to JsonPrimitive("call_service"),
                    "entity_id" to JsonPrimitive(entity),
                ),
            )

            val pending = async { service.awaitConfirmation(call) }
            runCurrent()

            assertEquals("'${'$'}entity' must still ask", call, service.pendingRequest.value?.call)
            service.submitConfirmation(service.pendingRequest.value!!.id, false)
            assertFalse(pending.await())
        }
    }

    @Test
    fun `HA control still asks while the opt-in is off`() = runTest {
        val service = service(autoApproveHomeAssistantControl = false)
        val call = ToolCall(
            "c", "home_assistant",
            mapOf("action" to JsonPrimitive("call_service"), "domain" to JsonPrimitive("switch")),
        )

        val pending = async { service.awaitConfirmation(call) }
        runCurrent()

        assertEquals(call, service.pendingRequest.value?.call)
        service.submitConfirmation(service.pendingRequest.value!!.id, false)
        assertFalse(pending.await())
    }

    @Test
    fun `the read-only exemption does not leak to other tools`() = runTest {
        // shell is biometric-gated; naming a read-only action must not bypass that.
        val service = service()
        val call = ToolCall("c", "shell", mapOf("action" to JsonPrimitive("list_entities")))

        assertFalse(service.awaitConfirmation(call))
    }

    @Test
    fun `shell uses device authentication even when phone auto approval is enabled`() = runTest {
        val settings = mockk<ToolAuthorizationSettings>(relaxed = true)
        coEvery { settings.autoApprovePhoneActions() } returns true
        val deviceAuth = mockk<DeviceAuthenticationService>()
        coEvery { deviceAuth.authenticate(any(), any()) } returns false
        val service = ToolConfirmationService(settings, deviceAuth)

        assertFalse(service.awaitConfirmation(ToolCall("danger", "shell", emptyMap())))
        coVerify(exactly = 1) { deviceAuth.authenticate(any(), any()) }
        assertNull(service.pendingRequest.value)
    }

    private fun haCall(vararg args: Pair<String, String>) =
        ToolCall("c", "home_assistant", args.associate { (k, v) -> k to JsonPrimitive(v) })

    private suspend fun kotlinx.coroutines.test.TestScope.assertAsks(service: ToolConfirmationService, call: ToolCall) {
        val pending = async { service.awaitConfirmation(call) }
        runCurrent()
        assertEquals("${call.arguments} must ask", call, service.pendingRequest.value?.call)
        service.submitConfirmation(service.pendingRequest.value!!.id, false)
        assertFalse(pending.await())
    }

    @Test
    fun `list_places and render_template only read, so they run without asking`() = runTest {
        val service = service()
        assertTrue(service.awaitConfirmation(haCall("action" to "list_places")))
        assertTrue(service.awaitConfirmation(haCall("action" to "render_template", "template" to "{{ 1 }}")))
        assertNull(service.pendingRequest.value)
    }

    @Test
    fun `assist always asks, even with HA auto-approve on`() = runTest {
        // One sentence can unlock a door, and it names no domain the high-risk check could see.
        assertAsks(service(autoApproveHomeAssistantControl = true), haCall("action" to "assist", "text" to "unlock the front door"))
    }

    @Test
    fun `generic homeassistant calls on a whole place or a risky entity still ask`() = runTest {
        val service = service(autoApproveHomeAssistantControl = true)
        // An area may hold a cover or a lock; homeassistant.toggle would reach them.
        assertAsks(service, haCall("action" to "call_service", "domain" to "homeassistant", "service" to "toggle", "area" to "terrace"))
        assertAsks(service, haCall("action" to "call_service", "domain" to "homeassistant", "service" to "turn_on", "entity_id" to "cover.garage"))
        // ...but on an ordinary entity it is as safe as that entity's own domain.
        assertTrue(service.awaitConfirmation(haCall("action" to "call_service", "domain" to "homeassistant", "service" to "toggle", "entity_id" to "light.lamp")))
    }

    @Test
    fun `control aimed at a whole place always asks`() = runTest {
        // switch.turn_off on the infrastructure label would reach the router and the Pi.
        val call = haCall("action" to "call_service", "domain" to "switch", "service" to "turn_off", "area" to "infrastructure")
        assertAsks(service(autoApproveHomeAssistantControl = true), call)
    }

    private fun guarded(guard: HomeAssistantControlGuard): ToolConfirmationService {
        val settings = mockk<ToolAuthorizationSettings>(relaxed = true)
        coEvery { settings.autoApproveHomeAssistantControl() } returns true
        return ToolConfirmationService(settings, mockk(relaxed = true), guard)
    }

    @Test
    fun `protected switches and buttons ask even with auto-approve, by entity id alone`() = runTest {
        val service = guarded(HomeAssistantControlGuard.ID_ONLY)
        assertAsks(service, haCall("action" to "call_service", "domain" to "switch", "service" to "turn_off", "entity_id" to "switch.hyderabad_flat_router_wps"))
        assertAsks(service, haCall("action" to "call_service", "domain" to "switch", "service" to "toggle", "entity_id" to "switch.pi_power"))
        assertAsks(service, haCall("action" to "call_service", "domain" to "homeassistant", "service" to "turn_off", "entity_id" to "switch.brains"))
        assertAsks(service, haCall("action" to "call_service", "domain" to "button", "service" to "press", "entity_id" to "button.hyderabad_flat_router_reboot_router"))
        // The router hub's UPS outlet under its cloud name ("Channel 3 UPS"): caught by the guard's friendly name.
        assertTrue(HomeAssistantProtectedNames.matches("switch.wipro_smart_extension_socket_3", "Channel 3 UPS"))
        assertFalse(HomeAssistantProtectedNames.matches("switch.upstairs_lamp", "Upstairs lamp"))
    }

    @Test
    fun `the protected-word rule leaves ordinary switches and other services alone`() = runTest {
        val service = guarded(HomeAssistantControlGuard.ID_ONLY)
        // Whole words only: "spider" is not "pi".
        assertTrue(service.awaitConfirmation(haCall("action" to "call_service", "domain" to "switch", "service" to "turn_off", "entity_id" to "switch.spider_lamp")))
        // Turning a router switch ON cuts nothing.
        assertTrue(service.awaitConfirmation(haCall("action" to "call_service", "domain" to "switch", "service" to "turn_on", "entity_id" to "switch.router")))
    }

    @Test
    fun `the guard decides from the friendly name, and a failing guard asks`() = runTest {
        val call = haCall("action" to "call_service", "domain" to "switch", "service" to "turn_off", "entity_id" to "switch.main_1_local_outlet_3")
        val byName = object : HomeAssistantControlGuard {
            override suspend fun mustAsk(call: ToolCall) =
                HomeAssistantProtectedNames.matches("switch.main_1_local_outlet_3", "Main 1 local APC UPS (Legion, router hub)")
        }
        assertAsks(guarded(byName), call)

        val broken = object : HomeAssistantControlGuard {
            override suspend fun mustAsk(call: ToolCall): Boolean = error("HA unreachable")
        }
        assertAsks(guarded(broken), call)
    }
}
