package com.hermes.agent.data.plugin

import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptPluginHost
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPluginRepositoryTest {

    /**
     * Minimal in-memory [ToolRegistry]. The real implementation lives in
     * :core:tools, which :core:plugin deliberately does not depend on; these
     * tests only need register/unregister bookkeeping.
     */
    private class FakeToolRegistry : ToolRegistry {
        private val tools = linkedMapOf<String, Tool>()
        override fun all(): List<Tool> = tools.values.toList()
        override fun byName(name: String): Tool? = tools[name]
        override fun register(tool: Tool) { tools[tool.descriptor.name] = tool }
        override fun unregister(name: String) { tools.remove(name) }
    }

    private class FakeScriptPluginDao : ScriptPluginDao {
        val items = mutableMapOf<String, ScriptPluginEntity>()
        val flow = MutableStateFlow<List<ScriptPluginEntity>>(emptyList())
        var beforeReadEnabled: suspend () -> Unit = {}

        private fun emit() {
            flow.value = items.values.toList()
        }

        override fun observeAll(): Flow<List<ScriptPluginEntity>> = flow

        override suspend fun getAll(): List<ScriptPluginEntity> = items.values.toList()

        override suspend fun getEnabled(): List<ScriptPluginEntity> {
            val hook = beforeReadEnabled
            beforeReadEnabled = {}
            hook()
            return items.values.filter { it.enabled }
        }

        override suspend fun getById(id: String): ScriptPluginEntity? = items[id]

        override suspend fun upsert(entity: ScriptPluginEntity) {
            currentCoroutineContext().ensureActive()
            items[entity.id] = entity
            emit()
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            items[id]?.let {
                items[id] = it.copy(enabled = enabled)
                emit()
            }
        }

        override suspend fun delete(id: String) {
            items.remove(id)
            emit()
        }
    }

    /** No-op host: these tests only exercise install/enable/reload bookkeeping. */
    private class FakeScriptPluginHost : ScriptPluginHost {
        override fun log(pluginId: String, message: String) = Unit
        override fun readData(pluginId: String, collection: String, query: String) = ""
        override fun writeData(pluginId: String, collection: String, payload: String) = ""
        override fun httpGet(pluginId: String, url: String, allowedHosts: List<String>) = ""
    }

    private fun sampleManifest(id: String = "test-plugin", toolName: String = "test_tool") = ScriptPluginManifest(
        id = id,
        name = "Test Plugin",
        version = "1.0.0",
        author = "Tester",
        description = "Test plugin description",
        type = "tool",
        main = "hermes.registerTool('$toolName', function(args) { return 'result'; });",
        permissions = listOf("network"),
        tools = listOf(
            ScriptToolSpec(
                name = toolName,
                description = "desc",
                parameters = emptyList(),
            )
        )
    )

    @Test
    fun `install persists entity with approved permissions and reloads tools`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = FakeToolRegistry()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("my-plugin", "my_custom_tool")
        val result = repository.install(manifest, "https://example.com/manifest.json")

        assertTrue(result.isSuccess)
        val installed = dao.getById("my-plugin")
        assertNotNull(installed)
        assertEquals("my-plugin", installed?.id)
        assertEquals("network", installed?.grantedPermissions)
        assertTrue(installed?.enabled == true)

        val tool = registry.byName("my_custom_tool")
        assertNotNull(tool)
        assertEquals("my_custom_tool", tool?.descriptor?.name)
    }

    @Test
    fun `setEnabled false unregisters tools from registry`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = FakeToolRegistry()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("toggle-plugin", "toggle_tool")
        repository.install(manifest, "https://example.com/manifest.json")
        assertNotNull(registry.byName("toggle_tool"))

        repository.setEnabled("toggle-plugin", false)
        assertNull(registry.byName("toggle_tool"))

        repository.setEnabled("toggle-plugin", true)
        assertNotNull(registry.byName("toggle_tool"))
    }

    @Test
    fun `uninstall deletes from dao and unregisters tool`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = FakeToolRegistry()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("del-plugin", "del_tool")
        repository.install(manifest, "https://example.com/manifest.json")
        assertNotNull(registry.byName("del_tool"))

        repository.uninstall("del-plugin")
        assertNull(dao.getById("del-plugin"))
        assertNull(registry.byName("del_tool"))
    }

    @Test
    fun `install rejects a module that collides with a built in tool`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = FakeToolRegistry()
        val builtIn = object : Tool {
            override val descriptor = ToolDescriptor(
                name = "shell",
                description = "built in",
                parameters = emptyList(),
            )
            override suspend fun execute(arguments: Map<String, kotlinx.serialization.json.JsonElement>) =
                com.hermes.agent.domain.tool.ToolResult.ok("built in")
        }
        registry.register(builtIn)
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val result = repository.install(sampleManifest("collision", "shell"), "https://example.com/manifest.json")

        assertTrue(result.isFailure)
        assertEquals(builtIn, registry.byName("shell"))
        assertNull(dao.getById("collision"))
    }

    @Test
    fun `cancelled updates restore persisted grants and runtime before propagating cancellation`() = runTest {
        for (local in listOf(false, true)) {
            val dao = FakeScriptPluginDao()
            val registry = FakeToolRegistry()
            val repository = ScriptPluginRepository(dao, ScriptPluginEngine(), registry, FakeScriptPluginHost())
            val good = sampleManifest("cancel-plugin", "cancel_tool")
            suspend fun install(manifest: ScriptPluginManifest): Result<Unit> = if (local) {
                repository.installLocal(
                    ScriptPluginManifest.json.encodeToString(ScriptPluginManifest.serializer(), manifest),
                    manifest.permissions.toSet(),
                ).map { }
            } else {
                repository.install(manifest, "https://example.com/m.json")
            }
            install(good).getOrThrow()
            val previous = dao.getById(good.id)
            val reloading = CompletableDeferred<Unit>()
            dao.beforeReadEnabled = { reloading.complete(Unit); awaitCancellation() }
            var returned = false
            val update = launch {
                install(good.copy(version = "2.0.0", permissions = listOf("data.write")))
                returned = true
            }
            reloading.await()
            update.cancelAndJoin()

            assertTrue(update.isCancelled)
            assertTrue("cancellation must not become a Result", !returned)
            assertEquals(previous, dao.getById(good.id))
            assertEquals("result", registry.byName("cancel_tool")?.execute(emptyMap())?.output)
        }
    }

    @Test
    fun `a failed registry update preserves the approved manifest grants and live tool`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = FakeToolRegistry()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())
        val good = sampleManifest("update-plugin", "update_tool")
        repository.install(good, "https://example.com/v1.json").getOrThrow()
        val previous = dao.getById(good.id)

        val result = repository.install(
            good.copy(version = "2.0.0", permissions = listOf("data.write"), main = "throw new Error('broken');"),
            "https://example.com/v2.json",
        )

        assertTrue(result.isFailure)
        assertEquals(previous, dao.getById(good.id))
        assertEquals("result", registry.byName("update_tool")?.execute(emptyMap())?.output)
    }

    @Test
    fun `a failed first registry install leaves no persisted row or tool`() = runTest {
        val dao = FakeScriptPluginDao()
        val registry = FakeToolRegistry()
        val repository = ScriptPluginRepository(dao, ScriptPluginEngine(), registry, FakeScriptPluginHost())

        val result = repository.install(sampleManifest().copy(main = "throw new Error('broken');"), "https://example.com/m.json")

        assertTrue(result.isFailure)
        assertTrue(dao.items.isEmpty())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `observeInstalled reflects changes`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = FakeToolRegistry()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("obs-plugin", "obs_tool")
        repository.install(manifest, "https://example.com/manifest.json")

        val list = repository.observeInstalled().first()
        assertEquals(1, list.size)
        assertEquals("obs-plugin", list[0].id)
    }
}
