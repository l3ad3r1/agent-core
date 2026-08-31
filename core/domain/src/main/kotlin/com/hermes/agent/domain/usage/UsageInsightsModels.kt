package com.hermes.agent.domain.usage

import kotlinx.serialization.Serializable

/**
 * Filter window for usage metrics aggregation.
 */
enum class UsageTimeWindow {
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    ALL_TIME,
}

@Serializable
data class ModelUsageBreakdown(
    val provider: String,
    val model: String,
    val totalRequests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val estimatedCostUsd: Double,
)

@Serializable
data class ToolUsageBreakdown(
    val toolName: String,
    val totalInvocations: Int,
    val successCount: Int,
    val failureCount: Int,
)

@Serializable
data class UsageSummary(
    val window: String,
    val totalSessions: Int,
    val totalMessages: Int,
    val totalTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsd: Double,
    val modelBreakdowns: List<ModelUsageBreakdown> = emptyList(),
    val toolBreakdowns: List<ToolUsageBreakdown> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis(),
)
