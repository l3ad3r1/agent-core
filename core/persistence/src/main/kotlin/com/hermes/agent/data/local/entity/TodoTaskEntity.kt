package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "todo_tasks",
    // Declared here, not only in the migration: Room validates an upgraded
    // database against the entities, so an index that exists in one and not the
    // other fails the upgrade with "Migration didn't properly handle".
    indices = [Index(value = ["done"]), Index(value = ["dueDateMs"])],
)
data class TodoTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val done: Boolean,
    val priority: String,
    val tagsJson: String,
    val dueDateMs: Long?,
    val reminderText: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
) {
    fun toDomain() = com.hermes.agent.domain.model.TodoTask(
        id = id,
        title = title,
        body = body,
        done = done,
        priority = com.hermes.agent.domain.model.TaskPriority.fromName(priority),
        tags = runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
        dueDateMs = dueDateMs,
        reminderText = reminderText,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    companion object {
        fun fromDomain(task: com.hermes.agent.domain.model.TodoTask) = TodoTaskEntity(
            id = task.id,
            title = task.title,
            body = task.body,
            done = task.done,
            priority = task.priority.name,
            tagsJson = Json.encodeToString(task.tags),
            dueDateMs = task.dueDateMs,
            reminderText = task.reminderText,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt,
            completedAt = task.completedAt,
        )
    }
}
