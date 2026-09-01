package com.hermes.agent.domain.model

import kotlinx.serialization.Serializable

/**
 * Model representing a persistent background proactive standing order.
 * Ported from OpenClaw standing orders / heartbeat automation specification.
 */
@Serializable
data class StandingOrder(
    val id: String,
    val title: String,
    val instruction: String,
    val enabled: Boolean = true,
    val lastExecutedAt: Long? = null,
    val lastResult: String? = null,
    val intervalMinutes: Int = 60,
    val createdAt: Long = System.currentTimeMillis(),
)
