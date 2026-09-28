package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginInstance
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginRegistry
import com.hermes.agent.domain.plugin.PluginSandbox
import com.hermes.agent.domain.plugin.PluginState
import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [PluginRegistry] implementation.
 *
 * Maintains an in-memory map of plugin id → [PluginInstance] and
 * delegates load/unload operations to the appropriate [PluginSandbox]
 * (in-process for first-party, gRPC for third-party).
 *
 * Which plugins are switched on is kept in [activeStore], and [restoreActive]
 * switches them back on at startup. Before that, every plugin came back off
 * after a restart, so its tools silently disappeared from the agent.
 */
@Singleton
class PluginRegistryImpl @Inject constructor(
    private val inProcessSandbox: InProcessPluginSandbox,
    private val grpcSandbox: GrpcPluginSandbox,
    private val pluginContext: PluginContext,
    private val resourceMonitor: PluginResourceMonitor,
    private val activeStore: ActivePluginStore? = null,
) : PluginRegistry {

    private val mutex = Mutex()
    private val _plugins = MutableStateFlow<List<PluginInstance>>(emptyList())
    override fun observePlugins(): StateFlow<List<PluginInstance>> = _plugins.asStateFlow()

    private data class RegisteredPlugin(
        val plugin: Plugin,
        val sandbox: PluginSandbox,
    )

    private val registeredPlugins = mutableMapOf<String, RegisteredPlugin>()
    private val loadedPlugins = mutableMapOf<String, RegisteredPlugin>()

    override suspend fun activePlugins(): List<Plugin> = mutex.withLock {
        val activeIds = _plugins.value
            .filter { it.state == PluginState.ACTIVE }
            .mapTo(mutableSetOf()) { it.manifest.id }
        loadedPlugins.filterKeys { it in activeIds }.values.map { it.plugin }
    }

    override suspend fun activeToolDescriptors(): List<ToolDescriptor> = mutex.withLock {
        val activeIds = _plugins.value
            .filter { it.state == PluginState.ACTIVE }
            .mapTo(mutableSetOf()) { it.manifest.id }
        loadedPlugins
            .filterKeys { it in activeIds }
            .values
            .flatMap { registration ->
                registration.plugin.manifest.capabilities.flatMap { capability ->
                    capability.toolDescriptors
                }
            }
    }

    override suspend fun byId(id: String): PluginInstance? = mutex.withLock {
        _plugins.value.firstOrNull { it.manifest.id == id }
    }

    override suspend fun install(plugin: Plugin): PluginInstance = mutex.withLock {
        registeredPlugins[plugin.manifest.id] = RegisteredPlugin(plugin, grpcSandbox)
        val instance = PluginInstance(
            manifest = plugin.manifest,
            state = PluginState.INSTALLED,
        )
        _plugins.value = (_plugins.value.filterNot { it.manifest.id == plugin.manifest.id } + instance)
            .sortedBy { it.manifest.displayName }
        Timber.tag("PluginRegistry").i("installed %s", plugin.manifest.id)
        instance
    }

    override suspend fun activate(id: String): PluginLifecycleResult = mutex.withLock {
        val instance = _plugins.value.firstOrNull { it.manifest.id == id }
            ?: return@withLock PluginLifecycleResult.Failure("unknown plugin: $id")
        val registered = registeredPlugins[id]
            ?: return@withLock PluginLifecycleResult.Failure("plugin $id not registered")
        val loaded = loadedPlugins[id]

        if (loaded != null) {
            if (instance.state == PluginState.ACTIVE) {
                return@withLock PluginLifecycleResult.Success
            }
            val result = loaded.sandbox.resume(loaded.plugin)
            if (result is PluginLifecycleResult.Success) {
                updateState(id, PluginState.ACTIVE)
                resourceMonitor.startMonitoring(id)
                remember(id, active = true)
            } else {
                updateState(id, PluginState.ERROR, lastError = (result as PluginLifecycleResult.Failure).message)
            }
            return@withLock result
        }

        val result = registered.sandbox.load(registered.plugin, pluginContext)
        if (result is PluginLifecycleResult.Success) {
            loadedPlugins[id] = registered
            updateState(id, PluginState.ACTIVE, loadedAt = System.currentTimeMillis())
            resourceMonitor.startMonitoring(id)
            remember(id, active = true)
        } else {
            val msg = (result as PluginLifecycleResult.Failure).message
            updateState(id, PluginState.ERROR, lastError = msg)
        }
        result
    }

    override suspend fun suspend_(id: String): PluginLifecycleResult = mutex.withLock {
        val loaded = loadedPlugins[id] ?: return@withLock PluginLifecycleResult.Failure("plugin $id not active")
        val result = loaded.sandbox.suspend_(loaded.plugin)
        if (result is PluginLifecycleResult.Success) {
            updateState(id, PluginState.SUSPENDED)
            resourceMonitor.stopMonitoring(id)
            remember(id, active = false)
        }
        result
    }

    override suspend fun uninstall(id: String) = mutex.withLock {
        val loaded = loadedPlugins.remove(id)
        if (loaded != null) {
            loaded.sandbox.unload(loaded.plugin)
            resourceMonitor.stopMonitoring(id)
        }
        registeredPlugins.remove(id)
        _plugins.value = _plugins.value.filterNot { it.manifest.id == id }
        remember(id, active = false)
        Timber.tag("PluginRegistry").i("uninstalled %s", id)
    }

    /** Switches back on every registered plugin that was on before; call once at startup. */
    suspend fun restoreActive() {
        val wanted = activeStore?.load().orEmpty()
        for (id in wanted) {
            if (id in registeredPlugins) {
                val result = activate(id)
                if (result is PluginLifecycleResult.Failure) {
                    Timber.tag("PluginRegistry").w("could not restore %s: %s", id, result.message)
                }
            }
        }
    }

    // --- helpers ---

    private fun remember(id: String, active: Boolean) {
        val store = activeStore ?: return
        val ids = store.load()
        store.save(if (active) ids + id else ids - id)
    }

    /** Called by [com.hermes.agent.di.PluginsModule] at app startup. */
    fun registerFirstParty(plugin: Plugin) {
        registeredPlugins[plugin.manifest.id] = RegisteredPlugin(plugin, inProcessSandbox)
        // Auto-install on registration so the Plugins UI shows it immediately.
        val instance = PluginInstance(
            manifest = plugin.manifest,
            state = PluginState.INSTALLED,
        )
        _plugins.value = (_plugins.value.filterNot { it.manifest.id == plugin.manifest.id } + instance)
            .sortedBy { it.manifest.displayName }
    }

    private fun updateState(
        id: String,
        state: PluginState,
        loadedAt: Long? = null,
        lastError: String? = null,
    ): PluginLifecycleResult {
        _plugins.value = _plugins.value.map { inst ->
            if (inst.manifest.id == id) {
                inst.copy(
                    state = state,
                    loadedAt = loadedAt ?: inst.loadedAt,
                    lastError = lastError ?: (if (state == PluginState.ERROR) inst.lastError else null),
                )
            } else inst
        }
        return PluginLifecycleResult.Success
    }
}

/** Where the set of switched-on plugin ids is kept between runs. */
interface ActivePluginStore {
    fun load(): Set<String>
    fun save(ids: Set<String>)
}
