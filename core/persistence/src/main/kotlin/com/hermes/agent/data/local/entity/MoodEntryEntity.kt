package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "mood_entries",
    // Declared here, not only in the migration: Room validates an upgraded
    // database against the entities, so an index that exists in one and not the
    // other fails the upgrade with "Migration didn't properly handle".
    indices = [Index(value = ["dateMs"])],
)
data class MoodEntryEntity(
    @PrimaryKey val id: String,
    val dateMs: Long,
    val mood: String,
    val intensity: Int,
    val note: String,
    val tagsJson: String,
    val createdAt: Long,
) {
    fun toDomain() = com.hermes.agent.domain.model.MoodEntry(
        id = id,
        dateMs = dateMs,
        mood = com.hermes.agent.domain.model.MoodLevel.fromName(mood),
        intensity = intensity,
        note = note,
        tags = runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(entry: com.hermes.agent.domain.model.MoodEntry) = MoodEntryEntity(
            id = entry.id,
            dateMs = entry.dateMs,
            mood = entry.mood.name,
            intensity = entry.intensity,
            note = entry.note,
            tagsJson = Json.encodeToString(entry.tags),
            createdAt = entry.createdAt,
        )
    }
}
