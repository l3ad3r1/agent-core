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
import com.hermes.agent.data.local.entity.BookmarkEntity
import com.hermes.agent.data.local.entity.CalendarEventEntity
import com.hermes.agent.data.local.entity.KanbanTicketEntity
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.local.entity.MoodEntryEntity
import com.hermes.agent.data.local.entity.NoteEntity
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.local.entity.SkillEntity
import com.hermes.agent.data.local.entity.TodoTaskEntity
import com.hermes.agent.domain.backup.BackupCipher
import com.hermes.agent.domain.backup.EncryptedBackup
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the portable [JsonBackup] format.
 *
 * Deliberately built on the DAOs rather than the repositories: an import has to
 * restore rows exactly as they were, ids and timestamps included, and the
 * repository `create` methods mint fresh ids and stamp "now" — which would turn
 * a restore into a pile of duplicates dated today.
 *
 * Nothing here touches the database file itself, so unlike the whole-database
 * ZIP restore this can merge into a live install and needs no app restart.
 */
@Singleton
class JsonBackupManager @Inject constructor(
    private val notes: NoteDao,
    private val todos: TodoTaskDao,
    private val bookmarks: BookmarkDao,
    private val moods: MoodEntryDao,
    private val calendar: CalendarEventDao,
    private val kanban: KanbanTicketDao,
    private val skills: SkillDao,
    private val memories: MemoryDao,
    private val scriptPlugins: ScriptPluginDao,
) {

    /**
     * Reads only the [sections] that were ticked. An unticked section is absent
     * from the file rather than present-but-empty, so a restore cannot mistake
     * "not backed up" for "backed up as nothing" and wipe the difference.
     */
    suspend fun export(
        app: String,
        appVersionCode: Int,
        sections: Set<BackupSection> = BackupSection.DEFAULT,
    ): JsonBackup {
        suspend fun <T> pick(section: BackupSection, read: suspend () -> List<T>): List<T> =
            if (section in sections) read() else emptyList()

        return JsonBackup(
            exportedAt = System.currentTimeMillis(),
            app = app,
            appVersionCode = appVersionCode,
            sections = sections.map { it.name }.sorted(),
            notes = pick(BackupSection.NOTES) { notes.observeAll().first().map { it.toBackup() } },
            todos = pick(BackupSection.TODOS) { todos.observeAll().first().map { it.toBackup() } },
            bookmarks = pick(BackupSection.BOOKMARKS) { bookmarks.observeAll().first().map { it.toBackup() } },
            moods = pick(BackupSection.MOODS) { moods.observeAll().first().map { it.toBackup() } },
            calendarEvents = pick(BackupSection.CALENDAR) { calendar.observeAll().first().map { it.toBackup() } },
            kanbanTickets = pick(BackupSection.KANBAN) { kanban.observeAll().first().map { it.toBackup() } },
            // Built-ins are shipped with the app, so backing them up would
            // either duplicate what a fresh install seeds or overwrite a newer
            // shipped version with a stale copy.
            skills = pick(BackupSection.SKILLS) {
                skills.getAll().filterNot { it.isBuiltIn }.map { it.toBackup() }
            },
            memories = pick(BackupSection.MEMORIES) { memories.observeAll().first().map { it.toBackup() } },
            scriptPlugins = pick(BackupSection.MODULES) { scriptPlugins.getAll().map { it.toBackup() } },
        )
    }

    /**
     * Serializes [backup], encrypting the whole file when a [password] is given.
     *
     * Refuses to write credentials without one: an unencrypted file with live
     * API keys in it is exactly the accident this guard exists to prevent, and
     * a check here covers every caller rather than trusting each screen.
     */
    fun encode(backup: JsonBackup, password: String? = null): String {
        require(backup.credentials == null || !password.isNullOrBlank()) {
            "A password is required to include cloud API keys in a backup."
        }
        val plain = JsonBackup.json.encodeToString(JsonBackup.serializer(), backup)
        if (password.isNullOrBlank()) return plain
        return EncryptedBackup.json.encodeToString(
            EncryptedBackup.serializer(),
            EncryptedBackup(payload = BackupCipher.encrypt(password, plain)),
        )
    }

    /**
     * Parses [text], failing loudly rather than salvaging what it can: a
     * partially-applied import is far harder to reason about than a refused one.
     */
    fun decode(text: String, password: String? = null): JsonBackup {
        val plain = if (EncryptedBackup.looksEncrypted(text)) {
            val envelope = EncryptedBackup.json.decodeFromString(EncryptedBackup.serializer(), text)
            require(!password.isNullOrBlank()) { "This backup is password-protected." }
            BackupCipher.decrypt(password, envelope.payload)
        } else {
            text
        }
        val backup = JsonBackup.json.decodeFromString(JsonBackup.serializer(), plain)
        require(backup.schemaVersion <= JsonBackup.SCHEMA_VERSION) {
            "This backup was written by a newer version of the app " +
                "(format ${backup.schemaVersion}, this build reads up to ${JsonBackup.SCHEMA_VERSION})."
        }
        return backup
    }

    suspend fun import(backup: JsonBackup, mode: ImportMode): ImportReport {
        var report = ImportReport()
        report += restore(backup.notes, mode, { notes.getById(it.id) != null }) { notes.upsert(it.toEntity()) }
        report += restore(backup.todos, mode, { todos.getById(it.id) != null }) { todos.upsert(it.toEntity()) }
        report += restore(backup.bookmarks, mode, { bookmarks.getById(it.id) != null }) { bookmarks.upsert(it.toEntity()) }
        report += restore(backup.moods, mode, { moods.getById(it.id) != null }) { moods.upsert(it.toEntity()) }
        report += restore(backup.calendarEvents, mode, { calendar.getById(it.id) != null }) { calendar.upsert(it.toEntity()) }
        report += restore(backup.kanbanTickets, mode, { kanban.getById(it.id) != null }) { kanban.upsert(it.toEntity()) }
        report += restore(backup.skills, mode, { skills.getById(it.id) != null }) { skills.upsert(it.toEntity()) }
        report += restore(backup.memories, mode, { memories.getById(it.id) != null }) { memories.upsert(it.toEntity()) }
        report += restore(backup.scriptPlugins, mode, { scriptPlugins.getById(it.id) != null }) {
            scriptPlugins.upsert(it.toEntity())
        }
        return report
    }

    /**
     * Applies one section. [exists] is checked per row rather than by loading
     * every id up front, so a large backup does not have to hold the whole
     * table in memory alongside itself.
     */
    private suspend fun <T> restore(
        items: List<T>,
        mode: ImportMode,
        exists: suspend (T) -> Boolean,
        write: suspend (T) -> Unit,
    ): ImportReport {
        var added = 0
        var replaced = 0
        var skipped = 0
        for (item in items) {
            val present = exists(item)
            when {
                !present -> {
                    write(item); added++
                }
                mode == ImportMode.OVERWRITE_EXISTING -> {
                    write(item); replaced++
                }
                else -> skipped++
            }
        }
        return ImportReport(added, replaced, skipped)
    }
}

