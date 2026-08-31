package com.hermes.agent.data.tools

import com.hermes.agent.data.repository.UsageInsightsRepository
import com.hermes.agent.domain.usage.ModelUsageBreakdown
import com.hermes.agent.domain.usage.ToolUsageBreakdown
import com.hermes.agent.domain.usage.UsageSummary
import com.hermes.agent.domain.usage.UsageTimeWindow
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageInsightsToolTest {

    private val repo = mockk<UsageInsightsRepository>()
    private val tool = UsageInsightsTool(repo)

    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { it.first to JsonPrimitive(it.second) }

    @Test
    fun `execute returns formatted usage summary`() = runTest {
        coEvery { repo.getUsageSummary(UsageTimeWindow.ALL_TIME) } returns UsageSummary(
            window = "all_time",
            totalSessions = 5,
            totalMessages = 42,
            totalTokens = 125000L,
            promptTokens = 100000L,
            completionTokens = 25000L,
            estimatedCostUsd = 0.625,
            modelBreakdowns = listOf(
                ModelUsageBreakdown("anthropic", "claude-3-5-sonnet", 20, 80000L, 20000L, 100000L, 0.54),
                ModelUsageBreakdown("local", "on-device-llama", 22, 20000L, 5000L, 25000L, 0.0),
            ),
            toolBreakdowns = listOf(
                ToolUsageBreakdown("read_file", 15, 15, 0),
                ToolUsageBreakdown("shell", 5, 4, 1),
            ),
        )

        val result = tool.execute(args("window" to "all"))

        assertTrue(result.success)
        val text = result.output.orEmpty()
        assertTrue(text.contains("Usage & Cost Insights"))
        assertTrue(text.contains("claude-3-5-sonnet"))
        assertTrue(text.contains("read_file"))
    }
}
