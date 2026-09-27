package com.hermes.agent.data.tools

import com.hermes.agent.data.tool.ToolResultStore
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Pages through a tool result that was too long to return in one piece; see [ToolResultStore]. */
@Singleton
class ReadToolResultTool @Inject constructor(
    private val store: ToolResultStore,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Read more of a long tool result. When a tool's output ends with " +
            "'The full output is saved as result_id ...', call this with that result_id and the offset it gives.",
        parameters = listOf(
            ToolParameter("result_id", ToolParameterType.STRING, "The result_id from the truncated output.", required = true),
            ToolParameter("offset", ToolParameterType.INTEGER, "Character offset to start from (from the footer).", required = false),
            ToolParameter("max_chars", ToolParameterType.INTEGER, "How much to read (default 8000, max 12000).", required = false),
        ),
        category = "system",
        // Every role holds "common": whichever agent got the long result can read the rest.
        capabilities = setOf("common"),
        maxResultSizeChars = MAX_SLICE + 400,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val id = (arguments["result_id"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult.error("missing required parameter: result_id")
        val stored = store.get(id)
            ?: return ToolResult.error(
                "Result '$id' is no longer available: only the ${ToolResultStore.MAX_ENTRIES} most recent long " +
                    "results are kept, and none survive an app restart. Run the original tool again.",
            )
        val text = stored.text
        val offset = (arguments["offset"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (offset >= text.length) {
            return ToolResult.error("offset $offset is past the end; result '$id' has ${text.length} characters.")
        }
        val max = (arguments["max_chars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.coerceIn(500, MAX_SLICE)
            ?: DEFAULT_SLICE
        val end = ToolResultStore.cutPoint(text, offset, max)
        return ToolResult.ok(text.substring(offset, end) + ToolResultStore.footer(id, offset, end, text.length))
    }

    companion object {
        const val NAME = "read_tool_result"
        private const val DEFAULT_SLICE = 8_000
        private const val MAX_SLICE = 12_000
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReadToolResultToolModule {
    @Binds
    @IntoSet
    abstract fun bindReadToolResultTool(tool: ReadToolResultTool): Tool
}
