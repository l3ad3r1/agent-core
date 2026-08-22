package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeAll(): Flow<List<Bookmark>>
    suspend fun get(id: String): Bookmark?
    suspend fun create(url: String, title: String = "", note: String = "", tags: List<String> = emptyList()): Bookmark
    suspend fun update(id: String, title: String? = null, note: String? = null, tags: List<String>? = null)
    suspend fun delete(id: String)
    suspend fun search(query: String, limit: Int = 20): List<Bookmark>
    suspend fun getByTag(tag: String): List<Bookmark>
}
