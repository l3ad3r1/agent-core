package com.hermes.agent.data.tools

import com.hermes.agent.data.repository.UsageInsightsRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.domain.usage.UsageTimeWindow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool for retrieving token consumption, estimated USD expenses, and tool usage statistics.
 * Ported from upstream `usage_pricing.py`, `insights.py`, and `billing_view.py`.
 */
@Singleton
class UsageInsightsTool @Inject constructor(
    private val usageInsightsRepository: UsageInsightsRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "usage_insights",
        description = "Retrieve token usage analytics, estimated USD API billing expenses, and tool invocation distribution. " +
            "Specify 'window' as 'today', '7d' (last 7 days), '30d' (last 30 days), or 'all' (all time).",
        parameters = listOf(
            ToolParameter(
                name = "window",
                type = ToolParameterType.STRING,
                description = "Time window for aggregation: 'today', '7d', '30d', or 'all' (default: 'all').",
                required = false,
                enumValues = listOf("today", "7d", "30d", "all"),
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val windowArg = (arguments["window"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "all"
        val window = when (windowArg) {
            "today" -> UsageTimeWindow.TODAY
            "7d", "week" -> UsageTimeWindow.LAST_7_DAYS
            "30d", "month" -> UsageTimeWindow.LAST_30_DAYS
            else -> UsageTimeWindow.ALL_TIME
        }

        val summary = usageInsightsRepository.getUsageSummary(window)
        val nf = NumberFormat.getIntegerInstance(Locale.US)
        val cf = NumberFormat.getCurrencyInstance(Locale.US).apply { maximumFractionDigits = 4 }

        val output = buildString {
            appendLine("# 📊 Usage & Cost Insights (${summary.window.uppercase()})")
            appendLine("- **Active Sessions:** ${summary.totalSessions}")
            appendLine("- **Total Messages:** ${summary.totalMessages}")
            appendLine("- **Total Tokens:** ${nf.format(summary.totalTokens)} (${nf.format(summary.promptTokens)} prompt + ${nf.format(summary.completionTokens)} completion)")
            appendLine("- **Estimated USD Cost:** ${cf.format(summary.estimatedCostUsd)}")

            if (summary.modelBreakdowns.isNotEmpty()) {
                appendLine("\n### 🤖 Model Breakdown")
                for (m in summary.modelBreakdowns) {
                    appendLine("- **${m.model}** (${m.provider}): ${nf.format(m.totalTokens)} tokens (${nf.format(m.totalRequests)} requests) → ${cf.format(m.estimatedCostUsd)}")
                }
            }

            if (summary.toolBreakdowns.isNotEmpty()) {
                appendLine("\n### 🛠️ Top Tool Invocations")
                for (t in summary.toolBreakdowns.take(10)) {
                    val passRate = if (t.totalInvocations > 0) (t.successCount * 100 / t.totalInvocations) else 100
                    appendLine("- `${t.toolName}`: ${t.totalInvocations} calls (${passRate}% success)")
                }
            }
        }

        return ToolResult.ok(output.trim())
    }

    @dagger.Module
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    abstract class BindingsModule {
        @dagger.Binds
        @dagger.multibindings.IntoSet
        abstract fun bindTool(tool: UsageInsightsTool): Tool
    }
}
