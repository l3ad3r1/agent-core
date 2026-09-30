package com.hermes.agent.data.plugin

import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.plugin.script.ScriptModuleDigest
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptPluginHost
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptToolSpec
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device install path used by the app's feature-evolution flow. */
class ScriptPluginRepositoryLocalInstallTest {

    private class FakeToolRegistry : ToolRegistry {
        val tools = linkedMapOf<String, Tool>()
        override fun all(): List<Tool> = tools.values.toList()
        override fun byName(name: String): Tool? = tools[name]
        override fun register(tool: Tool) { tools[tool.descriptor.name] = tool }
        override fun unregister(name: String) { tools.remove(name) }
    }

    private class FakeDao : ScriptPluginDao {
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

    private class NoHost : ScriptPluginHost {
        override fun log(pluginId: String, message: String) = Unit
        override fun readData(pluginId: String, collection: String, query: String) = ""
        override fun writeData(pluginId: String, collection: String, payload: String) = ""
        override fun httpGet(pluginId: String, url: String, allowedHosts: List<String>) = ""
    }

    private class BuiltIn(name: String) : Tool {
        override val descriptor = ToolDescriptor(name, "built-in", emptyList())
        override suspend fun execute(arguments: Map<String, JsonElement>) = ToolResult.ok("built-in")
    }

    private val dao = FakeDao()
    private val registry = FakeToolRegistry()
    private val repository = ScriptPluginRepository(dao, ScriptPluginEngine(), registry, NoHost())

    private fun manifestJson(
        id: String = "evo-greeter",
        tool: String = "greet",
        overrides: List<String> = emptyList(),
        permissions: List<String> = emptyList(),
        body: String = "return 'hi ' + args.name;",
    ): String = ScriptPluginManifest.json.encodeToString(
        ScriptPluginManifest(
            id = id,
            name = "Greeter",
            version = "1.0.0",
            permissions = permissions,
            tools = listOf(ScriptToolSpec(name = tool, description = "Greets")),
            overrides = overrides,
            main = "hermes.registerTool('$tool', function(args) { $body });",
        ),
    )

    @Test
    fun `installLocal stores the exact approved bytes and pins their digest`() = runTest {
        val json = manifestJson()
        val digest = ScriptModuleDigest.sha256Hex(json)

        val result = repository.installLocal(json, approvedPermissions = emptySet(), expectedSha256 = digest)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val row = checkNotNull(dao.items["evo-greeter"])
        assertEquals(json, row.manifestJson)
        assertEquals(ScriptPluginRepository.localSourceUrl("evolution", digest), row.sourceUrl)
        assertEquals(digest, ScriptPluginRepository.pinnedDigest(row.sourceUrl))
        assertEquals("evolution", ScriptPluginRepository.localSourceName(row.sourceUrl))
        assertNotNull(registry.byName("greet"))
    }

    @Test
    fun `installLocal refuses bytes that differ from the approved digest`() = runTest {
        val approved = manifestJson()
        val swapped = manifestJson(body = "return 'something else';")

        val result = repository.installLocal(
            swapped,
            approvedPermissions = emptySet(),
            expectedSha256 = ScriptModuleDigest.sha256Hex(approved),
        )

        assertTrue(result.isFailure)
        assertTrue(dao.items.isEmpty())
        assertNull(registry.byName("greet"))
    }

    @Test
    fun `a local module edited after install is not loaded`() = runTest {
        val json = manifestJson()
        repository.installLocal(json, emptySet()).getOrThrow()
        val row = checkNotNull(dao.items["evo-greeter"])
        dao.items["evo-greeter"] = row.copy(manifestJson = manifestJson(body = "return 'tampered';"))

        val failures = repository.reloadEnabled()

        assertTrue(failures.any { it.startsWith("evo-greeter:") && "digest" in it })
        assertNull(registry.byName("greet"))
    }

    @Test
    fun `only declared and approved permissions are granted`() = runTest {
        val json = manifestJson(permissions = listOf("network"), body = "return 'x';")
        repository.installLocal(json, approvedPermissions = setOf("network", "data.write")).getOrThrow()
        assertEquals("network", dao.items["evo-greeter"]?.grantedPermissions)

        repository.uninstall("evo-greeter")
        repository.installLocal(json, approvedPermissions = emptySet()).getOrThrow()
        assertEquals("", dao.items["evo-greeter"]?.grantedPermissions)
    }

    @Test
    fun `override tools are exposed as candidates and never registered`() = runTest {
        val builtIn = BuiltIn("calculator")
        registry.register(builtIn)

        repository.installLocal(manifestJson(tool = "calculator", overrides = listOf("calculator")), emptySet())
            .getOrThrow()

        assertSame(builtIn, registry.byName("calculator"))
        val candidate = checkNotNull(repository.overrideCandidates()["calculator"])
        assertEquals("evo-greeter", candidate.moduleId)
        assertEquals("hi you", candidate.tool.execute(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("you"))).output)

        repository.setEnabled("evo-greeter", false)
        assertTrue(repository.overrideCandidates().isEmpty())
    }

    @Test
    fun `reload generation advances on every reload`() = runTest {
        val before = repository.reloadGeneration.value
        repository.reloadEnabled()
        repository.reloadEnabled()
        assertEquals(before + 2, repository.reloadGeneration.value)
    }

    @Test
    fun `an override must name one of the module's own tools`() = runTest {
        val result = repository.installLocal(manifestJson(overrides = listOf("web_search")), emptySet())
        assertTrue(result.isFailure)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `a registry install may not declare overrides`() = runTest {
        val manifest = ScriptPluginManifest.json.decodeFromString<ScriptPluginManifest>(
            manifestJson(tool = "calculator", overrides = listOf("calculator")),
        )
        val result = repository.install(manifest, "https://example.com/m.json")
        assertTrue(result.isFailure)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `a registry install may not replace a local module and vice versa`() = runTest {
        repository.installLocal(manifestJson(), emptySet()).getOrThrow()
        val registryCopy = ScriptPluginManifest.json.decodeFromString<ScriptPluginManifest>(manifestJson())
        assertTrue(repository.install(registryCopy, "https://example.com/m.json").isFailure)
        assertTrue(ScriptPluginRepository.isLocalSource(dao.items.getValue("evo-greeter").sourceUrl))

        val other = ScriptPluginManifest.json.decodeFromString<ScriptPluginManifest>(
            manifestJson(id = "registry-mod", tool = "other_tool"),
        )
        repository.install(other, "https://example.com/r.json").getOrThrow()
        assertTrue(repository.installLocal(manifestJson(id = "registry-mod", tool = "other_tool"), emptySet()).isFailure)
    }

    @Test
    fun `a local module that fails to load is switched off again`() = runTest {
        val json = ScriptPluginManifest.json.encodeToString(
            ScriptPluginManifest(
                id = "evo-broken",
                name = "Broken",
                version = "1.0.0",
                tools = listOf(ScriptToolSpec(name = "broken", description = "x")),
                main = "throw new Error('boom');",
            ),
        )
        val result = repository.installLocal(json, emptySet())
        assertTrue(result.isFailure)
        assertFalse(dao.items.getValue("evo-broken").enabled)
    }

    @Test
    fun `malformed local sources carry no pin`() {
        assertNull(ScriptPluginRepository.pinnedDigest("local:evolution#sha256=xyz"))
        assertNull(ScriptPluginRepository.pinnedDigest("https://example.com/m.json"))
        assertFalse(ScriptPluginRepository.isLocalSource("https://example.com/m.json"))
    }
}
