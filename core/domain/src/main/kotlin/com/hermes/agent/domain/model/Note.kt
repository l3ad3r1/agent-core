package com.hermes.agent.domain.model

data class Note(
    val id: String,
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val category: String = "general",
    val isStarred: Boolean = false,
    val folder: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
