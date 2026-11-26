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
import kotlinx.serialization.json.JsonArray
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
 * Exposes these actions behind a single tool descriptor:
 *  - list_entities: filter entities by domain (light, switch, climate, sensor) or place
 *  - get_state: fetch detailed state and attributes of a single entity_id
 *  - list_services: list available services per domain
 *  - call_service: execute a service on an entity, or on every entity in a place
 *  - list_places: the areas, floors and labels the house is organised by
 *  - render_template: answer a question from live state with one Jinja render
 *  - assist: hand a sentence to Home Assistant's own Assist (conversation agent)
 *
 * A "place" is an area, floor or label, resolved through [HomeAssistantPlaces] - the same
 * device-aware lookup HA's Android companion app does with its registries.
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
            "Actions: 'list_places' (the areas, floors and labels - call this first to learn room names), " +
            "'list_entities' (filter by domain and/or place), 'get_state' (read state by entity_id), " +
            "'list_services' (discover available actions per domain), " +
            "'call_service' (turn on/off, adjust climate, etc. on an entity_id, or on a whole place via 'area'), " +
            "'render_template' (answer counts/summaries from live state with one Jinja template), " +
            "'assist' (pass a plain sentence to Home Assistant's own voice assistant).",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The action to perform: " + ACTIONS.joinToString(", ") { "'$it'" } + ".",
                required = true,
                // The tool rejects anything else; without the list a small model's action="turn_off"
                // looked well-formed and was accepted.
                enumValues = ACTIONS,
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
                description = "A place: area, floor or label name (e.g. 'living room', 'first floor', 'Hyderabad Flat'). " +
                    "Filters list_entities; for call_service it targets every matching entity in that place " +
                    "(combine with domain, e.g. domain='light' service='turn_off' area='terrace').",
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
            ToolParameter(
                name = "template",
                type = ToolParameterType.STRING,
                description = "Jinja2 template for render_template, e.g. " +
                    "\"{{ states.light | selectattr('state','eq','on') | list | count }}\".",
                required = false,
            ),
            ToolParameter(
                name = "text",
                type = ToolParameterType.STRING,
                description = "The sentence for assist, e.g. 'turn off the terrace lights'.",
                required = false,
            ),
        ),
        category = "automation",
        capabilities = setOf("home_assistant", "device_control", "automation"),
        requiresConfirmation = true,
        maxResultSizeChars = 8192,
        requiredByAction = mapOf(
            "get_state" to listOf("entity_id"),
            "call_service" to listOf("domain", "service"),
            "render_template" to listOf("template"),
            "assist" to listOf("text"),
        ),
    )

    companion object {
        val ACTIONS = listOf(
            "list_places", "list_entities", "get_state", "list_services", "call_service",
            "render_template", "assist",
        )

        /** Places change rarely; one render serves a whole conversation's worth of lookups. */
        private const val PLACES_TTL_MS = 60_000L
        private const val MAX_TEMPLATE_OUTPUT_CHARS = 4_000
        private const val MAX_TEMPLATE_INPUT_CHARS = 4_000
        private const val MAX_ASSIST_TEXT_CHARS = 500

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
                "list_places" -> handleListPlaces(baseUrl, token, start)
                "render_template" -> handleRenderTemplate(baseUrl, token, arguments, start)
                "assist" -> handleAssist(baseUrl, token, arguments, start)
                else -> ToolResult.error(
                    "Unknown action '$action'. Expected one of: ${ACTIONS.joinToString(", ")}.",
                    System.currentTimeMillis() - start,
                )
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
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

        // A real place lookup when the server can render the index; otherwise (an HA
        // older than labels/floors, or a failed render) the old name-substring match.
        var placeLabel: String? = null
        val placeEntities: Set<String>? = areaFilter?.let { query ->
            val places = loadPlaces(baseUrl, token) ?: return@let null
            when (val r = places.resolve(query)) {
                is HomeAssistantPlaces.Resolution.Found -> {
                    placeLabel = describe(r.place)
                    places.entitiesOf(r.place)
                }
                is HomeAssistantPlaces.Resolution.Ambiguous -> return ambiguous(query, r, start)
                HomeAssistantPlaces.Resolution.None -> return unknownPlace(query, places, start)
            }
        }

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

            // Filter by place
            if (placeEntities != null) {
                if (entityId !in placeEntities) continue
            } else if (areaFilter != null) {
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
            appendLine("Found ${entities.size} Home Assistant entities${placeLabel?.let { " in $it" }.orEmpty()}:")
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
        // Targeting a place hands the fan-out to HA itself: light.turn_off with an area_id
        // touches only that area's lights, devices included.
        var placeLabel: String? = null
        val areaQuery = arguments["area"]?.str()?.trim()?.takeIf { it.isNotBlank() }
        if (areaQuery != null && entityId == null) {
            val places = loadPlaces(baseUrl, token)
                ?: return ToolResult.error(
                    "Could not look up places on this Home Assistant; pass an entity_id instead.",
                    System.currentTimeMillis() - start,
                )
            when (val r = places.resolve(areaQuery)) {
                is HomeAssistantPlaces.Resolution.Found -> {
                    payloadMap[r.place.kind.targetKey] = JsonPrimitive(r.place.id)
                    placeLabel = describe(r.place)
                }
                is HomeAssistantPlaces.Resolution.Ambiguous -> return ambiguous(areaQuery, r, start)
                HomeAssistantPlaces.Resolution.None -> return unknownPlace(areaQuery, places, start)
            }
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
            "Service $domain.$service called successfully" +
                (entityId?.let { " on $it" } ?: placeLabel?.let { " on $it" }).orEmpty() +
                ". Result: ${responseBody.take(2_000)}",
            System.currentTimeMillis() - start,
        )
    }

    private fun handleListPlaces(baseUrl: String, token: String, start: Long): ToolResult {
        val places = loadPlaces(baseUrl, token, force = true)
            ?: return ToolResult.error(
                "Could not read areas, floors and labels from Home Assistant (needs HA 2024.4 or newer).",
                System.currentTimeMillis() - start,
            )
        val output = buildString {
            // Totals up front: a small model listing floors from the tree below once said
            // "three floors" for four.
            appendLine(
                "Home Assistant places: ${places.floors.size} floors, ${places.areas.size} areas, " +
                    "${places.labels.size} labels (use the name or id as 'area').",
            )
            if (places.floors.isNotEmpty()) {
                appendLine("Floors: ${places.floors.joinToString(", ") { safe(it.name) }}")
            }
            val areasById = places.areas.associateBy { it.id }
            places.floors.forEach { floor ->
                appendLine("Floor ${safe(floor.name)} [${floor.id}]: ${places.entitiesOf(floor).size} entities")
                floor.areaIds.mapNotNull { areasById[it] }.forEach { area ->
                    appendLine("  • Area ${safe(area.name)} [${area.id}]: ${area.entityIds.size} entities")
                }
            }
            val floorless = places.areas.filter { a -> places.floors.none { a.id in it.areaIds } }
            if (floorless.isNotEmpty()) {
                appendLine("Areas without a floor:")
                floorless.forEach { appendLine("  • Area ${safe(it.name)} [${it.id}]: ${it.entityIds.size} entities") }
            }
            if (places.labels.isNotEmpty()) {
                appendLine("Labels:")
                places.labels.forEach { appendLine("  • Label ${safe(it.name)} [${it.id}]: ${it.entityIds.size} entities") }
            }
        }.trimEnd()
        return ToolResult.ok(output, System.currentTimeMillis() - start)
    }

    private fun handleRenderTemplate(
        baseUrl: String,
        token: String,
        arguments: Map<String, JsonElement>,
        start: Long,
    ): ToolResult {
        val template = arguments["template"]?.str()?.trim().orEmpty()
        if (template.isBlank()) {
            return ToolResult.error("Missing required parameter: 'template'", System.currentTimeMillis() - start)
        }
        if (template.length > MAX_TEMPLATE_INPUT_CHARS) {
            return ToolResult.error("Template too long (max $MAX_TEMPLATE_INPUT_CHARS characters).", System.currentTimeMillis() - start)
        }
        val rendered = when (val r = renderTemplate(baseUrl, token, template)) {
            is Rendered.Ok -> r.text
            is Rendered.Failed -> return ToolResult.error("Template failed: ${r.reason}", System.currentTimeMillis() - start)
        }
        // Rendered text can quote any entity name or attribute, so it is screened like one.
        if (!SkillGuard.vet(rendered).ok) {
            return ToolResult.error("Template output withheld: it failed the untrusted-content screen.", System.currentTimeMillis() - start)
        }
        val clipped = if (rendered.length > MAX_TEMPLATE_OUTPUT_CHARS) {
            rendered.take(MAX_TEMPLATE_OUTPUT_CHARS) + "\n[truncated - narrow the template]"
        } else {
            rendered
        }
        return ToolResult.ok(clipped.ifBlank { "(empty result)" }, System.currentTimeMillis() - start)
    }

    /**
     * Assist can act on anything HA exposes to it, and its sentence names no domain the
     * high-risk check could read, so ToolConfirmationService always asks before it runs.
     */
    private fun handleAssist(
        baseUrl: String,
        token: String,
        arguments: Map<String, JsonElement>,
        start: Long,
    ): ToolResult {
        val text = arguments["text"]?.str()?.trim().orEmpty()
        if (text.isBlank()) {
            return ToolResult.error("Missing required parameter: 'text'", System.currentTimeMillis() - start)
        }
        if (text.length > MAX_ASSIST_TEXT_CHARS) {
            return ToolResult.error("Text too long for assist (max $MAX_ASSIST_TEXT_CHARS characters).", System.currentTimeMillis() - start)
        }
        val body = JsonObject(mapOf("text" to JsonPrimitive(text))).toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/api/conversation/process")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ToolResult.error("Assist failed: HTTP ${response.code} ${response.message}", System.currentTimeMillis() - start)
            }
            response.body?.string().orEmpty()
        }
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
            ?: return ToolResult.error("Invalid JSON response from Assist", System.currentTimeMillis() - start)
        val resp = root["response"] as? JsonObject
        val speech = ((resp?.get("speech") as? JsonObject)?.get("plain") as? JsonObject)
            ?.get("speech")?.str().orEmpty()
        val type = resp?.get("response_type")?.str().orEmpty()
        val data = resp?.get("data") as? JsonObject
        fun targets(key: String) = (data?.get(key) as? JsonArray).orEmpty().mapNotNull { t ->
            val o = t as? JsonObject
            o?.get("name")?.str() ?: o?.get("id")?.str()
        }
        val output = buildString {
            appendLine("Assist ($type): ${safe(speech).ifBlank { "(no reply)" }}")
            targets("success").takeIf { it.isNotEmpty() }
                ?.let { appendLine("Acted on: ${it.joinToString(", ") { t -> safe(t) }}") }
            targets("failed").takeIf { it.isNotEmpty() }
                ?.let { appendLine("Failed on: ${it.joinToString(", ") { t -> safe(t) }}") }
        }.trimEnd()
        return if (type == "error") {
            ToolResult.error(output, System.currentTimeMillis() - start)
        } else {
            ToolResult.ok(output, System.currentTimeMillis() - start)
        }
    }

    private sealed interface Rendered {
        data class Ok(val text: String) : Rendered
        data class Failed(val reason: String) : Rendered
    }

    private fun renderTemplate(baseUrl: String, token: String, template: String): Rendered {
        val body = JsonObject(mapOf("template" to JsonPrimitive(template))).toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/api/template")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Rendered.Ok(text)
            } else {
                // HA answers a bad template with 400 and its error message in the body.
                Rendered.Failed("HTTP ${response.code} ${text.take(300)}".trim())
            }
        }
    }

    @Volatile
    private var placesCache: Triple<String, Long, HomeAssistantPlaces>? = null

    private fun loadPlaces(baseUrl: String, token: String, force: Boolean = false): HomeAssistantPlaces? {
        val now = System.currentTimeMillis()
        placesCache?.let { (url, at, places) ->
            if (!force && url == baseUrl && now - at < PLACES_TTL_MS) return places
        }
        val rendered = runCatching { renderTemplate(baseUrl, token, HomeAssistantPlaces.INDEX_TEMPLATE) }
            .onFailure { Timber.w(it, "HA place index render failed") }
            .getOrNull() as? Rendered.Ok ?: return null
        val places = HomeAssistantPlaces.parse(rendered.text) ?: return null
        placesCache = Triple(baseUrl, now, places)
        return places
    }

    private fun describe(place: HomeAssistantPlaces.Place): String =
        "${place.kind.name.lowercase()} '${safe(place.name)}' [${place.id}]"

    private fun ambiguous(
        query: String,
        r: HomeAssistantPlaces.Resolution.Ambiguous,
        start: Long,
    ): ToolResult = ToolResult.error(
        "'$query' matches several places: ${r.candidates.joinToString(", ") { describe(it) }}. Pass one of these ids as 'area'.",
        System.currentTimeMillis() - start,
    )

    private fun unknownPlace(query: String, places: HomeAssistantPlaces, start: Long): ToolResult {
        val known = (places.areas + places.floors + places.labels).joinToString(", ") { safe(it.name) }
        return ToolResult.error("No area, floor or label matches '$query'. Known places: $known.", System.currentTimeMillis() - start)
    }

    /** Names come from the user's HA and can carry injected text; screen them as friendly_name is. */
    private fun safe(s: String): String = if (SkillGuard.vet(s).ok) s else "[unverified name]"

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HomeAssistantToolModule {
    @Binds
    @IntoSet
    abstract fun bindHomeAssistantTool(tool: HomeAssistantTool): Tool
}
