package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookmarkEntity?

    @Upsert
    suspend fun upsert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE title LIKE '%' || :query || '%'
           OR url LIKE '%' || :query || '%'
           OR note LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE ',' || tagsJson || ',' LIKE '%,\"' || :tag || '\",%' ORDER BY createdAt DESC")
    suspend fun getByTag(tag: String): List<BookmarkEntity>

    @Query(
        """
        UPDATE bookmarks
        SET title = COALESCE(:title, title),
            note = COALESCE(:note, note),
            tagsJson = COALESCE(:tagsJson, tagsJson)
        WHERE id = :id
        """,
    )
    suspend fun update(id: String, title: String?, note: String?, tagsJson: String?)
}
