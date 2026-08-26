package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.TodoTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoTaskDao {
    @Query("SELECT * FROM todo_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TodoTaskEntity>>

    @Query("SELECT * FROM todo_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TodoTaskEntity?

    @Upsert
    suspend fun upsert(entity: TodoTaskEntity)

    @Query("DELETE FROM todo_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE todo_tasks SET done = :done, completedAt = CASE WHEN :done = 1 AND completedAt IS NULL THEN :updatedAt ELSE completedAt END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDone(id: String, done: Boolean, updatedAt: Long)

    @Query(
        """
        SELECT * FROM todo_tasks
        WHERE title LIKE '%' || :query || '%'
           OR body LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<TodoTaskEntity>

    @Query("SELECT * FROM todo_tasks WHERE ',' || tagsJson || ',' LIKE '%,\"' || :tag || '\",%' ORDER BY createdAt DESC")
    suspend fun getByTag(tag: String): List<TodoTaskEntity>

    @Query("SELECT * FROM todo_tasks WHERE done = 0 AND dueDateMs IS NOT NULL AND dueDateMs < :now ORDER BY dueDateMs ASC")
    fun observeOverdue(now: Long = System.currentTimeMillis()): Flow<List<TodoTaskEntity>>

    @Query("SELECT COUNT(*) FROM todo_tasks WHERE done = 0")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM todo_tasks WHERE done = 0 AND dueDateMs IS NOT NULL AND dueDateMs < :now")
    suspend fun countOverdue(now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE todo_tasks SET priority = :priority, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPriority(id: String, priority: String, updatedAt: Long)

    @Query("UPDATE todo_tasks SET dueDateMs = :dueDateMs, updatedAt = :updatedAt WHERE id = :id")
    suspend fun reschedule(id: String, dueDateMs: Long?, updatedAt: Long)
}
