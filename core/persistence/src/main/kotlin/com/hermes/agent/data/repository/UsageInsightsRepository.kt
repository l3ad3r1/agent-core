package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ActivityLedgerDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.domain.usage.ModelUsageBreakdown
import com.hermes.agent.domain.usage.ToolUsageBreakdown
import com.hermes.agent.domain.usage.UsagePricingEngine
import com.hermes.agent.domain.usage.UsageSummary
import com.hermes.agent.domain.usage.UsageTimeWindow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageInsightsRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val activityLedgerDao: ActivityLedgerDao,
) {

    suspend fun getUsageSummary(window: UsageTimeWindow = UsageTimeWindow.ALL_TIME): UsageSummary {
        val now = System.currentTimeMillis()
        val cutoff = computeCutoffTimestamp(window, now)

        val messages = messageDao.getMessagesSince(cutoff)
        val sessionCount = messageDao.countActiveConversationsSince(cutoff)
        val ledgerEntries = activityLedgerDao.getEntriesSince(cutoff)

        var totalPromptTokens = 0L
        var totalCompletionTokens = 0L
        var totalCostUsd = 0.0

        val modelBuckets = mutableMapOf<String, MutableModelMetrics>()

        for (msg in messages) {
            val tokens = msg.tokens.toLong()
            val isAssistant = msg.role.equals("assistant", ignoreCase = true)
            val modelName = if (msg.isOnDevice) "on-device-llama" else "cloud-llm"

            val metrics = modelBuckets.getOrPut(modelName) {
                MutableModelMetrics(
                    provider = if (msg.isOnDevice) "local" else "cloud",
                    model = modelName,
                )
            }

            metrics.totalRequests++
            if (isAssistant) {
                metrics.completionTokens += tokens
                totalCompletionTokens += tokens
            } else {
                metrics.promptTokens += tokens
                totalPromptTokens += tokens
            }
        }

        val modelBreakdowns = modelBuckets.values.map { m ->
            val costEst = UsagePricingEngine.calculateCost(
                modelName = m.model,
                promptTokens = m.promptTokens,
                completionTokens = m.completionTokens,
                isOnDevice = m.provider == "local",
            )
            totalCostUsd += costEst.amountUsd

            ModelUsageBreakdown(
                provider = m.provider,
                model = m.model,
                totalRequests = m.totalRequests,
                promptTokens = m.promptTokens,
                completionTokens = m.completionTokens,
                totalTokens = m.promptTokens + m.completionTokens,
                estimatedCostUsd = costEst.amountUsd,
            )
        }

        val toolBuckets = mutableMapOf<String, MutableToolMetrics>()
        for (entry in ledgerEntries) {
            if (entry.kindName == "TOOL_CALL" || entry.title.isNotBlank()) {
                val toolName = entry.title.ifBlank { "unknown_tool" }
                val t = toolBuckets.getOrPut(toolName) {
                    MutableToolMetrics(toolName)
                }
                t.totalInvocations++
                if (entry.success) {
                    t.successCount++
                } else {
                    t.failureCount++
                }
            }
        }

        val toolBreakdowns = toolBuckets.values.map {
            ToolUsageBreakdown(
                toolName = it.toolName,
                totalInvocations = it.totalInvocations,
                successCount = it.successCount,
                failureCount = it.failureCount,
            )
        }.sortedByDescending { it.totalInvocations }

        return UsageSummary(
            window = window.name.lowercase(),
            totalSessions = sessionCount,
            totalMessages = messages.size,
            totalTokens = totalPromptTokens + totalCompletionTokens,
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            estimatedCostUsd = totalCostUsd,
            modelBreakdowns = modelBreakdowns,
            toolBreakdowns = toolBreakdowns,
            generatedAt = now,
        )
    }

    private fun computeCutoffTimestamp(window: UsageTimeWindow, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return when (window) {
            UsageTimeWindow.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            UsageTimeWindow.LAST_7_DAYS -> now - (7L * 24 * 60 * 60 * 1000)
            UsageTimeWindow.LAST_30_DAYS -> now - (30L * 24 * 60 * 60 * 1000)
            UsageTimeWindow.ALL_TIME -> 0L
        }
    }

    private class MutableModelMetrics(
        val provider: String,
        val model: String,
        var totalRequests: Int = 0,
        var promptTokens: Long = 0L,
        var completionTokens: Long = 0L,
    )

    private class MutableToolMetrics(
        val toolName: String,
        var totalInvocations: Int = 0,
        var successCount: Int = 0,
        var failureCount: Int = 0,
    )
}
