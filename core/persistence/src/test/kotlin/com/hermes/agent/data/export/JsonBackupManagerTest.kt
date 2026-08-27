package com.hermes.agent.data.export

import com.hermes.agent.data.local.dao.BookmarkDao
import com.hermes.agent.data.local.dao.CalendarEventDao
import com.hermes.agent.data.local.dao.KanbanTicketDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MoodEntryDao
import com.hermes.agent.data.local.dao.NoteDao
import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.dao.TodoTaskDao
import com.hermes.agent.data.local.entity.NoteEntity
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.local.entity.SkillEntity
import com.hermes.agent.data.local.entity.TodoTaskEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.hermes.agent.domain.backup.CredentialsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonBackupManagerTest {

    /** In-memory stand-in for the tables the manager writes through. */
    private class Tables {
        val notes = mutableMapOf<String, NoteEntity>()
        val todos = mutableMapOf<String, TodoTaskEntity>()
        val skills = mutableMapOf<String, SkillEntity>()
        val plugins = mutableMapOf<String, ScriptPluginEntity>()
    }

    private fun manager(t: Tables): JsonBackupManager {
        val noteDao = mockk<NoteDao>(relaxed = true)
        coEvery { noteDao.observeAll() } answers { flowOf(t.notes.values.toList()) }
        coEvery { noteDao.getById(any()) } answers { t.notes[firstArg()] }
        coEvery { noteDao.upsert(any()) } answers { t.notes[firstArg<NoteEntity>().id] = firstArg() }

        val todoDao = mockk<TodoTaskDao>(relaxed = true)
        coEvery { todoDao.observeAll() } answers { flowOf(t.todos.values.toList()) }
        coEvery { todoDao.getById(any()) } answers { t.todos[firstArg()] }
        coEvery { todoDao.upsert(any()) } answers { t.todos[firstArg<TodoTaskEntity>().id] = firstArg() }

        val skillDao = mockk<SkillDao>(relaxed = true)
        coEvery { skillDao.getAll() } answers { t.skills.values.toList() }
        coEvery { skillDao.getById(any()) } answers { t.skills[firstArg()] }
        coEvery { skillDao.upsert(any()) } answers { t.skills[firstArg<SkillEntity>().id] = firstArg() }

        val pluginDao = mockk<ScriptPluginDao>(relaxed = true)
        coEvery { pluginDao.getAll() } answers { t.plugins.values.toList() }
        coEvery { pluginDao.getById(any()) } answers { t.plugins[firstArg()] }
        coEvery { pluginDao.upsert(any()) } answers { t.plugins[firstArg<ScriptPluginEntity>().id] = firstArg() }

        val empty = { m: Any -> m }
        val bookmarkDao = mockk<BookmarkDao>(relaxed = true)
        coEvery { bookmarkDao.observeAll() } returns flowOf(emptyList())
        val moodDao = mockk<MoodEntryDao>(relaxed = true)
        coEvery { moodDao.observeAll() } returns flowOf(emptyList())
        val calendarDao = mockk<CalendarEventDao>(relaxed = true)
        coEvery { calendarDao.observeAll() } returns flowOf(emptyList())
        val kanbanDao = mockk<KanbanTicketDao>(relaxed = true)
        coEvery { kanbanDao.observeAll() } returns flowOf(emptyList())
        val memoryDao = mockk<MemoryDao>(relaxed = true)
        coEvery { memoryDao.observeAll() } returns flowOf(emptyList())

        return JsonBackupManager(
            notes = noteDao, todos = todoDao, bookmarks = bookmarkDao, moods = moodDao,
            calendar = calendarDao, kanban = kanbanDao, skills = skillDao,
            memories = memoryDao, scriptPlugins = pluginDao,
        )
    }

    private fun note(id: String, title: String = "T", tags: String = """["a","b"]""") = NoteEntity(
        id = id, title = title, content = "body", tagsJson = tags, category = "work",
        isStarred = true, folder = "f", createdAt = 111L, updatedAt = 222L,
    )

    @Test
    fun `export then import reproduces rows exactly, ids and timestamps included`() = runTest {
        val source = Tables().apply { notes["n1"] = note("n1") }
        val text = manager(source).let { it.encode(it.export("jeeves", 60)) }

        val target = Tables()
        val report = manager(target).let { it.import(it.decode(text), ImportMode.SKIP_EXISTING) }

        assertEquals(1, report.added)
        val restored = target.notes.getValue("n1")
        // A restore that renamed ids or restamped dates would be a duplicate,
        // not a restore — this is the property that matters most.
        assertEquals(source.notes.getValue("n1"), restored)
    }

    @Test
    fun `tags survive the round trip as a real array`() = runTest {
        val source = Tables().apply { notes["n1"] = note("n1") }
        val backup = manager(source).export("jeeves", 60)
        assertEquals(listOf("a", "b"), backup.notes.single().tags)

        val text = manager(source).encode(backup)
        assertTrue("tags should be readable JSON, not an escaped string", text.contains("\"a\","))
    }

    @Test
    fun `malformed stored tags degrade to empty rather than failing the export`() = runTest {
        val source = Tables().apply { notes["n1"] = note("n1", tags = "not json") }
        val backup = manager(source).export("jeeves", 60)
        assertEquals(emptyList<String>(), backup.notes.single().tags)
    }

    @Test
    fun `skip existing leaves local rows untouched`() = runTest {
        val source = Tables().apply { notes["n1"] = note("n1", title = "from file") }
        val text = manager(source).let { it.encode(it.export("jeeves", 60)) }

        val target = Tables().apply { notes["n1"] = note("n1", title = "local wins") }
        val report = manager(target).let { it.import(it.decode(text), ImportMode.SKIP_EXISTING) }

        assertEquals(1, report.skipped)
        assertEquals(0, report.replaced)
        assertEquals("local wins", target.notes.getValue("n1").title)
    }

    @Test
    fun `overwrite existing lets the file win`() = runTest {
        val source = Tables().apply { notes["n1"] = note("n1", title = "from file") }
        val text = manager(source).let { it.encode(it.export("jeeves", 60)) }

        val target = Tables().apply { notes["n1"] = note("n1", title = "local") }
        val report = manager(target).let { it.import(it.decode(text), ImportMode.OVERWRITE_EXISTING) }

        assertEquals(1, report.replaced)
        assertEquals(0, report.added)
        assertEquals("from file", target.notes.getValue("n1").title)
    }

    @Test
    fun `built-in skills are not exported`() = runTest {
        val source = Tables().apply {
            skills["mine"] = skill("mine", builtIn = false)
            skills["shipped"] = skill("shipped", builtIn = true)
        }
        val backup = manager(source).export("jeeves", 60)
        assertEquals(listOf("mine"), backup.skills.map { it.id })
    }

    @Test
    fun `an imported skill is never marked built-in`() = runTest {
        val source = Tables().apply { skills["mine"] = skill("mine", builtIn = false) }
        val text = manager(source).let { it.encode(it.export("jeeves", 60)) }

        val target = Tables()
        manager(target).let { it.import(it.decode(text), ImportMode.SKIP_EXISTING) }
        assertEquals(false, target.skills.getValue("mine").isBuiltIn)
    }

    @Test
    fun `a module keeps the permissions that were approved for its manifest`() = runTest {
        val source = Tables().apply {
            plugins["weather"] = ScriptPluginEntity(
                id = "weather", name = "Weather", version = "1.0.0",
                manifestJson = """{"id":"weather"}""", grantedPermissions = "network",
                enabled = true, sourceUrl = "https://example.com/m.json", installedAt = 5L,
            )
        }
        val text = manager(source).let { it.encode(it.export("jeeves", 60)) }

        val target = Tables()
        manager(target).let { it.import(it.decode(text), ImportMode.SKIP_EXISTING) }
        val restored = target.plugins.getValue("weather")
        assertEquals("network", restored.grantedPermissions)
        assertEquals("""{"id":"weather"}""", restored.manifestJson)
    }

    @Test
    fun `a backup from a newer format is refused rather than half-applied`() = runTest {
        val text = """{"schemaVersion": ${JsonBackup.SCHEMA_VERSION + 1}, "notes": []}"""
        val error = runCatching { manager(Tables()).decode(text) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("newer version"))
    }

    @Test
    fun `unknown sections from a newer build do not block the sections we understand`() = runTest {
        val text = """
            {"schemaVersion": 1, "somethingNew": [{"x": 1}],
             "notes": [{"id":"n1","title":"kept"}]}
        """.trimIndent()
        val target = Tables()
        val report = manager(target).let { it.import(it.decode(text), ImportMode.SKIP_EXISTING) }
        assertEquals(1, report.added)
        assertEquals("kept", target.notes.getValue("n1").title)
    }

    @Test
    fun `a truncated file is refused outright`() {
        val error = runCatching { manager(Tables()).decode("""{"schemaVersion": 1, "notes": [""") }
            .exceptionOrNull()
        assertNotNull("half a file must not import", error)
    }

    @Test
    fun `memories export without their embedding blob`() = runTest {
        val backup = manager(Tables()).export("jeeves", 60)
        // Nothing seeded, but the contract is what matters: the DTO has no
        // embedding field at all, so no vector can leak into a portable file.
        assertNull(backup.memories.firstOrNull())
        assertTrue(
            MemoryBackup::class.java.declaredFields.none { it.name.contains("embedding") },
        )
    }

    private fun skill(id: String, builtIn: Boolean) = SkillEntity(
        id = id, name = id, description = "d", version = "1", content = "c",
        category = "cat", tagsJson = "[]", isBuiltIn = builtIn,
        createdAt = 1L, updatedAt = 2L,
    )

    @Test
    fun `an unticked section is absent rather than exported empty`() = runTest {
        val source = Tables().apply { notes["n1"] = note("n1") }
        val backup = manager(source).export("jeeves", 60, setOf(BackupSection.TODOS))

        assertTrue("notes were not selected", backup.notes.isEmpty())
        // The marker is what lets a restore tell "not backed up" from
        // "backed up and empty".
        assertEquals(listOf("TODOS"), backup.sections)
    }

    @Test
    fun `credentials are refused without a password`() = runTest {
        val backup = manager(Tables()).export("jeeves", 60)
            .copy(credentials = CredentialsBackup(cloudApiKey = "sk-live"))
        val error = runCatching { manager(Tables()).encode(backup, password = null) }.exceptionOrNull()
        assertNotNull("an unencrypted file must never carry live keys", error)
        assertTrue(error!!.message.orEmpty().contains("password is required"))
    }

    @Test
    fun `a password encrypts the whole file, keys included`() = runTest {
        val m = manager(Tables().apply { notes["n1"] = note("n1", title = "private") })
        val backup = m.export("jeeves", 60)
            .copy(credentials = CredentialsBackup(cloudApiKey = "sk-live-secret"))

        val text = m.encode(backup, password = "hunter2")
        assertFalse("the key must not be readable in the file", text.contains("sk-live-secret"))
        assertFalse("nor the content", text.contains("private"))

        val restored = m.decode(text, password = "hunter2")
        assertEquals("sk-live-secret", restored.credentials?.cloudApiKey)
        assertEquals("private", restored.notes.single().title)
    }

    @Test
    fun `an encrypted file without the password is refused`() = runTest {
        val m = manager(Tables())
        val text = m.encode(m.export("jeeves", 60), password = "hunter2")
        assertNotNull(runCatching { m.decode(text) }.exceptionOrNull())
    }

    @Test
    fun `an unencrypted backup still opens with no password`() = runTest {
        val m = manager(Tables().apply { notes["n1"] = note("n1") })
        val text = m.encode(m.export("jeeves", 60))
        assertEquals(1, m.decode(text).notes.size)
    }
}
