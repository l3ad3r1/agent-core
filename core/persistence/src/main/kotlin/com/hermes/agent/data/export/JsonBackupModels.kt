package com.hermes.agent.data.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A portable, human-readable snapshot of the user's own content.
 *
 * This is deliberately a different thing from the whole-database ZIP that
 * `LocalBackupManager` produces. That one is an exact binary image: it restores
 * a device to a known state, but it is opaque, replaces everything wholesale,
 * and only a build with a compatible schema can read it back. This format is
 * plain JSON, so it can be inspected, edited, diffed, kept in version control,
 * carried between Hermes and Jeeves, and merged into an install that already
 * has data.
 *
 * The user picks which sections travel; credentials are one of them and force
 * the file to be password-encrypted. Two categories are never included:
 *  - connector configuration — `connectors` holds third-party secrets in an
 *    opaque `configJson` blob that cannot be selectively sealed the way the
 *    known credential fields can;
 *  - derived data — document chunks and memory embeddings are large binary
 *    blobs that the app regenerates, so exporting them would bloat the file
 *    without making it any more useful;
 *  - runtime state — execution plans, agent tasks and the activity ledger
 *    describe a particular device's in-flight work, which means nothing on
 *    another install.
 */
@Serializable
data class JsonBackup(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Epoch millis, for display and for picking the newer of two files. */
    val exportedAt: Long = 0L,
    /** `hermes` or `jeeves` — recorded for provenance, never enforced on import. */
    val app: String = "",
    val appVersionCode: Int = 0,
    /**
     * Which sections were selected, by [BackupSection] name.
     *
     * Recorded so a restore can tell "this section was not backed up" from
     * "this section was backed up and was empty" — the two look identical once
     * an absent list decodes to the empty default.
     */
    val sections: List<String> = emptyList(),
    val notes: List<NoteBackup> = emptyList(),
    val todos: List<TodoBackup> = emptyList(),
    val bookmarks: List<BookmarkBackup> = emptyList(),
    val moods: List<MoodBackup> = emptyList(),
    val calendarEvents: List<CalendarEventBackup> = emptyList(),
    val kanbanTickets: List<KanbanTicketBackup> = emptyList(),
    val skills: List<SkillBackup> = emptyList(),
    val memories: List<MemoryBackup> = emptyList(),
    val scriptPlugins: List<ScriptPluginBackup> = emptyList(),
    /**
     * Cloud credentials, present only when explicitly selected.
     *
     * Held in the clear *within the file*, which is safe only because selecting
     * them forces the whole backup to be password-encrypted — see
     * [com.hermes.agent.data.security.BackupCipher]. Nothing writes this to an
     * unencrypted file.
     */
    val credentials: com.hermes.agent.domain.backup.CredentialsBackup? = null,
) {
    /** Total rows, for the confirmation the UI shows before importing. */
    val totalItems: Int
        get() = notes.size + todos.size + bookmarks.size + moods.size +
            calendarEvents.size + kanbanTickets.size + skills.size +
            memories.size + scriptPlugins.size

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * `isLenient` is off on purpose: a backup is a file a person may have
         * hand-edited, and silently accepting malformed JSON would import
         * half a file. Unknown keys *are* ignored so a file written by a newer
         * build still restores the sections this one understands.
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}

/**
 * The parts of a backup a user can tick.
 *
 * An enum rather than a pile of booleans so adding a section cannot silently
 * default to "not backed up" in one place and "backed up" in another.
 */
enum class BackupSection(val label: String) {
    NOTES("Notes"),
    TODOS("Tasks"),
    BOOKMARKS("Bookmarks"),
    MOODS("Mood log"),
    CALENDAR("Calendar events"),
    KANBAN("Board tickets"),
    SKILLS("Skills"),
    MEMORIES("Memories"),
    MODULES("Installed modules"),
    CREDENTIALS("Cloud API keys");

    companion object {
        /** Everything except credentials, which is always a deliberate choice. */
        val DEFAULT: Set<BackupSection> = entries.toSet() - CREDENTIALS
    }
}

/** How an import treats rows that already exist locally. */
enum class ImportMode {
    /** Keep what is here; only add ids that are missing. Never destructive. */
    SKIP_EXISTING,

    /** Let the file win for any id it contains. Existing rows are overwritten. */
    OVERWRITE_EXISTING,
}

/** What an import actually did, per section, so the UI can report it honestly. */
data class ImportReport(
    val added: Int = 0,
    val replaced: Int = 0,
    val skipped: Int = 0,
) {
    operator fun plus(other: ImportReport) = ImportReport(
        added + other.added,
        replaced + other.replaced,
        skipped + other.skipped,
    )
}

@Serializable
data class NoteBackup(
    val id: String,
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val category: String = "general",
    val starred: Boolean = false,
    val folder: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class TodoBackup(
    val id: String,
    val title: String,
    val body: String = "",
    val done: Boolean = false,
    val priority: String = "MEDIUM",
    val tags: List<String> = emptyList(),
    val dueDateMs: Long? = null,
    val reminderText: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
)

@Serializable
data class BookmarkBackup(
    val id: String,
    val url: String,
    val title: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
)

@Serializable
data class MoodBackup(
    val id: String,
    /** Free-text mood label, matching the entity — not an ordinal. */
    val mood: String = "",
    val intensity: Int = 0,
    val note: String = "",
    val dateMs: Long = 0L,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
)

@Serializable
data class CalendarEventBackup(
    val id: String,
    val title: String,
    val description: String = "",
    val sourceCalendar: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val allDay: Boolean = false,
    val location: String? = null,
    val reminderMinutes: Int = 0,
    val createdAt: Long = 0L,
)

@Serializable
data class KanbanTicketBackup(
    val id: String,
    val title: String,
    val body: String = "",
    val status: String = "",
    val assignee: String? = null,
    val createdBy: String = "",
    val priority: String = "",
    val tags: List<String> = emptyList(),
    val result: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
)

/**
 * A user-authored skill. Built-in skills are excluded at export time: they are
 * seeded by the app itself, so re-importing them would either duplicate what is
 * already there or overwrite a newer shipped version with a stale copy.
 */
@Serializable
data class SkillBackup(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val content: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val requiresTools: List<String> = emptyList(),
    val lifecycleState: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val sourceUrl: String? = null,
    val pinnedCommit: String? = null,
    val installedAt: Long? = null,
    val lintStatus: String? = null,
)

/**
 * A memory without its vector. [com.hermes.agent.data.local.entity.MemoryEntity.embedding]
 * is a large binary blob the app recomputes from [content], so carrying it
 * would multiply the file size for no portability gain — and an embedding is
 * only meaningful to the model that produced it.
 */
@Serializable
data class MemoryBackup(
    val id: String,
    val content: String,
    val createdAt: Long = 0L,
    val lastAccessedAt: Long = 0L,
    val accessCount: Int = 0,
)

/**
 * An installed script module, carried as the manifest exactly as fetched plus
 * the permissions that were approved for that snapshot — the same pairing the
 * `script_plugins` table keeps, so a restored module cannot come back with a
 * wider grant than the user originally agreed to.
 */
@Serializable
data class ScriptPluginBackup(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    @SerialName("manifest") val manifestJson: String,
    val grantedPermissions: String = "",
    val enabled: Boolean = true,
    val sourceUrl: String = "",
    val installedAt: Long = 0L,
)
