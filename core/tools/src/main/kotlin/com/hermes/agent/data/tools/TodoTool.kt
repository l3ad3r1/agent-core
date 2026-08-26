package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.TaskPriority
import com.hermes.agent.domain.repository.TodoRepository
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
class TodoTool @Inject constructor(
    private val repository: TodoRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "todo",
        description = "Manage personal todos and tasks with due dates and reminders. Distinct from the Kanban board which is for project tickets. " +
            "Actions: 'create' (add a task), 'list' (view pending or all), 'get' (read a task), " +
            "'complete' (mark done), 'uncomplete' (re-open), 'delete' (remove), 'search' (find by title/body), " +
            "'set_priority' (change priority), 'reschedule' (update due date), 'overdue' (see past-due tasks).",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "The action: create, list, get, complete, uncomplete, delete, search, set_priority, reschedule, overdue.", required = true),
            ToolParameter("id", ToolParameterType.STRING, "Task ID (for get, complete, uncomplete, delete, set_priority, reschedule)."),
            ToolParameter("title", ToolParameterType.STRING, "Task title (for create)."),
            ToolParameter("body", ToolParameterType.STRING, "Optional task description."),
            ToolParameter("priority", ToolParameterType.STRING, "Priority: LOW, MEDIUM, HIGH, CRITICAL. Default: MEDIUM."),
            ToolParameter("due_date_ms", ToolParameterType.INTEGER, "Due date as epoch milliseconds (for create, reschedule)."),
            ToolParameter("reminder", ToolParameterType.STRING, "Optional reminder label (for create)."),
            ToolParameter("query", ToolParameterType.STRING, "Search query (for search)."),
            ToolParameter("tag", ToolParameterType.STRING, "Tag to filter by (for search by tag)."),
            ToolParameter("tags", ToolParameterType.ARRAY, "Array of tags for a new task."),
            ToolParameter("limit", ToolParameterType.INTEGER, "Max results for list/search. Default: 20."),
        ),
        category = "productivity",
        // "common" keeps this tool reachable from every agent role, which is the
        // documented intent: any persona may need to plan or track work. Dropping
        // it silently removed `todo` from the CREATIVE and DEVICE_CONTROL agents,
        // which grant "common" but not "todo".
        capabilities = setOf("common", "todo"),
        maxResultSizeChars = 8192,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"]?.str()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing required parameter: 'action'", System.currentTimeMillis() - start)

        return when (action) {
            "create" -> handleCreate(arguments, start)
            "list" -> handleList(arguments, start)
            "get" -> handleGet(arguments, start)
            "complete" -> handleComplete(arguments, start, true)
            "uncomplete" -> handleComplete(arguments, start, false)
            "delete" -> handleDelete(arguments, start)
            "search" -> handleSearch(arguments, start)
            "set_priority" -> handleSetPriority(arguments, start)
            "reschedule" -> handleReschedule(arguments, start)
            "overdue" -> handleOverdue(arguments, start)
            else -> ToolResult.error("Unknown action '$action'.", System.currentTimeMillis() - start)
        }
    }

    private suspend fun handleCreate(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val title = arguments["title"]?.str()?.trim().orEmpty()
        if (title.isBlank()) return ToolResult.error("Missing required parameter 'title'.", System.currentTimeMillis() - start)
        val body = arguments["body"]?.str()?.trim().orEmpty()
        val priorityStr = arguments["priority"]?.str()?.uppercase()?.trim() ?: "MEDIUM"
        val priority = TaskPriority.entries.firstOrNull { it.name == priorityStr }
            ?: return ToolResult.error("Invalid priority '$priorityStr'. Expected LOW, MEDIUM, HIGH, or CRITICAL.", System.currentTimeMillis() - start)
        val dueDateMs = arguments["due_date_ms"]?.longOrNull()
        val reminder = arguments["reminder"]?.str()?.takeIf { it.isNotBlank() }
        val tags = (arguments["tags"] as? JsonArray)?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()
        val task = repository.create(title, body, priority, dueDateMs, reminder, tags)
        val dueStr = if (task.dueDateMs != null) " due=${task.dueDateMs}" else ""
        return ToolResult.ok("Created task #${task.id}: \"$title\" [${task.priority}]$dueStr", System.currentTimeMillis() - start)
    }

    private suspend fun handleList(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val limit = (arguments["limit"]?.int() ?: 20).coerceIn(1, 100)
        val all = repository.observeAll().first().take(limit)
        if (all.isEmpty()) return ToolResult.ok("No tasks found.", System.currentTimeMillis() - start)
        val pending = all.filter { !it.done }
        val done = all.filter { it.done }
        val output = buildString {
            append("Tasks (${pending.size} pending, ${done.size} done):\n")
            pending.forEach { t -> appendTaskLine(t) }
            if (done.isNotEmpty()) {
                append("\n--- Done ---\n")
                done.take(5).forEach { t -> appendTaskLine(t) }
            }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleGet(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val task = repository.get(id) ?: return ToolResult.error("Task #$id not found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Task #${task.id}\n")
            append("Title: ${task.title}\n")
            append("Done: ${task.done}\n")
            append("Priority: ${task.priority}\n")
            if (task.dueDateMs != null) append("Due: ${task.dueDateMs}\n")
            if (task.reminderText != null) append("Reminder: ${task.reminderText}\n")
            if (task.tags.isNotEmpty()) append("Tags: ${task.tags.joinToString(", ")}\n")
            if (task.body.isNotBlank()) append("\n${task.body}\n")
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleComplete(arguments: Map<String, JsonElement>, start: Long, done: Boolean): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val task = repository.get(id) ?: return ToolResult.error("Task #$id not found.", System.currentTimeMillis() - start)
        if (done) repository.complete(id) else repository.uncomplete(id)
        return ToolResult.ok("Task #${id} (\"${task.title}\") ${if (done) "completed" else "reopened"}.", System.currentTimeMillis() - start)
    }

    private suspend fun handleDelete(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val task = repository.get(id) ?: return ToolResult.error("Task #$id not found.", System.currentTimeMillis() - start)
        repository.delete(id)
        return ToolResult.ok("Deleted task #${id} (\"${task.title}\").", System.currentTimeMillis() - start)
    }

    private suspend fun handleSearch(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val query = arguments["query"]?.str()?.trim()
        val tag = arguments["tag"]?.str()?.trim()
        if (query.isNullOrBlank() && tag.isNullOrBlank()) return ToolResult.error("Provide 'query' or 'tag' for search.", System.currentTimeMillis() - start)
        val limit = (arguments["limit"]?.int() ?: 20).coerceIn(1, 100)
        val results = if (!tag.isNullOrBlank()) repository.getByTag(tag) else repository.search(query!!, limit)
        if (results.isEmpty()) return ToolResult.ok("No tasks found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Tasks (${results.size}):\n")
            results.forEach { t -> appendTaskLine(t) }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleSetPriority(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val priorityStr = arguments["priority"]?.str()?.uppercase()?.trim().orEmpty()
        if (priorityStr.isBlank()) return ToolResult.error("Missing required parameter 'priority'.", System.currentTimeMillis() - start)
        val priority = TaskPriority.entries.firstOrNull { it.name == priorityStr }
            ?: return ToolResult.error("Invalid priority '$priorityStr'. Expected LOW, MEDIUM, HIGH, or CRITICAL.", System.currentTimeMillis() - start)
        val task = repository.get(id) ?: return ToolResult.error("Task #$id not found.", System.currentTimeMillis() - start)
        repository.setPriority(id, priority)
        return ToolResult.ok("Task #${id} (\"${task.title}\") priority set to $priority.", System.currentTimeMillis() - start)
    }

    private suspend fun handleReschedule(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val dueDateMs = arguments["due_date_ms"]?.longOrNull()
        val task = repository.get(id) ?: return ToolResult.error("Task #$id not found.", System.currentTimeMillis() - start)
        repository.reschedule(id, dueDateMs)
        val newDue = if (dueDateMs != null) "$dueDateMs" else "cleared"
        return ToolResult.ok("Task #${id} (\"${task.title}\") due date $newDue.", System.currentTimeMillis() - start)
    }

    private suspend fun handleOverdue(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val overdue = repository.observeOverdue().first()
        if (overdue.isEmpty()) return ToolResult.ok("No overdue tasks.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Overdue tasks (${overdue.size}):\n")
            overdue.forEach { t -> appendTaskLine(t) }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private fun StringBuilder.appendTaskLine(t: com.hermes.agent.domain.model.TodoTask) {
        append("${if (t.done) "✓" else "○"} #${t.id} [${t.priority}] ${t.title}")
        if (t.dueDateMs != null) append(" due=${t.dueDateMs}")
        if (t.tags.isNotEmpty()) append(" [${t.tags.joinToString(", ")}]")
        append("\n")
    }
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

@Module
@InstallIn(SingletonComponent::class)
abstract class TodoToolModule {
    @Binds @IntoSet abstract fun bindTodoTool(tool: TodoTool): Tool
}
