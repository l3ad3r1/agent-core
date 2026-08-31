package com.hermes.agent.data.tools

import com.hermes.agent.domain.repository.BookmarkRepository
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
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Singleton
class BookmarkTool @Inject constructor(
    private val repository: BookmarkRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "bookmarks",
        description = "Save, organize, and retrieve URLs and links. Use when the user asks to 'save this link', 'find a bookmark', or wants to look up research references. " +
            "Actions: 'save' (store a URL), 'list' (view all), 'get' (read by id), 'search' (find by title/url), " +
            "'delete' (remove), 'update' (edit title/note/tags).",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "The action: save, list, get, search, delete, update.", required = true),
            ToolParameter("id", ToolParameterType.STRING, "Bookmark ID (for get, update, delete)."),
            ToolParameter("url", ToolParameterType.STRING, "URL to save (required for save)."),
            ToolParameter("title", ToolParameterType.STRING, "Bookmark title (for save, update)."),
            ToolParameter("note", ToolParameterType.STRING, "Personal note about the bookmark (for save, update)."),
            ToolParameter("query", ToolParameterType.STRING, "Search query for title, URL, or note."),
            ToolParameter("tag", ToolParameterType.STRING, "Tag to filter by."),
            ToolParameter("tags", ToolParameterType.ARRAY, "Array of string tags (for save, update)."),
            ToolParameter("limit", ToolParameterType.INTEGER, "Max results. Default: 20."),
        ),
        category = "productivity",
        capabilities = setOf("bookmarks", "deferrable"),
        maxResultSizeChars = 8192,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = arguments["action"]?.str()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing required parameter: 'action'", System.currentTimeMillis() - start)

        return when (action) {
            "save" -> handleSave(arguments, start)
            "list" -> handleList(arguments, start)
            "get" -> handleGet(arguments, start)
            "search" -> handleSearch(arguments, start)
            "delete" -> handleDelete(arguments, start)
            "update" -> handleUpdate(arguments, start)
            else -> ToolResult.error("Unknown action '$action'. Expected save, list, get, search, delete, update.", System.currentTimeMillis() - start)
        }
    }

    private suspend fun handleSave(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val url = arguments["url"]?.str()?.trim().orEmpty()
        if (url.isBlank()) return ToolResult.error("Missing required parameter 'url'.", System.currentTimeMillis() - start)
        val title = arguments["title"]?.str()?.trim().orEmpty()
        val note = arguments["note"]?.str()?.trim().orEmpty()
        val tags = (arguments["tags"] as? JsonArray)?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()
        val bookmark = repository.create(url, title, note, tags)
        return ToolResult.ok("Saved bookmark #${bookmark.id}: \"$title\" ($url).", System.currentTimeMillis() - start)
    }

    private suspend fun handleList(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val limit = (arguments["limit"]?.int() ?: 20).coerceIn(1, 100)
        val bookmarks = repository.observeAll().first().take(limit)
        if (bookmarks.isEmpty()) return ToolResult.ok("No bookmarks found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Bookmarks ($limit):\n")
            bookmarks.forEach { b ->
                append("• #${b.id} ${b.title} - ${b.url}")
                if (b.tags.isNotEmpty()) append(" [${b.tags.joinToString(", ")}]")
                append("\n")
            }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleGet(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val b = repository.get(id) ?: return ToolResult.error("Bookmark #$id not found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Bookmark #${b.id}\n")
            append("Title: ${b.title}\n")
            append("URL: ${b.url}\n")
            if (b.note.isNotBlank()) append("Note: ${b.note}\n")
            if (b.tags.isNotEmpty()) append("Tags: ${b.tags.joinToString(", ")}\n")
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleSearch(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val query = arguments["query"]?.str()?.trim()
        val tag = arguments["tag"]?.str()?.trim()
        if (query.isNullOrBlank() && tag.isNullOrBlank()) return ToolResult.error("Provide 'query' or 'tag'.", System.currentTimeMillis() - start)
        val limit = (arguments["limit"]?.int() ?: 20).coerceIn(1, 100)
        val results = if (!tag.isNullOrBlank()) repository.getByTag(tag) else repository.search(query!!, limit)
        if (results.isEmpty()) return ToolResult.ok("No bookmarks found.", System.currentTimeMillis() - start)
        val output = buildString {
            append("Bookmarks (${results.size}):\n")
            results.forEach { b ->
                append("• #${b.id} ${b.title} - ${b.url}")
                if (b.tags.isNotEmpty()) append(" [${b.tags.joinToString(", ")}]")
                append("\n")
            }
        }
        return ToolResult.ok(output.trimEnd(), System.currentTimeMillis() - start)
    }

    private suspend fun handleDelete(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val b = repository.get(id) ?: return ToolResult.error("Bookmark #$id not found.", System.currentTimeMillis() - start)
        repository.delete(id)
        return ToolResult.ok("Deleted bookmark #${id} (\"${b.title}\").", System.currentTimeMillis() - start)
    }

    private suspend fun handleUpdate(arguments: Map<String, JsonElement>, start: Long): ToolResult {
        val id = arguments["id"]?.str()?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("Missing required parameter 'id'.", System.currentTimeMillis() - start)
        val b = repository.get(id) ?: return ToolResult.error("Bookmark #$id not found.", System.currentTimeMillis() - start)
        val title = arguments["title"]?.str()?.takeIf { it.isNotBlank() }
        val note = arguments["note"]?.str()
        val tags = (arguments["tags"] as? JsonArray)?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) }
        repository.update(id, title, note, tags)
        return ToolResult.ok("Updated bookmark #${id} (\"${b.title}\").", System.currentTimeMillis() - start)
    }
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull

@Module
@InstallIn(SingletonComponent::class)
abstract class BookmarkToolModule {
    @Binds @IntoSet abstract fun bindBookmarkTool(tool: BookmarkTool): Tool
}
