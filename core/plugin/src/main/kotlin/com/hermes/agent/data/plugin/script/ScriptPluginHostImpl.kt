package com.hermes.agent.data.plugin.script

import com.hermes.agent.domain.model.TaskPriority
import com.hermes.agent.domain.repository.BookmarkRepository
import com.hermes.agent.domain.repository.NotesRepository
import com.hermes.agent.domain.repository.TodoRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs the permission-gated `hermes.*` APIs a script module can reach, once
 * granted. Bound to [ScriptPluginRepository] via [engine.host] so every call
 * originates from inside the Rhino sandbox on a background dispatcher — see
 * [ScriptPluginHost]'s class doc for the threading contract this must honor.
 *
 * `readData`/`writeData` only ever reach the same three host collections the
 * install-approval prompt already describes to the user
 * ([ScriptPluginPermissions.describe]: "notes, tasks, and bookmarks") — there
 * is deliberately no generic escape hatch into arbitrary app storage.
 *
 * Repository calls are `suspend`, but this interface is not (Rhino calls it
 * synchronously from JS, which has no notion of awaiting a coroutine), so
 * each one is bridged with [runBlocking]. That is safe here specifically
 * because [ScriptPluginEngine.execute] already runs on [Dispatchers.Default],
 * never the main thread, and a local Room query returns in well under the
 * instruction-budget window a module gets.
 */
