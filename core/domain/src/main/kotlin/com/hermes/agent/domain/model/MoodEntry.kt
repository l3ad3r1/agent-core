package com.hermes.agent.domain.model

data class MoodEntry(
    val id: String,
    val dateMs: Long,
    val mood: MoodLevel = MoodLevel.MID,
    val intensity: Int = 5,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

enum class MoodLevel {
    VERY_BAD,
    BAD,
    MID,
    GOOD,
    VERY_GOOD;

    companion object {
        fun fromName(name: String): MoodLevel =
            entries.firstOrNull { it.name == name } ?: MID
    }
}
