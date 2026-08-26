package com.hermes.agent.data.plugin.script

import com.hermes.agent.domain.model.Bookmark
import com.hermes.agent.domain.model.Note
import com.hermes.agent.domain.model.TaskPriority
import com.hermes.agent.domain.model.TodoTask
import com.hermes.agent.domain.repository.BookmarkRepository
import com.hermes.agent.domain.repository.NotesRepository
import com.hermes.agent.domain.repository.TodoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPluginHostImplTest {

    private class FakeNotesRepository : NotesRepository {
        val items = mutableMapOf<String, Note>()
        override fun observeAll(): Flow<List<Note>> = MutableStateFlow(items.values.toList())
        override suspend fun get(id: String): Note? = items[id]
        override suspend fun create(title: String, content: String, tags: List<String>, category: String, folder: String?): Note {
            val note = Note(id = "note-${items.size + 1}", title = title, content = content, tags = tags, category = category, folder = folder)
            items[note.id] = note
            return note
        }
        override suspend fun update(id: String, title: String?, content: String?, tags: List<String>?, starred: Boolean?) {
            val current = items[id] ?: return
            items[id] = current.copy(
                title = title ?: current.title,
                content = content ?: current.content,
                tags = tags ?: current.tags,
                isStarred = starred ?: current.isStarred,
            )
        }
        override suspend fun toggleStar(id: String) {
            items[id]?.let { items[id] = it.copy(isStarred = !it.isStarred) }
        }
        override suspend fun delete(id: String) { items.remove(id) }
        override suspend fun search(query: String, limit: Int): List<Note> =
            items.values.filter { it.title.contains(query, ignoreCase = true) }.take(limit)
        override suspend fun getByCategory(category: String): List<Note> =
            items.values.filter { it.category == category }
    }

    private class FakeTodoRepository : TodoRepository {
        val items = mutableMapOf<String, TodoTask>()
        override fun observeAll(): Flow<List<TodoTask>> = MutableStateFlow(items.values.toList())
        override fun observeOverdue(): Flow<List<TodoTask>> = MutableStateFlow(emptyList())
        override suspend fun get(id: String): TodoTask? = items[id]
        override suspend fun create(title: String, body: String, priority: TaskPriority, dueDateMs: Long?, reminderText: String?, tags: List<String>): TodoTask {
            val task = TodoTask(id = "todo-${items.size + 1}", title = title, body = body, priority = priority, dueDateMs = dueDateMs, tags = tags)
            items[task.id] = task
            return task
        }
        override suspend fun complete(id: String) {
            items[id]?.let { items[id] = it.copy(done = true) }
        }
        override suspend fun uncomplete(id: String) {
            items[id]?.let { items[id] = it.copy(done = false) }
        }
        override suspend fun delete(id: String) { items.remove(id) }
        override suspend fun search(query: String, limit: Int): List<TodoTask> =
            items.values.filter { it.title.contains(query, ignoreCase = true) }.take(limit)
        override suspend fun getByTag(tag: String): List<TodoTask> = items.values.filter { tag in it.tags }
        override suspend fun reschedule(id: String, dueDateMs: Long?) {
            items[id]?.let { items[id] = it.copy(dueDateMs = dueDateMs) }
        }
        override suspend fun setPriority(id: String, priority: TaskPriority) {
            items[id]?.let { items[id] = it.copy(priority = priority) }
        }
        override suspend fun countPending(): Int = items.values.count { !it.done }
        override suspend fun countOverdue(): Int = 0
    }

    private class FakeBookmarkRepository : BookmarkRepository {
        val items = mutableMapOf<String, Bookmark>()
        override fun observeAll(): Flow<List<Bookmark>> = MutableStateFlow(items.values.toList())
        override suspend fun get(id: String): Bookmark? = items[id]
        override suspend fun create(url: String, title: String, note: String, tags: List<String>): Bookmark {
            val bookmark = Bookmark(id = "bm-${items.size + 1}", url = url, title = title, note = note, tags = tags)
            items[bookmark.id] = bookmark
            return bookmark
        }
        override suspend fun update(id: String, title: String?, note: String?, tags: List<String>?) {
            val current = items[id] ?: return
            items[id] = current.copy(title = title ?: current.title, note = note ?: current.note, tags = tags ?: current.tags)
        }
        override suspend fun delete(id: String) { items.remove(id) }
        override suspend fun search(query: String, limit: Int): List<Bookmark> =
            items.values.filter { it.url.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }.take(limit)
        override suspend fun getByTag(tag: String): List<Bookmark> = items.values.filter { tag in it.tags }
    }

    private fun newHost(
        notes: FakeNotesRepository = FakeNotesRepository(),
        todos: FakeTodoRepository = FakeTodoRepository(),
        bookmarks: FakeBookmarkRepository = FakeBookmarkRepository(),
    ) = Triple(notes, todos, bookmarks) to ScriptPluginHostImpl(notes, todos, bookmarks, OkHttpClient())

    @Test
    fun `writeData create then readData notes round-trips`() {
        val (_, host) = newHost()

        val createResult = host.writeData("p1", "notes", """{"action":"create","title":"Hello","content":"World","tags":["a","b"]}""")
        val id = Json.parseToJsonElement(createResult).jsonObject["id"]!!.jsonPrimitive.content

        val listed = Json.parseToJsonElement(host.readData("p1", "notes", "")).jsonArray
        assertEquals(1, listed.size)
        assertEquals(id, listed[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Hello", listed[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(2, listed[0].jsonObject["tags"]!!.jsonArray.size)
    }

    @Test
    fun `writeData todos complete flips done`() {
        val (repos, host) = newHost()
        val (_, todos, _) = repos

        val id = Json.parseToJsonElement(
            host.writeData("p1", "todos", """{"action":"create","title":"Buy milk"}"""),
        ).jsonObject["id"]!!.jsonPrimitive.content

        assertFalse(todos.items.getValue(id).done)
        host.writeData("p1", "todos", """{"action":"complete","id":"$id"}""")
        assertTrue(todos.items.getValue(id).done)
    }

    @Test
    fun `writeData bookmarks delete removes entry`() {
        val (repos, host) = newHost()
        val (_, _, bookmarks) = repos

        val id = Json.parseToJsonElement(
            host.writeData("p1", "bookmarks", """{"action":"create","url":"https://example.com"}"""),
        ).jsonObject["id"]!!.jsonPrimitive.content

        assertTrue(id in bookmarks.items)
        host.writeData("p1", "bookmarks", """{"action":"delete","id":"$id"}""")
        assertFalse(id in bookmarks.items)
    }

    @Test
    fun `readData rejects unknown collection`() {
        val (_, host) = newHost()
        assertThrows(IllegalArgumentException::class.java) {
            host.readData("p1", "secrets", "")
        }
    }

    @Test
    fun `writeData rejects payload missing action`() {
        val (_, host) = newHost()
        assertThrows(IllegalArgumentException::class.java) {
            host.writeData("p1", "notes", """{"title":"no action field"}""")
        }
    }

    @Test
    fun `writeData rejects unknown action for collection`() {
        val (_, host) = newHost()
        assertThrows(IllegalArgumentException::class.java) {
            host.writeData("p1", "notes", """{"action":"nuke"}""")
        }
    }

    @Test
    fun `httpGet rejects non-http scheme`() {
        val (_, host) = newHost()
        assertThrows(IllegalArgumentException::class.java) {
            host.httpGet("p1", "ftp://example.com")
        }
    }
}
