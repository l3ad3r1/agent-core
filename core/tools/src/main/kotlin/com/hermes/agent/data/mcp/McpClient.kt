package com.hermes.agent.data.mcp

import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpToolDefinition
import com.hermes.agent.domain.mcp.McpTransportType
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for Model Context Protocol (MCP) servers over HTTP and SSE.
 * Implements JSON-RPC 2.0 protocol for handshake, tool listing, and tool invocation.
 */
class McpClient(
    val config: McpServerConfig,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val requestId = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    @Volatile private var ssePostEndpoint: String? = null
    @Volatile private var sseCall: Call? = null
    /** Streamable HTTP session id from initialize, sent back on every later request. */
    @Volatile private var sessionId: String? = null
    /** SSE transport: requests waiting for their response to arrive on the stream. */
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement>>()
    /** Responses that reached the stream before their caller started waiting. */
    private val unclaimed = ConcurrentHashMap<Int, JsonElement>()

    companion object {
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val MAX_DESCRIPTION_LENGTH = 1024
        private const val MAX_TOOL_OUTPUT_CHARS = 16384
        private const val SESSION_HEADER = "Mcp-Session-Id"
        private const val ENDPOINT_TIMEOUT_SECONDS = 30L
        private const val RESPONSE_TIMEOUT_SECONDS = 120L

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun sanitizeServerName(name: String): String {
            return name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        }

        fun sanitizeToolName(name: String): String {
            return name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        }

        fun qualifyToolName(serverName: String, toolName: String): String {
            val safeServer = sanitizeServerName(serverName)
            val safeTool = sanitizeToolName(toolName)
            return "mcp__${safeServer}__${safeTool}"
        }

        fun sanitizeDescription(raw: String?): String {
            if (raw.isNullOrBlank()) return "MCP tool"
            val sanitized = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
            return if (sanitized.length > MAX_DESCRIPTION_LENGTH) {
                sanitized.substring(0, MAX_DESCRIPTION_LENGTH) + "..."
            } else {
                sanitized
            }
        }
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (config.transport == McpTransportType.SSE) {
                close()
                openSseStream()
            }

            val initPayload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") {
                        putJsonObject("roots") { put("listChanged", false) }
                        putJsonObject("sampling") {}
                    }
                    putJsonObject("clientInfo") {
                        put("name", "hermes-agent-android")
                        put("version", "0.10.0")
                    }
                }
            }

            val responseJson = sendJsonRpc(initPayload)
            val error = responseJson.jsonObject["error"]
            if (error != null) {
                val errorMsg = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown MCP error"
                return@withContext Result.failure(Exception("MCP initialize failed: ${sanitizeErrorMessage(errorMsg)}"))
            }

            // Send notifications/initialized
            val notifPayload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
            try {
                sendJsonRpcNotification(notifPayload)
            } catch (e: Exception) {
                Timber.w(e, "Optional notification 'notifications/initialized' failed")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(sanitizeErrorMessage(e.message ?: "Failed to initialize MCP client"), e))
        }
    }

    suspend fun listTools(): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        try {
            // A dropped SSE stream ended the server session; open a new one first.
            if (needsSseSession()) initialize().getOrThrow()
            val listPayload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "tools/list")
                putJsonObject("params") {}
            }

            val responseJson = sendJsonRpc(listPayload)
            val error = responseJson.jsonObject["error"]
            if (error != null) {
                val errorMsg = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown MCP error"
                return@withContext Result.failure(Exception("tools/list failed: ${sanitizeErrorMessage(errorMsg)}"))
            }

            val resultObj = responseJson.jsonObject["result"]?.jsonObject
                ?: return@withContext Result.failure(Exception("Invalid MCP tools/list response: missing result object"))

            val toolsArray = resultObj["tools"]?.jsonArray ?: JsonArray(emptyList())
            val definitions = mutableListOf<McpToolDefinition>()

            for (toolElem in toolsArray) {
                val toolObj = toolElem.jsonObject
                val rawName = toolObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val rawDesc = toolObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val inputSchema = toolObj["inputSchema"] ?: buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                }

                val safeToolName = sanitizeToolName(rawName)
                val qualifiedName = qualifyToolName(config.name, safeToolName)
                val cleanDescription = sanitizeDescription(rawDesc)

                definitions.add(
                    McpToolDefinition(
                        serverId = config.id,
                        toolName = safeToolName,
                        qualifiedName = qualifiedName,
                        description = cleanDescription,
                        inputSchemaJson = inputSchema.toString(),
                        remoteName = rawName,
                    )
                )
            }

            Result.success(definitions)
        } catch (e: Exception) {
            Result.failure(Exception(sanitizeErrorMessage(e.message ?: "Failed to list MCP tools"), e))
        }
    }

    suspend fun callTool(toolName: String, arguments: Map<String, JsonElement>): Result<String> = withContext(Dispatchers.IO) {
        try {
            // A dropped SSE stream ended the server session; open a new one first.
            if (needsSseSession()) initialize().getOrThrow()
            val argsObj = JsonObject(arguments)
            val callPayload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.getAndIncrement())
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", toolName)
                    put("arguments", argsObj)
                }
            }

            val responseJson = sendJsonRpc(callPayload)
            val error = responseJson.jsonObject["error"]
            if (error != null) {
                val errorMsg = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown MCP error"
                return@withContext Result.failure(Exception("Tool call failed: ${sanitizeErrorMessage(errorMsg)}"))
            }

            val resultObj = responseJson.jsonObject["result"]?.jsonObject
                ?: return@withContext Result.failure(Exception("Invalid MCP tool call response: missing result"))

            val isError = resultObj["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            val contentArray = resultObj["content"]?.jsonArray

            val textOutput = StringBuilder()
            if (contentArray != null) {
                for (item in contentArray) {
                    val itemObj = item.jsonObject
                    val type = itemObj["type"]?.jsonPrimitive?.contentOrNull ?: "text"
                    if (type == "text") {
                        val text = itemObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (textOutput.isNotEmpty()) textOutput.append("\n")
                        textOutput.append(text)
                    } else {
                        if (textOutput.isNotEmpty()) textOutput.append("\n")
                        textOutput.append(itemObj.toString())
                    }
                }
            } else {
                textOutput.append(resultObj.toString())
            }

            val finalOutput = if (textOutput.length > MAX_TOOL_OUTPUT_CHARS) {
                textOutput.substring(0, MAX_TOOL_OUTPUT_CHARS) + "\n...[truncated output]"
            } else {
                textOutput.toString()
            }

            if (isError) {
                Result.failure(Exception(sanitizeErrorMessage(finalOutput)))
            } else {
                Result.success(finalOutput)
            }
        } catch (e: Exception) {
            Result.failure(Exception(sanitizeErrorMessage(e.message ?: "MCP tool call execution error"), e))
        }
    }

    /**
     * Opens the SSE stream and keeps it open for the life of this client.
     *
     * Under the 2024-11-05 SSE transport the server answers each POST with 202
     * and sends the JSON-RPC response on this stream; closing it also ends the
     * server session. So the stream is read on its own thread for as long as
     * the client lives, and responses are routed to their callers by id.
     */
    private fun openSseStream() {
        val requestBuilder = Request.Builder()
            .url(config.url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
        config.headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        // No read timeout: an idle stream between tool calls is normal.
        val call = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            .newCall(requestBuilder.build())
        val response = call.execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw Exception("HTTP $code opening the MCP event stream")
        }
        val body = response.body ?: run {
            response.close()
            throw Exception("MCP event stream has no body")
        }
        sseCall = call
        val endpoint = CompletableFuture<String>()

        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                readSseEvents(reader) { event, data -> onSseEvent(event, data, endpoint) }
            } catch (e: Exception) {
                if (sseCall === call && !call.isCanceled()) Timber.w(e, "MCP event stream failed")
            } finally {
                response.close()
                if (sseCall === call) {
                    sseCall = null
                    ssePostEndpoint = null
                }
                endpoint.completeExceptionally(Exception("MCP event stream closed"))
                failPending(Exception("MCP event stream closed"))
            }
        }, "mcp-sse-${config.id}").apply { isDaemon = true }.start()

        ssePostEndpoint = try {
            endpoint.get(ENDPOINT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            close()
            throw Exception("MCP server sent no endpoint event", e)
        }
    }

    private fun onSseEvent(event: String, data: String, endpoint: CompletableFuture<String>) {
        if (event == "endpoint") {
            endpoint.complete(resolveUrl(config.url, data.trim()))
            return
        }
        val message = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        // Server requests and notifications carry no id we are waiting on.
        val id = message["id"]?.jsonPrimitive?.intOrNull ?: return
        pending.remove(id)?.complete(message) ?: unclaimed.put(id, message)
    }

    private fun failPending(error: Exception) {
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(error) }
    }

    /** Stops the event stream. Safe to call more than once. */
    fun close() {
        val call = sseCall
        sseCall = null
        ssePostEndpoint = null
        call?.cancel()
        failPending(Exception("MCP client closed"))
        unclaimed.clear()
    }

    /** True when an SSE-transport client has lost its stream and must handshake again. */
    private fun needsSseSession(): Boolean =
        config.transport == McpTransportType.SSE && sseCall == null

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
            relativeOrAbsolute
        } else {
            val baseUri = java.net.URI(baseUrl)
            baseUri.resolve(relativeOrAbsolute).toString()
        }
    }

    private fun getPostUrl(): String {
        return ssePostEndpoint ?: config.url
    }

    private fun sendJsonRpc(payload: JsonObject): JsonElement {
        val id = payload["id"]?.jsonPrimitive?.intOrNull
        val onStream = config.transport == McpTransportType.SSE && ssePostEndpoint != null
        val answer = if (onStream && id != null) {
            CompletableFuture<JsonElement>().also { future ->
                pending[id] = future
                unclaimed.remove(id)?.let { pending.remove(id); future.complete(it) }
            }
        } else {
            null
        }

        val response = try {
            httpClient.newCall(postRequest(payload, accept = true)).execute()
        } catch (e: Exception) {
            if (id != null) pending.remove(id)
            throw e
        }
        val responseBody = response.body?.string().orEmpty()
        response.close()
        rememberSession(response)

        if (!response.isSuccessful) {
            if (id != null) pending.remove(id)
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        // Some servers still answer inline; otherwise the reply comes on the stream.
        pickResponse(responseBody, id)?.let { inline ->
            if (id != null) pending.remove(id)
            return inline
        }
        if (answer == null || id == null) throw Exception("MCP server returned no JSON-RPC response")
        return try {
            answer.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } catch (e: TimeoutException) {
            throw Exception("MCP server did not answer ${payload["method"]} in time")
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Finds the reply to request [id] in a POST body, which is either one JSON
     * message or an SSE stream. A stream can carry progress notifications before
     * the result, so every event is checked, not just the first `data:` line.
     */
    private fun pickResponse(body: String, id: Int?): JsonElement? {
        if (body.isBlank()) return null
        val trimmed = body.trimStart()
        val candidates = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            listOf(body)
        } else {
            buildList { readSseEvents(BufferedReader(StringReader(body))) { _, data -> add(data) } }
        }
        val messages = candidates.mapNotNull { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            .flatMap { if (it is JsonArray) it else listOf(it) }
            .mapNotNull { it as? JsonObject }
        return messages.firstOrNull { id != null && it["id"]?.jsonPrimitive?.intOrNull == id }
            ?: messages.firstOrNull { "result" in it || "error" in it }
    }

    private fun sendJsonRpcNotification(payload: JsonObject) {
        val response = httpClient.newCall(postRequest(payload, accept = false)).execute()
        rememberSession(response)
        response.close()
    }

    private fun postRequest(payload: JsonObject, accept: Boolean): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBuilder = Request.Builder()
            .url(getPostUrl())
            .post(payload.toString().toRequestBody(mediaType))
            .header("Content-Type", "application/json")
        if (accept) requestBuilder.header("Accept", "application/json, text/event-stream")
        config.headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        // Streamable HTTP servers with sessions reject calls that omit it.
        sessionId?.let { requestBuilder.header(SESSION_HEADER, it) }
        return requestBuilder.build()
    }

    private fun rememberSession(response: Response) {
        response.header(SESSION_HEADER)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
    }

    /** Calls [onEvent] with each event type and its joined `data:` lines, per the SSE format. */
    private fun readSseEvents(reader: BufferedReader, onEvent: (String, String) -> Unit) {
        var event = ""
        val data = StringBuilder()
        fun dispatch() {
            if (data.isNotEmpty()) onEvent(event.ifEmpty { "message" }, data.toString())
            event = ""
            data.setLength(0)
        }
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                }
            }
        }
        dispatch()
    }

    fun sanitizeErrorMessage(raw: String): String {
        var clean = raw
        config.headers.values.forEach { headerVal ->
            if (headerVal.length > 5) {
                clean = clean.replace(headerVal, "[REDACTED]")
            }
            headerVal.split(Regex("\\s+")).forEach { part ->
                if (part.length > 5 && !part.equals("Bearer", ignoreCase = true)) {
                    clean = clean.replace(part, "[REDACTED]")
                }
            }
        }
        return clean.replace(Regex("Bearer\\s+[A-Za-z0-9._~+/-]+", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
    }

    fun parseParameters(inputSchemaJson: String): List<ToolParameter> {
        try {
            val schemaObj = json.parseToJsonElement(inputSchemaJson).jsonObject
            val properties = schemaObj["properties"]?.jsonObject ?: return emptyList()
            val requiredArray = schemaObj["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

            val parameters = mutableListOf<ToolParameter>()
            for ((propName, propElem) in properties) {
                val propObj = propElem.jsonObject
                val typeStr = propObj["type"]?.jsonPrimitive?.contentOrNull ?: "string"
                val descStr = propObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val isRequired = requiredArray.contains(propName)

                val paramType = when (typeStr.lowercase()) {
                    "integer" -> ToolParameterType.INTEGER
                    "number" -> ToolParameterType.NUMBER
                    "boolean" -> ToolParameterType.BOOLEAN
                    "array" -> ToolParameterType.ARRAY
                    "object" -> ToolParameterType.OBJECT
                    else -> ToolParameterType.STRING
                }

                val enumList = propObj["enum"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                val itemTypeStr = (propObj["items"] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull

                parameters.add(
                    ToolParameter(
                        name = propName,
                        type = paramType,
                        description = descStr,
                        required = isRequired,
                        enumValues = enumList,
                        itemType = when (itemTypeStr?.lowercase()) {
                            "integer" -> ToolParameterType.INTEGER
                            "number" -> ToolParameterType.NUMBER
                            "boolean" -> ToolParameterType.BOOLEAN
                            "object" -> ToolParameterType.OBJECT
                            "array" -> ToolParameterType.ARRAY
                            else -> ToolParameterType.STRING
                        },
                    )
                )
            }
            return parameters
        } catch (e: Exception) {
            Timber.w(e, "Failed parsing inputSchemaJson for MCP tool: $inputSchemaJson")
            return emptyList()
        }
    }
}
