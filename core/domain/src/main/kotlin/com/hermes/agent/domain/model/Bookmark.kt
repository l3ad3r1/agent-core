package com.hermes.agent.domain.model

data class Bookmark(
    val id: String,
    val url: String,
    val title: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)
