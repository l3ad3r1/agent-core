package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.PluginState
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryImplTest {

    private val dummyContext = object : PluginContext {
        override fun log(tag: String, level: com.hermes.agent.domain.plugin.LogLevel, message: String, throwable: Throwable?) {}
        override suspend fun hostSetting(key: String): String? = null
        override fun hostAppVersion(): Int = 1
    }

    private class FakeToolRegistry : ToolRegistry {
        private val map = mutableMapOf<String, Tool>()
        override fun register(tool: Tool) { map[tool.descriptor.name] = tool }
        override fun unregister(name: String) { map.remove(name) }
        override fun byName(name: String): Tool? = map[name]
        override fun all(): List<Tool> = map.values.toList()
    }

    private class FakePlugin(private val id: String) : Plugin {
        var resumeCalls = 0

        override val manifest = PluginManifest(
            id = id,
            displayName = id,
            versionCode = 1,
            versionName = "1.0",
            author = "test",
            signatureFingerprint = "test",
            capabilities = listOf(
                PluginCapability(
                    name = "cap_$id",
                    description = "test capability",
                    toolDescriptors = listOf(
                        ToolDescriptor(
                            name = "tool_$id",
                            description = "test tool",
                            parameters = emptyList(),
                        ),
                    ),
                ),
            ),
            permissions = listOf(PluginPermission(PermissionType.NETWORK, "test")),
        )

        override fun tools(): List<Tool> = listOf(object : Tool {
            override val descriptor = manifest.capabilities.first().toolDescriptors.first()
            override suspend fun execute(arguments: Map<String, JsonElement>) = ToolResult.ok("ok")
        })

        override suspend fun onLoad(context: PluginContext) = PluginLifecycleResult.Success
        override suspend fun onSuspend() = PluginLifecycleResult.Success
        override suspend fun onResume(): PluginLifecycleResult {
            resumeCalls++
            return PluginLifecycleResult.Success
        }
        override suspend fun onUnload() = PluginLifecycleResult.Success
    }

    private fun makePlugin(id: String) = FakePlugin(id)

    private class FakeGrpcTransport(
        private val available: Boolean,
        override val name: String = "fake",
        private val availabilityFailure: Throwable? = null,
    ) : GrpcPluginTransport {
        var loadCalls = 0
        var suspendCalls = 0
        var resumeCalls = 0
        var unloadCalls = 0

        override suspend fun isAvailable(): Boolean {
            availabilityFailure?.let { throw it }
            return available
        }
        override suspend fun load(plugin: Plugin, context: PluginContext): PluginLifecycleResult {
            loadCalls++
            return plugin.onLoad(context)
        }
        override suspend fun suspend_(plugin: Plugin): PluginLifecycleResult {
            suspendCalls++
            return plugin.onSuspend()
        }
        override suspend fun resume(plugin: Plugin): PluginLifecycleResult {
            resumeCalls++
            return plugin.onResume()
        }
        override suspend fun unload(plugin: Plugin): PluginLifecycleResult {
            unloadCalls++
            return plugin.onUnload()
        }
    }

    private fun makeRegistry(
        remoteTransport: GrpcPluginTransport? = null,
    ): Pair<PluginRegistryImpl, FakeToolRegistry> {
        val toolRegistry = FakeToolRegistry()
        val sandbox = InProcessPluginSandbox(toolRegistry)
        val grpc = GrpcPluginSandbox(toolRegistry, setOfNotNull(remoteTransport))
        val monitor = PluginResourceMonitor()
        val registry = PluginRegistryImpl(sandbox, grpc, dummyContext, monitor)
        return registry to toolRegistry
    }

    @Test
    fun `registerFirstParty adds plugin in INSTALLED state`() = runTest {
        val (registry, _) = makeRegistry()
        val plugin = makePlugin("test.one")
        registry.registerFirstParty(plugin)

        val plugins = registry.observePlugins().value
        assertEquals(1, plugins.size)
        assertEquals(PluginState.INSTALLED, plugins[0].state)
    }

    @Test
    fun `activate moves plugin to ACTIVE state and registers its tools`() = runTest {
        val (registry, toolRegistry) = makeRegistry()
        val plugin = makePlugin("test.two")
        registry.registerFirstParty(plugin)

        val result = registry.activate("test.two")
        assertTrue(result is PluginLifecycleResult.Success)

        val activated = registry.observePlugins().value.first { it.manifest.id == "test.two" }
        assertEquals(PluginState.ACTIVE, activated.state)
        assertNotNull(activated.loadedAt)

        // Tool should be registered with the global ToolRegistry.
        assertNotNull(toolRegistry.byName("tool_test.two"))
    }

    @Test
    fun `suspend_ moves plugin to SUSPENDED state`() = runTest {
        val (registry, _) = makeRegistry()
        registry.registerFirstParty(makePlugin("test.three"))
        registry.activate("test.three")

        registry.suspend_("test.three")

        val suspended = registry.observePlugins().value.first { it.manifest.id == "test.three" }
        assertEquals(PluginState.SUSPENDED, suspended.state)
        assertTrue(registry.activePlugins().isEmpty())
        assertTrue(registry.activeToolDescriptors().isEmpty())
    }

    @Test
    fun `uninstall removes plugin and unloads its tools`() = runTest {
        val (registry, toolRegistry) = makeRegistry()
        registry.registerFirstParty(makePlugin("test.four"))
        registry.activate("test.four")
        assertNotNull(toolRegistry.byName("tool_test.four"))

        registry.uninstall("test.four")

        assertTrue(registry.observePlugins().value.isEmpty())
        assertNull(toolRegistry.byName("tool_test.four"))
    }

    @Test
    fun `byId returns null for unknown plugin`() = runTest {
        val (registry, _) = makeRegistry()
        assertNull(registry.byId("does.not.exist"))
    }

    @Test
    fun `activeToolDescriptors returns tools from ACTIVE plugins only`() = runTest {
        val (registry, _) = makeRegistry()
        registry.registerFirstParty(makePlugin("test.five"))
        registry.registerFirstParty(makePlugin("test.six"))

        // Nothing active yet.
        assertTrue(registry.activeToolDescriptors().isEmpty())

        registry.activate("test.five")
        assertEquals(1, registry.activeToolDescriptors().size)

        registry.activate("test.six")
        assertEquals(2, registry.activeToolDescriptors().size)
    }

    @Test
    fun `remote plugin lifecycle stays on its grpc transport`() = runTest {
        val transport = FakeGrpcTransport(available = true)
        val (registry, toolRegistry) = makeRegistry(transport)
        val plugin = makePlugin("remote.one")

        registry.install(plugin)
        val result = registry.activate("remote.one")

        assertTrue(result is PluginLifecycleResult.Success)
        assertEquals(1, transport.loadCalls)
        assertEquals(PluginState.ACTIVE, registry.byId("remote.one")?.state)
        assertNotNull(toolRegistry.byName("tool_remote.one"))

        registry.suspend_("remote.one")
        registry.activate("remote.one")
        registry.uninstall("remote.one")

        assertEquals(1, transport.suspendCalls)
        assertEquals(1, transport.resumeCalls)
        assertEquals(1, transport.unloadCalls)
        assertNull(toolRegistry.byName("tool_remote.one"))
    }

    @Test
    fun `remote plugin activation fails honestly when transport is unavailable`() = runTest {
        val (registry, toolRegistry) = makeRegistry()
        registry.install(makePlugin("remote.offline"))

        val result = registry.activate("remote.offline")

        assertTrue(result is PluginLifecycleResult.Failure)
        assertTrue((result as PluginLifecycleResult.Failure).recoverable)
        assertEquals(PluginState.ERROR, registry.byId("remote.offline")?.state)
        assertFalse(registry.byId("remote.offline")?.lastError.isNullOrBlank())
        assertNull(toolRegistry.byName("tool_remote.offline"))
    }

    @Test
    fun `failed availability probe falls through to next transport`() = runTest {
        val toolRegistry = FakeToolRegistry()
        val broken = FakeGrpcTransport(
            available = false,
            name = "a-broken",
            availabilityFailure = IllegalStateException("probe failed"),
        )
        val healthy = FakeGrpcTransport(available = true, name = "b-healthy")
        val sandbox = GrpcPluginSandbox(toolRegistry, setOf(healthy, broken))
        val plugin = makePlugin("remote.fallback")

        val result = sandbox.load(plugin, dummyContext)

        assertTrue(result is PluginLifecycleResult.Success)
        assertEquals(0, broken.loadCalls)
        assertEquals(1, healthy.loadCalls)
        assertNotNull(toolRegistry.byName("tool_remote.fallback"))
    }

    @Test
    fun `activating suspended plugin resumes its owning sandbox`() = runTest {
        val (registry, _) = makeRegistry()
        val plugin = makePlugin("test.resume")
        registry.registerFirstParty(plugin)
        registry.activate("test.resume")
        registry.suspend_("test.resume")

        val result = registry.activate("test.resume")

        assertTrue(result is PluginLifecycleResult.Success)
        assertEquals(1, plugin.resumeCalls)
        assertEquals(PluginState.ACTIVE, registry.byId("test.resume")?.state)
    }
}
