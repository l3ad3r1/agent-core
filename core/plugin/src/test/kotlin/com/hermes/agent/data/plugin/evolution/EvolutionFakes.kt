package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.plugin.script.ScriptPluginHost
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptToolParameter
import com.hermes.agent.data.plugin.script.ScriptToolSpec
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class FakeToolRegistry : ToolRegistry {
    val tools = linkedMapOf<String, Tool>()
    override fun all(): List<Tool> = tools.values.toList()
    override fun byName(name: String): Tool? = tools[name]
    override fun register(tool: Tool) { tools[tool.descriptor.name] = tool }
    override fun unregister(name: String) { tools.remove(name) }
}

internal class FakeScriptPluginDao : ScriptPluginDao {
    val items = linkedMapOf<String, ScriptPluginEntity>()
    private val flow = MutableStateFlow<List<ScriptPluginEntity>>(emptyList())
    override fun observeAll(): Flow<List<ScriptPluginEntity>> = flow
    override suspend fun getAll() = items.values.toList()
    override suspend fun getEnabled() = items.values.filter { it.enabled }
    override suspend fun getById(id: String) = items[id]
    override suspend fun upsert(entity: ScriptPluginEntity) { items[entity.id] = entity; flow.value = getAll() }
    override suspend fun setEnabled(id: String, enabled: Boolean) {
        items[id]?.let { items[id] = it.copy(enabled = enabled) }
        flow.value = getAll()
    }
    override suspend fun delete(id: String) { items.remove(id); flow.value = getAll() }
}

internal class NoHost : ScriptPluginHost {
    override fun log(pluginId: String, message: String) = Unit
    override fun readData(pluginId: String, collection: String, query: String) = ""
    override fun writeData(pluginId: String, collection: String, payload: String) = ""
    override fun httpGet(pluginId: String, url: String, allowedHosts: List<String>) = ""
}

/** A first-party tool that answers with a fixed string and counts its calls. */
internal class FakeBuiltIn(
    name: String,
    category: String = "information",
    requiresConfirmation: Boolean = false,
    params: List<ToolParameter> = listOf(ToolParameter("expression", ToolParameterType.STRING, "what to compute", required = true)),
    private val answer: String = "built-in answer",
) : Tool {
    var calls = 0
    override val descriptor = ToolDescriptor(name, "built-in $name", params, category, requiresConfirmation)
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        calls++
        return ToolResult.ok(answer)
    }
}

internal class InMemoryProposalStore : EvolutionProposalStore {
    val items = linkedMapOf<String, EvolutionProposal>()
    override suspend fun get(id: String) = items[id]
    override suspend fun all() = items.values.toList()
    override suspend fun insert(proposal: EvolutionProposal) { items[proposal.id] = proposal }
    override suspend fun update(proposal: EvolutionProposal) { items[proposal.id] = proposal }
    override suspend fun findInstalledByModule(moduleId: String) =
        items.values.lastOrNull { it.moduleId == moduleId && it.status == ProposalStatus.INSTALLED }
}

internal class InMemoryVersionStore : EvolutionModuleVersionStore {
    val items = mutableListOf<EvolutionModuleVersion>()
    override suspend fun versions(moduleId: String) = items.filter { it.moduleId == moduleId }.sortedBy { it.installedAt }
    override suspend fun record(version: EvolutionModuleVersion) { items += version }
    override suspend fun markActive(moduleId: String, versionId: String?) {
        items.replaceAll { if (it.moduleId == moduleId) it.copy(active = it.id == versionId) else it }
    }
}

internal class RecordingEvents : EvolutionEvents {
    val log = mutableListOf<String>()
    override fun proposalsReady(count: Int) { log += "proposals:$count" }
    override fun moduleReady(title: String) { log += "ready:$title" }
    override fun overrideReverted(toolName: String, moduleId: String, reason: String) { log += "reverted:$toolName:$moduleId" }
}

/** Keeps text unless it contains the word "SECRET" — enough to prove the sanitizer is applied. */
internal val TestSanitizer = EvidenceSanitizer { text -> if ("SECRET" in text) null else text.replace("hunter2", "[REDACTED]") }

internal fun proposal(
    id: String = "p1",
    kind: ProposalKind = ProposalKind.MODULE_FEATURE,
    status: ProposalStatus = ProposalStatus.APPROVED,
    target: String? = null,
    now: Long = 1_000L,
) = EvolutionProposal(
    id = id,
    title = "Add a unit converter",
    problem = "User keeps asking to convert units",
    evidence = "[1] repeated ×5",
    kind = kind,
    acceptanceCriteria = listOf("Converts km to miles"),
    targetTool = target,
    signalKey = "repeat:abc",
    status = status,
    createdAt = now,
    updatedAt = now,
)

/** A valid evolution module manifest as JSON, with knobs for the hostile variants. */
internal fun moduleJson(
    id: String = "evo-unit-converter",
    tool: String = "convert_units",
    body: String = "var km = Number(args.km); if (isNaN(km)) return 'error: km must be a number'; return (km * 0.621371).toFixed(2) + ' miles';",
    params: List<ScriptToolParameter> = listOf(ScriptToolParameter("km", "NUMBER", "kilometres", required = true)),
    permissions: List<String> = emptyList(),
    hosts: List<String> = emptyList(),
    overrides: List<String> = emptyList(),
    tests: JsonArray? = null,
    version: String = "1.0.0",
    extra: Map<String, JsonElement> = emptyMap(),
    main: String? = null,
): String {
    val manifest = ScriptPluginManifest(
        id = id,
        name = "Unit converter",
        version = version,
        description = "Converts units",
        permissions = permissions,
        hosts = hosts,
        tools = listOf(ScriptToolSpec(name = tool, description = "Convert kilometres to miles", parameters = params)),
        overrides = overrides,
        main = main ?: "hermes.registerTool('$tool', function(args) { $body });",
    )
    val base = ScriptPluginManifest.json.parseToJsonElement(ScriptPluginManifest.json.encodeToString(manifest)).jsonObject
    val testArray = tests ?: JsonArray(
        listOf(
            buildJsonObject {
                put("tool", tool)
                put("arguments", buildJsonObject { put("km", 10) })
                put("expectContains", "6.21 miles")
            },
            buildJsonObject {
                put("tool", tool)
                put("arguments", buildJsonObject { put("km", "abc") })
                put("expectContains", "error")
            },
        ),
    )
    return JsonObject(base + mapOf("tests" to testArray) + extra).toString()
}

internal fun smokeCase(tool: String, expect: String, vararg args: Pair<String, JsonElement>) = buildJsonObject {
    put("tool", tool)
    put("arguments", JsonObject(args.toMap()))
    put("expectContains", expect)
}

internal fun fenced(json: String, lang: String = "json") = "Here is the module.\n```$lang\n$json\n```\nDone."

internal fun str(s: String) = JsonPrimitive(s)