// ── entity → backup ────────────────────────────────────────────────────────
//
// Tags live in the database as a JSON string. The backup carries them as a real
// array so the file reads naturally and can be hand-edited; a row whose stored
// JSON is malformed degrades to no tags rather than failing the whole export.

private fun tagsOf(tagsJson: String): List<String> =
    runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList())

private fun tagsJson(tags: List<String>): String = Json.encodeToString(tags)

private fun NoteEntity.toBackup() = NoteBackup(
    id = id, title = title, content = content, tags = tagsOf(tagsJson),
    category = category, starred = isStarred, folder = folder,
    createdAt = createdAt, updatedAt = updatedAt,
)

private fun TodoTaskEntity.toBackup() = TodoBackup(
    id = id, title = title, body = body, done = done, priority = priority,
    tags = tagsOf(tagsJson), dueDateMs = dueDateMs, reminderText = reminderText,
    createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt,
)

private fun BookmarkEntity.toBackup() = BookmarkBackup(
    id = id, url = url, title = title, note = note,
    tags = tagsOf(tagsJson), createdAt = createdAt,
)

private fun MoodEntryEntity.toBackup() = MoodBackup(
    id = id, mood = mood, intensity = intensity, note = note,
    dateMs = dateMs, tags = tagsOf(tagsJson), createdAt = createdAt,
)

