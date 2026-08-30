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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
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
    private var ssePostEndpoint: String? = null

    companion object {
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val MAX_DESCRIPTION_LENGTH = 1024
        private const val MAX_TOOL_OUTPUT_CHARS = 16384

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
                resolveSseEndpoint()
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

    private fun resolveSseEndpoint() {
        val requestBuilder = Request.Builder()
            .url(config.url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        config.headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            return
        }

        val body = response.body ?: return
        try {
            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            var line: String?
            var currentEvent = ""
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: break
                if (l.startsWith("event:")) {
                    currentEvent = l.substring(6).trim()
                } else if (l.startsWith("data:")) {
                    val data = l.substring(5).trim()
                    if (currentEvent == "endpoint" || currentEvent.isEmpty()) {
                        ssePostEndpoint = resolveUrl(config.url, data)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed resolving SSE endpoint")
        } finally {
            response.close()
        }
    }

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
        val postUrl = getPostUrl()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(postUrl)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        config.headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string().orEmpty()
        response.close()

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        // Response could be direct JSON-RPC or an SSE data line
        val cleanBody = if (responseBody.contains("data:")) {
            responseBody.lines()
                .firstOrNull { it.startsWith("data:") }
                ?.substring(5)?.trim() ?: responseBody
        } else {
            responseBody
        }

        return json.parseToJsonElement(cleanBody)
    }

    private fun sendJsonRpcNotification(payload: JsonObject) {
        val postUrl = getPostUrl()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(postUrl)
            .post(body)
            .header("Content-Type", "application/json")

        config.headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        httpClient.newCall(requestBuilder.build()).execute().close()
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

                parameters.add(
                    ToolParameter(
                        name = propName,
                        type = paramType,
                        description = descStr,
                        required = isRequired,
                        enumValues = enumList,
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
