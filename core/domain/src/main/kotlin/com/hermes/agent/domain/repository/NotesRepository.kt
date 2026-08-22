package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Note
import kotlinx.coroutines.flow.Flow

interface NotesRepository {
    fun observeAll(): Flow<List<Note>>
    suspend fun get(id: String): Note?
    suspend fun create(title: String, content: String = "", tags: List<String> = emptyList(), category: String = "general", folder: String? = null): Note
    suspend fun update(id: String, title: String? = null, content: String? = null, tags: List<String>? = null, starred: Boolean? = null)
    suspend fun toggleStar(id: String)
    suspend fun delete(id: String)
    suspend fun search(query: String, limit: Int = 20): List<Note>
    suspend fun getByCategory(category: String): List<Note>
}
