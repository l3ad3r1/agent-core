package com.hermes.agent.data.tools

import com.hermes.agent.domain.repository.NotesRepository
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
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Singleton
class NotesTool @Inject constructor(
    private val repository: NotesRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "notes",
        description = "Read, write, and search structured notes (markdown) for knowledge capture, " +
            "meeting summaries, and research. Actions: 'create' (store a note), 'list' (view all or filter by category), " +
            "'get' (read a note by id), 'search' (keyword search across titles and content), " +
            "'update' (edit title/content/tags/starred), 'toggle_star' (mark as favorite), 'delete'.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "The action: create, list, get, search, update, toggle_star, delete.", required = true),
            ToolParameter("id", ToolParameterType.STRING, "Note ID (for get, update, toggle_star, delete)."),
            ToolParameter("title", ToolParameterType.STRING, "Note title (for create, update)."),
            ToolParameter("content", ToolParameterType.STRING, "Markdown note content (for create, update)."),
            ToolParameter("query", ToolParameterType.STRING, "Search or filter query (for search, list-by-category)."),
            ToolParameter("category", ToolParameterType.STRING, "Filter by category, e.g. 'work', 'personal', 'research'."),
            ToolParameter("folder", ToolParameterType.STRING, "Optional folder for a new note."),
            ToolParameter("tags", ToolParameterType.ARRAY, "Array of string tags (for create, update)."),
            ToolParameter("starred", ToolParameterType.BOOLEAN, "Starred status (for update)."),
            ToolParameter("limit", ToolParameterType.INTEGER, "Max results for list/search. Default: 20."),
        ),
        category = "productivity",
        capabilities = setOf("notes"),
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
            "search" -> handleSearch(arguments, start)
            "update" -> handleUpdate(arguments, start)
            "toggle_star" -> handleToggleStar(arguments, start)
            "delete" -> handleDelete(arguments, start)
            else -> ToolResult.error("Unknown action '$action'. Expected create, list, get, search, update, toggle_star, delete.", System.currentTimeMillis() - start)
        }
    }

    private suspend fun handleCreate(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val title = arguments["title"]?.str()?.trim().orEmpty()
        if (title.isBlank()) return ToolResult.error("Missing required parameter 'title' for create.", System.currentTimeMillis() - start)
        val content = arguments["content"]?.str()?.trim().orEmpty()
        val category = arguments["category"]?.str()?.trim()?.takeIf { it.isNotEmpty() } ?: "general"
        val folder = arguments["folder"]?.str()?.trim()?.takeIf { it.isNotEmpty() }
        val tags = arguments.stringList("tags")

        val note = repository.create(title, content, tags, category, folder)
        return ToolResult.ok("Created note '#${note.id}': \"$title\" (category=$category, ${note.tags.size} tags).", System.currentTimeMillis() - start)
    }

    private suspend fun handleList(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val category = arguments["category"]?.str()?.trim()
        val limit = arguments.resultLimit()
        val notes = if (category != null) repository.getByCategory(category) else repository.observeAll().first()
        val list = notes.take(limit)
        if (list.isEmpty()) return ToolResult.ok("No notes found${if (category != null) " in category '$category'" else ""}.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Notes (${list.size}):\n")
            list.forEach { n ->
                append("• #${n.id} [${n.category}] ${n.title}")
                if (n.isStarred) append(" ⭐")
                if (n.tags.isNotEmpty()) append(" [${n.tags.joinToString(", ")}]")
                append("\n")
            }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleGet(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id' for get.", System.currentTimeMillis() - start)
        val note = repository.get(id) ?: return ToolResult.error("Note #$id not found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Note #${note.id}\n")
            append("Title: ${note.title}\n")
            append("Category: ${note.category}\n")
            append("Starred: ${note.isStarred}\n")
            if (note.tags.isNotEmpty()) append("Tags: ${note.tags.joinToString(", ")}\n")
            append("\nContent:\n${note.content}\n")
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleSearch(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val query = arguments["query"]?.str()?.trim().orEmpty()
        if (query.isBlank()) return ToolResult.error("Missing required parameter 'query' for search.", System.currentTimeMillis() - start)
        val limit = arguments.resultLimit()
        val results = repository.search(query, limit)
        if (results.isEmpty()) return ToolResult.ok("No notes found matching '$query'.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Search results for '$query' (${results.size}):\n")
            results.forEach { n ->
                append("• #${n.id} [${n.category}] ${n.title}\n")
                if (n.content.isNotEmpty()) append("  ${n.content.take(200)}\n")
            }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleUpdate(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id' for update.", System.currentTimeMillis() - start)
        val current = repository.get(id) ?: return ToolResult.error("Note #$id not found.", System.currentTimeMillis() - start)
        val title = arguments["title"]?.str()?.takeIf { it.isNotBlank() }
        val content = arguments["content"]?.str()
        val tags = (arguments["tags"] as? JsonArray)?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) }
        val starred = arguments["starred"]?.bool()
        repository.update(id, title, content, tags, starred)
        return ToolResult.ok("Updated note #${id} (\"${current.title}\").", System.currentTimeMillis() - start)
    }

    private suspend fun handleToggleStar(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id' for toggle_star.", System.currentTimeMillis() - start)
        val current = repository.get(id) ?: return ToolResult.error("Note #$id not found.", System.currentTimeMillis() - start)
        repository.toggleStar(id)
        val starState = if (current.isStarred) "unstarred" else "starred"
        return ToolResult.ok("Note #${id} (\"${current.title}\") $starState.", System.currentTimeMillis() - start)
    }

    private suspend fun handleDelete(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id' for delete.", System.currentTimeMillis() - start)
        val current = repository.get(id) ?: return ToolResult.error("Note #$id not found.", System.currentTimeMillis() - start)
        repository.delete(id)
        return ToolResult.ok("Deleted note #${id} (\"${current.title}\").", System.currentTimeMillis() - start)
    }
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun Map<String, JsonElement>.resultLimit(default: Int = 20): Int =
    (this["limit"]?.int() ?: default).coerceIn(1, 100)
private fun Map<String, JsonElement>.stringList(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()

@Module
@InstallIn(SingletonComponent::class)
abstract class NotesToolModule {
    @Binds @IntoSet abstract fun bindNotesTool(tool: NotesTool): Tool
}