@Singleton
class ScriptPluginHostImpl @Inject constructor(
    private val notes: NotesRepository,
    private val todos: TodoRepository,
    private val bookmarks: BookmarkRepository,
    private val okHttpClient: OkHttpClient,
) : ScriptPluginHost {

    override fun log(pluginId: String, message: String) {
        Timber.tag("Plugin:$pluginId").d(message)
    }

    override fun readData(pluginId: String, collection: String, query: String): String =
        when (collection.trim().lowercase()) {
            "notes" -> readNotes(query)
            "todos" -> readTodos(query)
            "bookmarks" -> readBookmarks(query)
            else -> throw IllegalArgumentException(
                "Unknown collection '$collection'. Use notes, todos, or bookmarks.",
            )
        }

    override fun writeData(pluginId: String, collection: String, payload: String): String {
        val body = runCatching { Json.parseToJsonElement(payload) as JsonObject }
            .getOrElse { throw IllegalArgumentException("Payload must be a JSON object: ${it.message}") }
        val action = body["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: throw IllegalArgumentException("Payload is missing required field 'action'")

        return when (collection.trim().lowercase()) {
            "notes" -> writeNotes(action, body)
            "todos" -> writeTodos(action, body)
            "bookmarks" -> writeBookmarks(action, body)
            else -> throw IllegalArgumentException(
                "Unknown collection '$collection'. Use notes, todos, or bookmarks.",
            )
        }
    }

    override fun httpGet(pluginId: String, url: String): String {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "url must start with http:// or https://"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) HermesModule/1.0")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} from $url")
            }
            val body = response.body?.string().orEmpty()
            // A module's tool output flows straight into the model's context;
            // an unbounded response could blow well past any sane token budget.
            return if (body.length > MAX_HTTP_RESPONSE_CHARS) {
                body.take(MAX_HTTP_RESPONSE_CHARS) + "\n…[truncated at $MAX_HTTP_RESPONSE_CHARS chars]"
            } else {
                body
            }
        }
    }

    // ---- notes -------------------------------------------------------

    private fun readNotes(query: String): String = runBlocking {
        val items = if (query.isBlank()) notes.observeAll().first().take(MAX_LIST_RESULTS) else notes.search(query, MAX_LIST_RESULTS)
        buildJsonArray {
            items.forEach { n ->
                add(
                    buildJsonObject {
                        put("id", n.id)
                        put("title", n.title)
                        put("content", n.content.take(MAX_FIELD_CHARS))
                        put("category", n.category)
                        put("starred", n.isStarred)
                        put("tags", buildJsonArray { n.tags.forEach { add(it) } })
                    },
                )
            }
        }.toString()
    }

    private fun writeNotes(action: String, body: JsonObject): String = runBlocking {
        when (action) {
            "create" -> {
                val title = body.requireString("title")
                val note = notes.create(
                    title = title,
                    content = body.optString("content").orEmpty(),
                    tags = body.optStringList("tags"),
                    category = body.optString("category") ?: "general",
                    folder = body.optString("folder"),
                )
                okResult(note.id)
            }
            "update" -> {
                val id = body.requireString("id")
                notes.update(
                    id = id,
                    title = body.optString("title"),
                    content = body.optString("content"),
                    tags = body.optStringListOrNull("tags"),
                    starred = body["starred"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                )
                okResult(id)
            }
            "delete" -> {
                val id = body.requireString("id")
                notes.delete(id)
                okResult(id)
            }
            else -> throw IllegalArgumentException("Unknown notes action '$action'. Use create, update, or delete.")
        }
    }

    // ---- todos ---------------------------------------------------------

    private fun readTodos(query: String): String = runBlocking {
        val items = if (query.isBlank()) todos.observeAll().first().take(MAX_LIST_RESULTS) else todos.search(query, MAX_LIST_RESULTS)
        buildJsonArray {
            items.forEach { t ->
                add(
                    buildJsonObject {
                        put("id", t.id)
                        put("title", t.title)
                        put("body", t.body.take(MAX_FIELD_CHARS))
                        put("done", t.done)
                        put("priority", t.priority.name)
                        put("dueDateMs", t.dueDateMs)
                        put("tags", buildJsonArray { t.tags.forEach { add(it) } })
                    },
                )
            }
        }.toString()
    }

    private fun writeTodos(action: String, body: JsonObject): String = runBlocking {
        when (action) {
            "create" -> {
                val title = body.requireString("title")
                val task = todos.create(
                    title = title,
                    body = body.optString("body").orEmpty(),
                    priority = body.optString("priority")?.let { TaskPriority.fromName(it.uppercase()) } ?: TaskPriority.MEDIUM,
                    dueDateMs = body["dueDateMs"]?.jsonPrimitive?.longOrNull,
                    tags = body.optStringList("tags"),
                )
                okResult(task.id)
            }
            "complete" -> {
                val id = body.requireString("id")
                todos.complete(id)
                okResult(id)
            }
            "uncomplete" -> {
                val id = body.requireString("id")
                todos.uncomplete(id)
                okResult(id)
            }
            "delete" -> {
                val id = body.requireString("id")
                todos.delete(id)
                okResult(id)
            }
            else -> throw IllegalArgumentException(
                "Unknown todos action '$action'. Use create, complete, uncomplete, or delete.",
            )
        }
    }

    // ---- bookmarks -------------------------------------------------------

    private fun readBookmarks(query: String): String = runBlocking {
        val items = if (query.isBlank()) bookmarks.observeAll().first().take(MAX_LIST_RESULTS) else bookmarks.search(query, MAX_LIST_RESULTS)
        buildJsonArray {
            items.forEach { b ->
                add(
                    buildJsonObject {
                        put("id", b.id)
                        put("url", b.url)
                        put("title", b.title)
                        put("note", b.note.take(MAX_FIELD_CHARS))
                        put("tags", buildJsonArray { b.tags.forEach { add(it) } })
                    },
                )
            }
        }.toString()
    }

    private fun writeBookmarks(action: String, body: JsonObject): String = runBlocking {
        when (action) {
            "create" -> {
                val url = body.requireString("url")
                val bookmark = bookmarks.create(
                    url = url,
                    title = body.optString("title").orEmpty(),
                    note = body.optString("note").orEmpty(),
                    tags = body.optStringList("tags"),
                )
                okResult(bookmark.id)
            }
            "update" -> {
                val id = body.requireString("id")
                bookmarks.update(
                    id = id,
                    title = body.optString("title"),
                    note = body.optString("note"),
                    tags = body.optStringListOrNull("tags"),
                )
                okResult(id)
            }
            "delete" -> {
                val id = body.requireString("id")
                bookmarks.delete(id)
                okResult(id)
            }
            else -> throw IllegalArgumentException("Unknown bookmarks action '$action'. Use create, update, or delete.")
        }
    }

    private fun okResult(id: String): String = buildJsonObject {
        put("ok", true)
        put("id", id)
    }.toString()

    private companion object {
        const val MAX_LIST_RESULTS = 25
        const val MAX_FIELD_CHARS = 300
        const val MAX_HTTP_RESPONSE_CHARS = 32_000
    }
}

private fun JsonObject.requireString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Payload is missing required field '$key'")

private fun JsonObject.optString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.optStringList(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        ?: emptyList()

/** Distinguishes "field omitted" (null, leave unchanged) from "field is an empty array". */
private fun JsonObject.optStringListOrNull(key: String): List<String>? =
    if (key in this) optStringList(key) else null

@Module
@InstallIn(SingletonComponent::class)
abstract class ScriptPluginHostModule {
    @Binds
    abstract fun bindScriptPluginHost(impl: ScriptPluginHostImpl): ScriptPluginHost
}
