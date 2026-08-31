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
}
