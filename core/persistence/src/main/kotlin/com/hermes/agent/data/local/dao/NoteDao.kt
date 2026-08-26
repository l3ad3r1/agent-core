package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NoteEntity?

    @Upsert
    suspend fun upsert(entity: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        SELECT * FROM notes
        WHERE title LIKE '%' || :query || '%'
           OR content LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE category = :category ORDER BY updatedAt DESC")
    suspend fun getByCategory(category: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE isStarred = 1 ORDER BY updatedAt DESC")
    fun observeStarred(): Flow<List<NoteEntity>>
}
