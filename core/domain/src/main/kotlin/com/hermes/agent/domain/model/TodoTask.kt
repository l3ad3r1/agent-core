package com.hermes.agent.domain.model

data class TodoTask(
    val id: String,
    val title: String,
    val body: String = "",
    val done: Boolean = false,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val tags: List<String> = emptyList(),
    val dueDateMs: Long? = null,
    val reminderText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    companion object {
        fun fromName(name: String): TaskPriority =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}
