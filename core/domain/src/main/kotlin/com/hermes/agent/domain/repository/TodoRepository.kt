package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.TaskPriority
import com.hermes.agent.domain.model.TodoTask
import kotlinx.coroutines.flow.Flow

interface TodoRepository {
    fun observeAll(): Flow<List<TodoTask>>
    fun observeOverdue(): Flow<List<TodoTask>>
    suspend fun get(id: String): TodoTask?
    suspend fun create(title: String, body: String = "", priority: TaskPriority = TaskPriority.MEDIUM, dueDateMs: Long? = null, reminderText: String? = null, tags: List<String> = emptyList()): TodoTask
    suspend fun complete(id: String)
    suspend fun uncomplete(id: String)
    suspend fun delete(id: String)
    suspend fun search(query: String, limit: Int = 20): List<TodoTask>
    suspend fun getByTag(tag: String): List<TodoTask>
    suspend fun reschedule(id: String, dueDateMs: Long?)
    suspend fun setPriority(id: String, priority: TaskPriority)
    suspend fun countPending(): Int
    suspend fun countOverdue(): Int
}
