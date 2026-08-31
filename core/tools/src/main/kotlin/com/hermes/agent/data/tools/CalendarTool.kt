package com.hermes.agent.data.tools

import com.hermes.agent.domain.calendar.CalendarEventGateway
import com.hermes.agent.domain.calendar.CalendarEventRequest
import com.hermes.agent.domain.repository.CalendarRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Singleton
class CalendarTool @Inject constructor(
    private val repository: CalendarRepository,
    private val gateway: CalendarEventGateway,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "calendar",
        description = "Read, write, and manage calendar events. Use for scheduling, checking availability, and responding to 'what's on my schedule' queries. " +
            "Actions: 'list' (view upcoming events), 'get' (read an event by id), " +
            "'create' (add an event to the device calendar and local store), 'update' (edit a local event), 'delete' (remove), " +
            "'upcoming' (next N events), 'range' (events between two dates).",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "The action: list, get, create, update, delete, upcoming, range.", required = true),
            ToolParameter("id", ToolParameterType.STRING, "Event ID (for get, update, delete)."),
            ToolParameter("title", ToolParameterType.STRING, "Event title (required for create)."),
            ToolParameter("description", ToolParameterType.STRING, "Event description."),
            ToolParameter("start_ms", ToolParameterType.INTEGER, "Start time as epoch milliseconds."),
            ToolParameter("end_ms", ToolParameterType.INTEGER, "End time as epoch milliseconds."),
            ToolParameter("all_day", ToolParameterType.BOOLEAN, "True if the event spans the full day."),
            ToolParameter("location", ToolParameterType.STRING, "Event location."),
            ToolParameter("source_calendar", ToolParameterType.STRING, "Calendar source name (default: 'default')."),
            ToolParameter("reminder_minutes", ToolParameterType.INTEGER, "Reminder before event in minutes (default: 0)."),
            ToolParameter("limit", ToolParameterType.INTEGER, "Max upcoming events to return. Default: 10."),
            ToolParameter("start_date_ms", ToolParameterType.INTEGER, "Start of date range for 'range' action."),
            ToolParameter("end_date_ms", ToolParameterType.INTEGER, "End of date range for 'range' action."),
        ),
        category = "productivity",
        capabilities = setOf("calendar", "deferrable"),
        requiresConfirmation = true,
        maxResultSizeChars = 8192,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"]?.str()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing required parameter: 'action'", System.currentTimeMillis() - start)

        return when (action) {
            "list", "upcoming" -> handleUpcoming(arguments, start)
            "get" -> handleGet(arguments, start)
            "create" -> handleCreate(arguments, start)
            "update" -> handleUpdate(arguments, start)
            "delete" -> handleDelete(arguments, start)
            "range" -> handleRange(arguments, start)
            else -> ToolResult.error("Unknown action '$action'. Expected list, get, create, update, delete, upcoming, range.", System.currentTimeMillis() - start)
        }
    }

    private suspend fun handleUpcoming(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val limit = (arguments["limit"]?.int() ?: 10).coerceIn(1, 100)
        val events = repository.getUpcoming(limit)
        if (events.isEmpty()) return ToolResult.ok("No upcoming events.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Upcoming events ($limit):\n")
            events.forEach { e -> appendEventLine(e) }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleGet(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val event = repository.get(id) ?: return ToolResult.error("Event #$id not found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Event #${event.id}\n")
            append("Title: ${event.title}\n")
            append("Calendar: ${event.sourceCalendar}\n")
            append("Start: ${event.startMs}\n")
            append("End: ${event.endMs}\n")
            append("All-day: ${event.allDay}\n")
            if (event.location != null) append("Location: ${event.location}\n")
            if (event.reminderMinutes > 0) append("Reminder: ${event.reminderMinutes} min before\n")
            if (event.description.isNotBlank()) append("\n${event.description}\n")
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleCreate(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val title = arguments["title"]?.str()?.trim().orEmpty()
        if (title.isBlank()) return ToolResult.error("Missing required parameter 'title'.", System.currentTimeMillis() - start)
        val description = arguments["description"]?.str()?.trim().orEmpty()
        val startMs = arguments["start_ms"]?.longOrNull() ?: return ToolResult.error("Missing required parameter 'start_ms'.", System.currentTimeMillis() - start)
        val endMs = arguments["end_ms"]?.longOrNull() ?: startMs + 3_600_000L
        if (endMs <= startMs) {
            return ToolResult.error("'end_ms' must be later than 'start_ms'.", System.currentTimeMillis() - start)
        }
        val allDay = arguments["all_day"]?.bool() ?: false
        val location = arguments["location"]?.str()?.takeIf { it.isNotBlank() }
        val sourceCalendar = arguments["source_calendar"]?.str()?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
        val reminderMinutes = arguments["reminder_minutes"]?.int() ?: 0
        if (reminderMinutes !in 0..40_320) {
            return ToolResult.error("'reminder_minutes' must be between 0 and 40320.", System.currentTimeMillis() - start)
        }

        val event = repository.create(title, description, startMs, endMs, allDay, sourceCalendar, location, reminderMinutes)

        val systemResult = gateway.createEvent(
            CalendarEventRequest(
                title = title,
                start = Instant.ofEpochMilli(startMs),
                end = Instant.ofEpochMilli(endMs),
                timeZoneId = ZoneId.systemDefault().id,
                location = location,
            ),
        )

        return when {
            systemResult.isFailure -> {
                val errMsg = systemResult.exceptionOrNull()?.message?.takeIf { it.isNotEmpty() } ?: "unknown error"
                ToolResult.ok(
                    "Local event #${event.id} created (\"$title\"), but device calendar write failed: $errMsg. Check calendar permissions.",
                    System.currentTimeMillis() - start,
                )
            }
            else -> {
                val systemId = systemResult.getOrNull()
                ToolResult.ok(
                    "Created event #${event.id}: \"$title\" (local) | device calendar ID=${systemId?.id}, calendar=${systemId?.calendarName} (${event.startMs}-${event.endMs}).",
                    System.currentTimeMillis() - start,
                )
            }
        }
    }

    private suspend fun handleUpdate(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val current = repository.get(id) ?: return ToolResult.error("Event #$id not found.", System.currentTimeMillis() - start)
        val title = arguments["title"]?.str()?.takeIf { it.isNotBlank() }
        val description = arguments["description"]?.str()?.takeIf { !it.isNullOrBlank() }
        val startMs = arguments["start_ms"]?.longOrNull()
        val endMs = arguments["end_ms"]?.longOrNull()
        val allDay = arguments["all_day"]?.bool()
        val location = arguments["location"]?.str()?.takeIf { it.isNotBlank() }
        repository.update(id, title, description, startMs, endMs, allDay, location)
        return ToolResult.ok("Updated event #${id} (\"${current.title}\").", System.currentTimeMillis() - start)
    }

    private suspend fun handleDelete(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val current = repository.get(id) ?: return ToolResult.error("Event #$id not found.", System.currentTimeMillis() - start)
        repository.delete(id)
        return ToolResult.ok("Deleted event #${id} (\"${current.title}\").", System.currentTimeMillis() - start)
    }

    private suspend fun handleRange(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val startMs = arguments["start_date_ms"]?.longOrNull() ?: return ToolResult.error("Missing required parameter 'start_date_ms'.", System.currentTimeMillis() - start)
        val endMs = arguments["end_date_ms"]?.longOrNull() ?: return ToolResult.error("Missing required parameter 'end_date_ms'.", System.currentTimeMillis() - start)
        if (endMs <= startMs) {
            return ToolResult.error("'end_date_ms' must be later than 'start_date_ms'.", System.currentTimeMillis() - start)
        }
        val events = repository.getByDateRange(startMs, endMs)
        if (events.isEmpty()) return ToolResult.ok("No events in range.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Events in range (${events.size}):\n")
            events.forEach { e -> appendEventLine(e) }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private fun StringBuilder.appendEventLine(e: com.hermes.agent.domain.model.CalendarEvent) {
        append("• #${e.id} ${e.title}")
        if (e.location != null) append(" @${e.location}")
        append(" [${e.sourceCalendar}] start=${e.startMs} end=${e.endMs}")
        if (e.allDay) append(" (all-day)")
        append("\n")
    }
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarToolModule {
    @Binds @IntoSet abstract fun bindCalendarTool(tool: CalendarTool): Tool
}
