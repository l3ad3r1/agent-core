package com.hermes.agent.data.tools

import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.skill.SkillGuard
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home Assistant tool for smart home integration via REST API.
 *
 * Exposes 4 actions behind a single tool descriptor:
 *  - list_entities: filter entities by domain (light, switch, climate, sensor) or area
 *  - get_state: fetch detailed state and attributes of a single entity_id
 *  - list_services: list available services per domain
 *  - call_service: execute a service on target entity with optional parameters
 */
@Singleton
class HomeAssistantTool @Inject constructor(
    private val settingsRepository: SettingsRepository,
    okHttpClient: OkHttpClient,
) : Tool {

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }

    override val descriptor = ToolDescriptor(
        name = "home_assistant",
        description = "Control smart home devices, inspect entity states, and list available services via Home Assistant. " +
            "Actions: 'list_entities' (filter by domain or area), 'get_state' (read state by entity_id), " +
            "'list_services' (discover available actions per domain), 'call_service' (turn on/off, adjust climate, etc.).",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The action to perform: 'list_entities', 'get_state', 'list_services', 'call_service'.",
                required = true,
            ),
            ToolParameter(
                name = "domain",
                type = ToolParameterType.STRING,
                description = "Entity or service domain to filter by (e.g. 'light', 'switch', 'climate', 'sensor', 'cover', 'media_player').",
                required = false,
            ),
            ToolParameter(
                name = "area",
                type = ToolParameterType.STRING,
                description = "Area or room name to filter by (e.g. 'living room', 'kitchen', 'bedroom') for list_entities.",
                required = false,
            ),
            ToolParameter(
                name = "entity_id",
                type = ToolParameterType.STRING,
                description = "Target entity ID (e.g. 'light.living_room', 'climate.thermostat') for get_state or call_service.",
                required = false,
            ),
            ToolParameter(
                name = "service",
                type = ToolParameterType.STRING,
                description = "Service name (e.g. 'turn_on', 'turn_off', 'toggle', 'set_temperature') for call_service.",
                required = false,
            ),
            ToolParameter(
                name = "data",
                type = ToolParameterType.STRING,
                description = "Additional service parameters as JSON object string (e.g. '{\"brightness\": 255}' or '{\"temperature\": 22}').",
                required = false,
            ),
        ),
        category = "automation",
        capabilities = setOf("home_assistant", "device_control", "automation"),
        requiresConfirmation = true,
        maxResultSizeChars = 8192,
    )

    companion object {
        private val ENTITY_ID_RE = Regex("^[a-z_][a-z0-9_]*\\.[a-z0-9_]+$")
        private val SERVICE_NAME_RE = Regex("^[a-z][a-z0-9_]*$")

        private val BLOCKED_DOMAINS = setOf(
            "shell_command",
            "command_line",
            "python_script",
            "pyscript",
            "hassio",
            "rest_command",
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val action = arguments["action"]?.str()?.lowercase()?.trim()
            ?: return@withContext ToolResult.error("Missing required parameter: 'action'", System.currentTimeMillis() - start)

        val settings = settingsRepository.current()
        val baseUrl = settings.homeAssistantUrl.trim().removeSuffix("/")
        val token = settings.homeAssistantToken.trim()

        if (baseUrl.isBlank() || token.isBlank()) {
            return@withContext ToolResult.error(
                "Home Assistant is not configured. Please set the host URL and Long-Lived Access Token in Settings -> Connections.",
                System.currentTimeMillis() - start,
            )
        }

        try {
            when (action) {
                "list_entities" -> handleListEntities(baseUrl, token, arguments, start)
                "get_state" -> handleGetState(baseUrl, token, arguments, start)
                "list_services" -> handleListServices(baseUrl, token, arguments, start)
                "call_service" -> handleCallService(baseUrl, token, arguments, start)
                else -> ToolResult.error(
                    "Unknown action '$action'. Expected 'list_entities', 'get_state', 'list_services', or 'call_service'.",
                    System.currentTimeMillis() - start,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "HomeAssistantTool failed on action $action")
            ToolResult.error("Home Assistant request failed: ${e.message ?: e.javaClass.simpleName}", System.currentTimeMillis() - start)
        }
    }

    private fun handleListEntities(
        baseUrl: String,
        token: String,
        arguments: Map<String, JsonElement>,
        start: Long,
    ): ToolResult {
        val domainFilter = arguments["domain"]?.str()?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
        val areaFilter = arguments["area"]?.str()?.lowercase()?.trim()?.takeIf { it.isNotBlank() }

        val request = Request.Builder()
            .url("$baseUrl/api/states")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .get()
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ToolResult.error("Failed to list entities: HTTP ${response.code} ${response.message}", System.currentTimeMillis() - start)
            }
            response.body?.string() ?: return ToolResult.error("Empty response from Home Assistant", System.currentTimeMillis() - start)
        }

        val jsonArray = runCatching { json.parseToJsonElement(responseBody).jsonArray }.getOrNull()
            ?: return ToolResult.error("Invalid JSON response from Home Assistant", System.currentTimeMillis() - start)

        val entities = mutableListOf<Map<String, String>>()
        for (element in jsonArray) {
            val obj = element.jsonObject
            val entityId = obj["entity_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val attributes = obj["attributes"]?.jsonObject
            val friendlyNameRaw = attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val areaRaw = attributes?.get("area")?.jsonPrimitive?.contentOrNull.orEmpty()

            // Filter by domain
            if (domainFilter != null && !entityId.startsWith("$domainFilter.")) {
                continue
            }

            // Filter by area
            if (areaFilter != null) {
                val matchesArea = friendlyNameRaw.lowercase().contains(areaFilter) || areaRaw.lowercase().contains(areaFilter)
                if (!matchesArea) continue
            }

            // Untrusted data defense: vet friendly_name with SkillGuard
            val screenedName = if (SkillGuard.vet(friendlyNameRaw).ok) friendlyNameRaw else "[unverified name]"

            entities += mapOf(
                "entity_id" to entityId,
                "state" to state,
                "friendly_name" to screenedName,
            )
        }

        val output = buildString {
            appendLine("Found ${entities.size} Home Assistant entities:")
            entities.forEach { e ->
                appendLine("• ${e["entity_id"]} [${e["state"]}] - ${e["friendly_name"]}")
            }
        }.trimEnd()

        return ToolResult.ok(output, System.currentTimeMillis() - start)
    }

    private fun handleGetState(
        baseUrl: String,
        token: String,
        arguments: Map<String, JsonElement>,
        start: Long,
    ): ToolResult {
        val entityId = arguments["entity_id"]?.str()?.trim().orEmpty()
        if (entityId.isBlank()) {
            return ToolResult.error("Missing required parameter: 'entity_id'", System.currentTimeMillis() - start)
        }
        if (!ENTITY_ID_RE.matches(entityId)) {
            return ToolResult.error("Invalid entity_id format: '$entityId'. Expected 'domain.entity_name'.", System.currentTimeMillis() - start)
        }

        val request = Request.Builder()
            .url("$baseUrl/api/states/$entityId")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .get()
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return ToolResult.error("Entity '$entityId' not found.", System.currentTimeMillis() - start)
            }
            if (!response.isSuccessful) {
                return ToolResult.error("Failed to get state for '$entityId': HTTP ${response.code} ${response.message}", System.currentTimeMillis() - start)
            }
            response.body?.string() ?: return ToolResult.error("Empty response from Home Assistant", System.currentTimeMillis() - start)
        }

        val obj = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
            ?: return ToolResult.error("Invalid JSON response from Home Assistant", System.currentTimeMillis() - start)

        val state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attributes = obj["attributes"]?.jsonObject?.toString().orEmpty()
        val lastChanged = obj["last_changed"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val lastUpdated = obj["last_updated"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val output = buildString {
            appendLine("Entity: $entityId")
            appendLine("State: $state")
            if (lastChanged.isNotBlank()) appendLine("Last Changed: $lastChanged")
            if (lastUpdated.isNotBlank()) appendLine("Last Updated: $lastUpdated")
            if (attributes.isNotBlank()) appendLine("Attributes: $attributes")
        }.trimEnd()

        return ToolResult.ok(output, System.currentTimeMillis() - start)
    }

    private fun handleListServices(
        baseUrl: String,
        token: String,
        arguments: Map<String, JsonElement>,
        start: Long,
    ): ToolResult {
        val domainFilter = arguments["domain"]?.str()?.lowercase()?.trim()?.takeIf { it.isNotBlank() }

        val request = Request.Builder()
            .url("$baseUrl/api/services")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .get()
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ToolResult.error("Failed to list services: HTTP ${response.code} ${response.message}", System.currentTimeMillis() - start)
            }
            response.body?.string() ?: return ToolResult.error("Empty response from Home Assistant", System.currentTimeMillis() - start)
        }

        val jsonArray = runCatching { json.parseToJsonElement(responseBody).jsonArray }.getOrNull()
            ?: return ToolResult.error("Invalid JSON response from Home Assistant", System.currentTimeMillis() - start)

        val output = buildString {
            appendLine("Available Home Assistant services:")
            for (element in jsonArray) {
                val obj = element.jsonObject
                val domain = obj["domain"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (domainFilter != null && domain != domainFilter) continue
                if (domain in BLOCKED_DOMAINS) continue

                val servicesObj = obj["services"]?.jsonObject ?: continue
                appendLine("\n[$domain]")
                for ((svcName, svcVal) in servicesObj) {
                    val desc = svcVal.jsonObject["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val screenedDesc = if (SkillGuard.vet(desc).ok) desc else ""
                    appendLine("  • $svcName: $screenedDesc")
                }
            }
        }.trimEnd()

        return ToolResult.ok(output, System.currentTimeMillis() - start)
    }

    private fun handleCallService(
        baseUrl: String,
        token: String,
        arguments: Map<String, JsonElement>,
        start: Long,
    ): ToolResult {
        val domain = arguments["domain"]?.str()?.lowercase()?.trim().orEmpty()
        val service = arguments["service"]?.str()?.lowercase()?.trim().orEmpty()
        val entityId = arguments["entity_id"]?.str()?.trim()?.takeIf { it.isNotBlank() }
        val dataString = arguments["data"]?.str()?.trim()

        if (domain.isBlank() || service.isBlank()) {
            return ToolResult.error("Missing required parameters: 'domain' and 'service'", System.currentTimeMillis() - start)
        }

        // Validate domain and service formats BEFORE checking blocked domains (prevents path traversal / bypass)
        if (!SERVICE_NAME_RE.matches(domain)) {
            return ToolResult.error("Invalid domain format: '$domain'. Only lowercase letters, digits, and underscores allowed.", System.currentTimeMillis() - start)
        }
        if (!SERVICE_NAME_RE.matches(service)) {
            return ToolResult.error("Invalid service format: '$service'. Only lowercase letters, digits, and underscores allowed.", System.currentTimeMillis() - start)
        }

        if (domain in BLOCKED_DOMAINS) {
            return ToolResult.error(
                "Service domain '$domain' is blocked for security. Blocked domains: ${BLOCKED_DOMAINS.sorted().joinToString(", ")}",
                System.currentTimeMillis() - start,
            )
        }

        if (entityId != null && !ENTITY_ID_RE.matches(entityId)) {
            return ToolResult.error("Invalid entity_id format: '$entityId'. Expected 'domain.entity_name'.", System.currentTimeMillis() - start)
        }

        val payloadMap = mutableMapOf<String, JsonElement>()
        if (!dataString.isNullOrBlank()) {
            try {
                val parsed = json.parseToJsonElement(dataString).jsonObject
                payloadMap.putAll(parsed)
            } catch (e: Exception) {
                return ToolResult.error("Invalid JSON in 'data' parameter: ${e.message}", System.currentTimeMillis() - start)
            }
        }
        if (entityId != null) {
            payloadMap["entity_id"] = JsonPrimitive(entityId)
        }

        val requestBody = JsonObject(payloadMap).toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/api/services/$domain/$service")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ToolResult.error("Failed to call service $domain.$service: HTTP ${response.code} ${response.message}", System.currentTimeMillis() - start)
            }
            response.body?.string() ?: ""
        }

        return ToolResult.ok(
            "Service $domain.$service called successfully${if (entityId != null) " on $entityId" else ""}. Result: $responseBody",
            System.currentTimeMillis() - start,
        )
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HomeAssistantToolModule {
    @Binds
    @IntoSet
    abstract fun bindHomeAssistantTool(tool: HomeAssistantTool): Tool
}
