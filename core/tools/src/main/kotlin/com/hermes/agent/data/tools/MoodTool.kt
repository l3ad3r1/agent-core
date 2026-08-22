package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.MoodLevel
import com.hermes.agent.domain.repository.MoodRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Singleton
class MoodTool @Inject constructor(
    private val repository: MoodRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "mood",
        description = "Log daily mood entries and receive insights about emotional patterns. " +
            "Actions: 'log' (record today's mood), 'list' (view entries), 'get' (read by id), " +
            "'delete' (remove), 'insights' (get trend and most frequent mood over N days), " +
            "'today' (get today's entry, create if missing).",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "The action: log, list, get, delete, insights, today.", required = true),
            ToolParameter("id", ToolParameterType.STRING, "Entry ID (for get, delete)."),
            ToolParameter("mood", ToolParameterType.STRING, "Mood level: VERY_BAD, BAD, MID, GOOD, VERY_GOOD."),
            ToolParameter("intensity", ToolParameterType.INTEGER, "Intensity 1-10. Default: 5."),
            ToolParameter("note", ToolParameterType.STRING, "Optional note about the mood."),
            ToolParameter("tags", ToolParameterType.ARRAY, "Optional tags describing influences or context."),
            ToolParameter("date_ms", ToolParameterType.INTEGER, "Date as epoch ms (for log). Defaults to today."),
            ToolParameter("days", ToolParameterType.INTEGER, "Lookback days for insights. Default: 30."),
            ToolParameter("limit", ToolParameterType.INTEGER, "Max entries for list. Default: 20."),
        ),
        category = "productivity",
        capabilities = setOf("mood"),
        maxResultSizeChars = 4096,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"]?.str()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing required parameter: 'action'", System.currentTimeMillis() - start)

        return when (action) {
            "log" -> handleLog(arguments, start)
            "list" -> handleList(arguments, start)
            "get" -> handleGet(arguments, start)
            "delete" -> handleDelete(arguments, start)
            "insights" -> handleInsights(arguments, start)
            "today" -> handleToday(arguments, start)
            else -> ToolResult.error("Unknown action '$action'. Expected log, list, get, delete, insights, today.", System.currentTimeMillis() - start)
        }
    }

    private suspend fun handleLog(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val moodStr = arguments["mood"]?.str()?.uppercase()?.trim()
            ?: return ToolResult.error("Missing required parameter 'mood'.", System.currentTimeMillis() - start)
        val mood = MoodLevel.entries.firstOrNull { it.name == moodStr }
            ?: return ToolResult.error("Invalid mood '$moodStr'. Expected VERY_BAD, BAD, MID, GOOD, or VERY_GOOD.", System.currentTimeMillis() - start)
        val intensity = arguments["intensity"]?.int() ?: 5
        if (intensity !in 1..10) {
            return ToolResult.error("Intensity must be between 1 and 10.", System.currentTimeMillis() - start)
        }
        val note = arguments["note"]?.str()?.trim().orEmpty()
        val dateMs = arguments["date_ms"]?.longOrNull() ?: todayStartMs()
        val tags = (arguments["tags"] as? JsonArray)?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()
        val entry = repository.create(dateMs, mood, intensity, note, tags)
        return ToolResult.ok("Logged mood #${entry.id}: ${entry.mood} (intensity=${entry.intensity}).", System.currentTimeMillis() - start)
    }

    private suspend fun handleList(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val limit = (arguments["limit"]?.int() ?: 20).coerceIn(1, 100)
        val entries = repository.observeAll().first().take(limit)
        if (entries.isEmpty()) return ToolResult.ok("No mood entries found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Mood entries ($limit):\n")
            entries.forEach { e -> appendEntryLine(e) }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleGet(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val entry = repository.get(id) ?: return ToolResult.error("Mood entry #$id not found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Mood entry #${entry.id}\n")
            append("Date: ${entry.dateMs}\n")
            append("Mood: ${entry.mood}\n")
            append("Intensity: ${entry.intensity}/10\n")
            if (entry.note.isNotEmpty()) append("Note: ${entry.note}\n")
            if (entry.tags.isNotEmpty()) append("Tags: ${entry.tags.joinToString(", ")}\n")
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleDelete(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val entry = repository.get(id) ?: return ToolResult.error("Mood entry #$id not found.", System.currentTimeMillis() - start)
        repository.delete(id)
        return ToolResult.ok("Deleted mood entry #${id}.", System.currentTimeMillis() - start)
    }

    private suspend fun handleInsights(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val days = (arguments["days"]?.int() ?: 30).coerceIn(1, 3650)
        val trend = repository.getTrend(days)
        val frequent = repository.getMostFrequentMood(days)
        val stats = repository.getWeeklyStats(days)

        if (trend.isEmpty()) return ToolResult.ok("No mood data for the past $days days.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Mood insights (last $days days):\n")
            if (frequent != null) append("Most frequent mood: $frequent\n")
            append("\nDistribution:\n")
            stats.forEach { (level, count) ->
                append("  $level: $count entries\n")
            }
            val recent = trend.takeLast(7)
            append("\nRecent trend (${recent.size} entries):\n")
            recent.forEach { e -> appendEntryLine(e) }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleToday(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val todayMs = todayStartMs()
        val entry = repository.getForDate(todayMs)
        if (entry == null) return ToolResult.ok("No mood logged for today. Use 'log' to record.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Today's mood:\n")
            append("  Mood: ${entry.mood} (intensity=${entry.intensity}/10)\n")
            if (entry.note.isNotEmpty()) append("  Note: ${entry.note}\n")
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private fun StringBuilder.appendEntryLine(e: com.hermes.agent.domain.model.MoodEntry) {
        append("• #${e.id} ${e.mood} (intensity=${e.intensity})")
        if (e.note.isNotEmpty()) append(" — ${e.note.take(80)}")
        append("\n")
    }

    private fun todayStartMs(): Long {
        val cal = java.util.GregorianCalendar()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

@Module
@InstallIn(SingletonComponent::class)
abstract class MoodToolModule {
    @Binds @IntoSet abstract fun bindMoodTool(tool: MoodTool): Tool
}
