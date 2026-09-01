package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.StandingOrder
import com.hermes.agent.domain.settings.SettingsRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage persistent standing orders evaluated during heartbeat turns.
 * Ported from OpenClaw heartbeat / standing orders specification.
 */
@Singleton
class StandingOrdersTool @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : Tool {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override val descriptor = ToolDescriptor(
        name = "standing_orders",
        description = "Manage persistent background standing orders (proactive instructions and monitoring tasks). " +
            "Actions: 'list' (view all standing orders), 'create' (create new standing order with title, instruction, interval_minutes), " +
            "'delete' (remove by id), 'toggle' (enable or disable by id).",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The action to perform: 'list', 'create', 'delete', or 'toggle'.",
                required = true,
                enumValues = listOf("list", "create", "delete", "toggle"),
            ),
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "Short descriptive title for the standing order (required for 'create').",
                required = false,
            ),
            ToolParameter(
                name = "instruction",
                type = ToolParameterType.STRING,
                description = "Detailed prompt or monitoring instructions for the agent (required for 'create').",
                required = false,
            ),
            ToolParameter(
                name = "interval_minutes",
                type = ToolParameterType.INTEGER,
                description = "Minimum minutes between evaluations (default 60).",
                required = false,
            ),
            ToolParameter(
                name = "id",
                type = ToolParameterType.STRING,
                description = "The ID of the standing order (required for 'delete' and 'toggle').",
                required = false,
            ),
        ),
        category = "automation",
        capabilities = setOf("automation", "scheduler", "productivity"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val action = arguments.string("action")?.lowercase()
            ?: return@withContext ToolResult.error("Missing required parameter 'action'", System.currentTimeMillis() - start)

        val settings = settingsRepository.current()
        val currentOrders = parseOrders(settings.standingOrdersJson)

        when (action) {
            "list" -> {
                if (currentOrders.isEmpty()) {
                    return@withContext ToolResult.ok("No standing orders configured.", System.currentTimeMillis() - start)
                }
                val formatted = json.encodeToString(ListSerializer(StandingOrder.serializer()), currentOrders)
                ToolResult.ok(formatted, System.currentTimeMillis() - start)
            }
            "create" -> {
                val title = arguments.string("title")
                val instruction = arguments.string("instruction")
                val interval = (arguments.int("interval_minutes") ?: 60).coerceIn(5, 1440)

                if (title.isNullOrBlank() || instruction.isNullOrBlank()) {
                    return@withContext ToolResult.error("Parameters 'title' and 'instruction' are required for 'create'", System.currentTimeMillis() - start)
                }

                val newOrder = StandingOrder(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    instruction = instruction,
                    enabled = true,
                    intervalMinutes = interval,
                    createdAt = System.currentTimeMillis(),
                )

                val updated = currentOrders + newOrder
                saveOrders(updated)
                ToolResult.ok("Standing order created successfully (id='${newOrder.id}', title='${newOrder.title}').", System.currentTimeMillis() - start)
            }
            "delete" -> {
                val id = arguments.string("id")
                    ?: return@withContext ToolResult.error("Parameter 'id' is required for 'delete'", System.currentTimeMillis() - start)
                val updated = currentOrders.filter { it.id != id }
                saveOrders(updated)
                ToolResult.ok("Standing order '$id' deleted.", System.currentTimeMillis() - start)
            }
            "toggle" -> {
                val id = arguments.string("id")
                    ?: return@withContext ToolResult.error("Parameter 'id' is required for 'toggle'", System.currentTimeMillis() - start)
                val updated = currentOrders.map {
                    if (it.id == id) it.copy(enabled = !it.enabled) else it
                }
                saveOrders(updated)
                val toggled = updated.find { it.id == id }
                ToolResult.ok("Standing order '$id' enabled=${toggled?.enabled}.", System.currentTimeMillis() - start)
            }
            else -> ToolResult.error("Unknown action '$action'", System.currentTimeMillis() - start)
        }
    }

    private fun parseOrders(raw: String): List<StandingOrder> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StandingOrder.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun saveOrders(orders: List<StandingOrder>) {
        val encoded = json.encodeToString(ListSerializer(StandingOrder.serializer()), orders)
        settingsRepository.setStandingOrdersJson(encoded)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StandingOrdersToolModule {
    @Binds
    @IntoSet
    abstract fun bindStandingOrdersTool(tool: StandingOrdersTool): Tool
}