private fun CalendarEventEntity.toBackup() = CalendarEventBackup(
    id = id, title = title, description = description, sourceCalendar = sourceCalendar,
    startMs = startMs, endMs = endMs, allDay = allDay, location = location,
    reminderMinutes = reminderMinutes, createdAt = createdAt,
)

private fun KanbanTicketEntity.toBackup() = KanbanTicketBackup(
    id = id, title = title, body = body, status = status, assignee = assignee,
    createdBy = createdBy, priority = priority, tags = tagsOf(tagsJson),
    result = result, createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt,
)

private fun SkillEntity.toBackup() = SkillBackup(
    id = id, name = name, description = description, version = version,
    content = content, category = category, tags = tagsOf(tagsJson),
    requiresTools = tagsOf(requiresToolsJson), lifecycleState = lifecycleState,
    createdAt = createdAt, updatedAt = updatedAt,
)

private fun MemoryEntity.toBackup() = MemoryBackup(
    id = id, content = content, createdAt = createdAt,
    lastAccessedAt = lastAccessedAt, accessCount = accessCount,
)

private fun ScriptPluginEntity.toBackup() = ScriptPluginBackup(
    id = id, name = name, version = version, author = author, description = description,
    manifestJson = manifestJson, grantedPermissions = grantedPermissions,
    enabled = enabled, sourceUrl = sourceUrl, installedAt = installedAt,
)

// ── backup → entity ────────────────────────────────────────────────────────

private fun NoteBackup.toEntity() = NoteEntity(
    id = id, title = title, content = content, tagsJson = tagsJson(tags),
    category = category, isStarred = starred, folder = folder,
    createdAt = createdAt, updatedAt = updatedAt,
)

private fun TodoBackup.toEntity() = TodoTaskEntity(
    id = id, title = title, body = body, done = done, priority = priority,
    tagsJson = tagsJson(tags), dueDateMs = dueDateMs, reminderText = reminderText,
    createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt,
)

private fun BookmarkBackup.toEntity() = BookmarkEntity(
    id = id, url = url, title = title, note = note,
    tagsJson = tagsJson(tags), createdAt = createdAt,
)

private fun MoodBackup.toEntity() = MoodEntryEntity(
    id = id, dateMs = dateMs, mood = mood, intensity = intensity,
    note = note, tagsJson = tagsJson(tags), createdAt = createdAt,
)

private fun CalendarEventBackup.toEntity() = CalendarEventEntity(
    id = id, title = title, description = description, sourceCalendar = sourceCalendar,
    startMs = startMs, endMs = endMs, allDay = allDay, location = location,
    reminderMinutes = reminderMinutes, createdAt = createdAt,
)

private fun KanbanTicketBackup.toEntity() = KanbanTicketEntity(
    id = id, title = title, body = body, status = status, assignee = assignee,
    createdBy = createdBy, priority = priority, tagsJson = tagsJson(tags),
    result = result, createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt,
)

private fun SkillBackup.toEntity() = SkillEntity(
    id = id, name = name, description = description, version = version,
    content = content, category = category, tagsJson = tagsJson(tags),
    // An imported skill is by definition not one this build shipped.
    isBuiltIn = false,
    createdAt = createdAt, updatedAt = updatedAt,
    requiresToolsJson = tagsJson(requiresTools),
)

private fun MemoryBackup.toEntity() = MemoryEntity(
    id = id, content = content, embedding = null, relevanceScore = 0f,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt, accessCount = accessCount,
)

private fun ScriptPluginBackup.toEntity() = ScriptPluginEntity(
    id = id, name = name, version = version, author = author, description = description,
    manifestJson = manifestJson, grantedPermissions = grantedPermissions,
    enabled = enabled, sourceUrl = sourceUrl, installedAt = installedAt,
)
