package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "bookmarks",
    // Declared here, not only in the migration: Room validates an upgraded
    // database against the entities, so an index that exists in one and not the
    // other fails the upgrade with "Migration didn't properly handle".
    indices = [Index(value = ["url"])],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val note: String,
    val tagsJson: String,
    val createdAt: Long,
) {
    fun toDomain() = com.hermes.agent.domain.model.Bookmark(
        id = id,
        url = url,
        title = title,
        note = note,
        tags = runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(bookmark: com.hermes.agent.domain.model.Bookmark) = BookmarkEntity(
            id = bookmark.id,
            url = bookmark.url,
            title = bookmark.title,
            note = bookmark.note,
            tagsJson = Json.encodeToString(bookmark.tags),
            createdAt = bookmark.createdAt,
        )
    }
}
