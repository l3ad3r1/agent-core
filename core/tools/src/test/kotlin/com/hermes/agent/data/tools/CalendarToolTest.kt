package com.hermes.agent.data.tools

import com.hermes.agent.domain.calendar.CalendarEventGateway
import com.hermes.agent.domain.calendar.CalendarEventRequest
import com.hermes.agent.domain.calendar.CreatedCalendarEvent
import com.hermes.agent.domain.model.CalendarEvent
import com.hermes.agent.domain.repository.CalendarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert
import org.junit.Test

class CalendarToolTest {

    private class FakeCalendarRepository : CalendarRepository {
        private val events = HashMap<String, CalendarEvent>()
        var nextId = 1

        override fun observeAll(): Flow<List<CalendarEvent>> =
            flowOf(events.values.toList())

        override suspend fun get(id: String): CalendarEvent? = events[id]

        override suspend fun create(
            title: String,
            description: String,
            startMs: Long,
            endMs: Long,
            allDay: Boolean,
            sourceCalendar: String,
            location: String?,
            reminderMinutes: Int,
        ): CalendarEvent {
            val event = CalendarEvent(
                id = "ce_$nextId",
                title = title,
                description = description,
                startMs = startMs,
                endMs = endMs,
                allDay = allDay,
                sourceCalendar = sourceCalendar,
                location = location,
                reminderMinutes = reminderMinutes,
            )
            events["ce_$nextId"] = event
            nextId++
            return event
        }

        override suspend fun update(id: String, title: String?, description: String?, startMs: Long?, endMs: Long?, allDay: Boolean?, location: String?) {
            val current = events[id] ?: return
            events[id] = current.copy(
                title = title ?: current.title,
                description = description ?: current.description,
                startMs = startMs ?: current.startMs,
                endMs = endMs ?: current.endMs,
                allDay = allDay ?: current.allDay,
                location = location ?: current.location,
            )
        }

        override suspend fun delete(id: String) { events.remove(id) }
        override suspend fun getUpcoming(limit: Int): List<CalendarEvent> =
            events.values.filter { it.startMs > System.currentTimeMillis() }.take(limit)
        override suspend fun getByDateRange(startMs: Long, endMs: Long): List<CalendarEvent> =
            events.values.filter { it.startMs in startMs until endMs }
    }

    private class FakeGateway(
        private val result: Result<CreatedCalendarEvent> =
            Result.success(CreatedCalendarEvent(42L, "Primary")),
    ) : CalendarEventGateway {
        var lastRequest: CalendarEventRequest? = null

        override suspend fun createEvent(request: CalendarEventRequest): Result<CreatedCalendarEvent> {
            lastRequest = request
            return result
        }
    }

    @Test
    fun `creates an event and calls the system gateway`() = runTest {
        val repo = FakeCalendarRepository()
        val gateway = FakeGateway()
        val tool = CalendarTool(repo, gateway)
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Meeting"),
                "start_ms" to JsonPrimitive(1_000_000L),
                "end_ms" to JsonPrimitive(1_060_000L),
            ),
        )
        Assert.assertTrue("Expected success, got error: ${result.errorMessage}", result.success)
        Assert.assertTrue("Expected local id in output", result.output.contains("ce_1"))
        Assert.assertTrue("Expected device calendar id in output", result.output.contains("42"))
        Assert.assertNotNull("Gateway should have been called", gateway.lastRequest)
        Assert.assertEquals("Meeting", gateway.lastRequest!!.title)
    }

    @Test
    fun `falls back gracefully when gateway fails`() = runTest {
        val repo = FakeCalendarRepository()
        val gateway = FakeGateway(Result.failure(IllegalStateException("permission denied")))
        val tool = CalendarTool(repo, gateway)
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Meeting"),
                "start_ms" to JsonPrimitive(1_000_000L),
            ),
        )
        Assert.assertTrue("Expected local creation to succeed despite gateway failure", result.success)
        Assert.assertTrue("Expected fallback message", result.output.contains("device calendar write failed"))
    }

    @Test
    fun `lists upcoming events`() = runTest {
        val repo = FakeCalendarRepository()
        val futureMs = System.currentTimeMillis() + 3_600_000L
        repo.create("Event A", "", futureMs, futureMs + 3600_000)
        val tool = CalendarTool(repo, FakeGateway())
        val result = tool.execute(
            mapOf("action" to JsonPrimitive("upcoming")),
        )
        Assert.assertTrue("Expected success, got error: ${result.errorMessage}", result.success)
        Assert.assertTrue("Expected output to contain Event A, got: ${result.output}", result.output.contains("Event A"))
    }

    @Test
    fun `returns error on missing action`() = runTest {
        val tool = CalendarTool(FakeCalendarRepository(), FakeGateway())
        val result = tool.execute(emptyMap())
        Assert.assertFalse("Expected failure", result.success)
        Assert.assertTrue("Expected error to mention 'action', got: ${result.errorMessage}", result.errorMessage.orEmpty().contains("action"))
    }

    @Test
    fun `rejects an event ending before it starts`() = runTest {
        val gateway = FakeGateway()
        val tool = CalendarTool(FakeCalendarRepository(), gateway)
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Invalid"),
                "start_ms" to JsonPrimitive(2_000L),
                "end_ms" to JsonPrimitive(1_000L),
            ),
        )

        Assert.assertFalse("Expected failure", result.success)
        Assert.assertTrue(result.errorMessage.orEmpty().contains("later"))
        Assert.assertNull("Gateway must not be called", gateway.lastRequest)
    }
}
