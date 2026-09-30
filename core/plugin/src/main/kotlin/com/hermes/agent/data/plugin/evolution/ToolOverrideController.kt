package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.plugin.ScriptPluginRepository
import com.hermes.agent.data.plugin.script.ScriptPluginTool
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The descriptor of the first-party tool registered under [name], looking through
 * an active override. Hosts pass this to [EvolutionModuleVetter] and
 * [EvolutionDispatcher] so a fix is always judged against the real built-in.
 */
fun ToolRegistry.builtInDescriptor(name: String): com.hermes.agent.domain.tool.ToolDescriptor? =
    when (val tool = byName(name)) {
        is EvolutionOverrideTool -> tool.builtIn.descriptor
        else -> tool?.descriptor
    }

/** The module that registered the tool under [name], or null for a first-party tool. */
fun ToolRegistry.moduleOwning(name: String): String? = (byName(name) as? ScriptPluginTool)?.pluginId

/** Called when an override trips its failure limit; the host disables the module and tells the user. */
fun interface OverrideRevertListener {
    suspend fun onOverrideTripped(moduleId: String, toolName: String, reason: String)
}

/**
 * A built-in tool with an evolution module standing in front of it.
 *
 * The built-in stays alive underneath. The model keeps seeing the built-in's
 * descriptor (same name, schema and confirmation policy — a module can only
 * make confirmation stricter). Every failed override call falls back to the
 * built-in for that call, and after [maxConsecutiveFailures] failures in a row
 * the override trips: from then on only the built-in runs, and [onTrip] fires
 * once so the module can be switched off.
 */
class EvolutionOverrideTool(
    val builtIn: Tool,
    val override: Tool,
    val moduleId: String,
    private val maxConsecutiveFailures: Int,
    /** Shared per module build, so a module reload does not wipe the failure streak. */
    private val consecutiveFailures: AtomicInteger = AtomicInteger(0),
    private val onTrip: (EvolutionOverrideTool, String) -> Unit,
) : Tool {

    override val descriptor = builtIn.descriptor.copy(
        requiresConfirmation = builtIn.descriptor.requiresConfirmation || override.descriptor.requiresConfirmation,
    )

    private val tripped = AtomicBoolean(false)

    val isTripped: Boolean get() = tripped.get()

    override fun requiresConfirmation(arguments: Map<String, JsonElement>): Boolean =
        builtIn.requiresConfirmation(arguments) || override.requiresConfirmation(arguments)

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        if (tripped.get()) return builtIn.execute(arguments)
        val result = try {
            override.execute(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ToolResult.error(t.message ?: "override threw")
        }
        if (result.success) {
            consecutiveFailures.set(0)
            return result
        }
        val failures = consecutiveFailures.incrementAndGet()
        val reason = result.errorMessage.orEmpty().take(200)
        if (failures >= maxConsecutiveFailures && tripped.compareAndSet(false, true)) {
            onTrip(this, "$failures consecutive failures; last: $reason")
        }
        // Never worse than before the fix: this call still gets the built-in's answer.
        return builtIn.execute(arguments)
    }
}

/**
 * Wires evolution-module overrides into the [ToolRegistry] after every module
 * reload, and unwires them when their module goes away or trips.
 *
 * Fail closed at every step: only enabled modules installed locally from the
 * evolution flow are considered, the target must be a first-party tool (not
 * another module's), and [ToolOverridePolicy] must allow it — checked again
 * here, at wiring time, not only when the module was vetted.
 */
class ToolOverrideController(
    private val registry: ToolRegistry,
    private val repository: ScriptPluginRepository,
    private val dao: ScriptPluginDao,
    private val listener: OverrideRevertListener,
    private val scope: CoroutineScope,
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
) {

    private val mutex = Mutex()
    private val active = mutableMapOf<String, EvolutionOverrideTool>()

    /** "moduleId#sha256" of every module that tripped; not re-wired until reinstalled with new bytes. */
    private val trippedBuilds = mutableSetOf<String>()

    /** Failure streaks per "moduleId#sha256" and tool, surviving re-wiring after a reload. */
    private val streaks = mutableMapOf<String, AtomicInteger>()

    private val _overrides = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Live overrides, tool name → module id, for display. */
    val overrides: StateFlow<Map<String, String>> = _overrides.asStateFlow()

    private var job: Job? = null

    /** Follows [ScriptPluginRepository.reloadGeneration]; call once at app start. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            repository.reloadGeneration.collect {
                runCatching { sync() }.onFailure { Timber.tag(TAG).w(it, "override sync failed") }
            }
        }
    }

    /** Reconciles the registry with the current override candidates. Returns what was refused. */
    suspend fun sync(): List<String> = mutex.withLock {
        val refused = mutableListOf<String>()
        val candidates = repository.overrideCandidates()

        // Unwire anything whose module is gone, disabled, replaced, or tripped.
        active.entries.toList().forEach { (name, wrapper) ->
            val current = candidates[name]
            if (current == null || current.tool !== wrapper.override || wrapper.isTripped) unwireLocked(name)
        }

        candidates.values.forEach { candidate ->
            val name = candidate.toolName
            if (active[name]?.override === candidate.tool) return@forEach
            val row = dao.getById(candidate.moduleId)
            val build = row?.let { "${it.id}#${ScriptPluginRepository.pinnedDigest(it.sourceUrl)}" }
            val reason = when {
                row == null || !row.enabled -> "module is not enabled"
                ScriptPluginRepository.localSourceName(row.sourceUrl) != ScriptPluginRepository.SOURCE_EVOLUTION ->
                    "only evolution modules may override tools"
                build in trippedBuilds -> "this build already failed and was reverted"
                else -> null
            }
            val builtIn = registry.byName(name)
            val policy = when {
                reason != null -> reason
                builtIn == null -> "there is no tool named '$name' to override"
                builtIn is EvolutionOverrideTool || builtIn is ScriptPluginTool -> "only first-party tools can be overridden"
                else -> (ToolOverridePolicy.evaluate(name, builtIn.descriptor) as? ToolOverridePolicy.Decision.Denied)?.reason
            }
            if (policy != null) {
                refused += "${candidate.moduleId} → $name: $policy"
                return@forEach
            }
            val wrapper = EvolutionOverrideTool(
                builtIn = builtIn!!,
                override = candidate.tool,
                moduleId = candidate.moduleId,
                maxConsecutiveFailures = maxConsecutiveFailures,
                consecutiveFailures = streaks.getOrPut("$build/$name") { AtomicInteger(0) },
                onTrip = { tripped, why -> onTripped(tripped, build.orEmpty(), why) },
            )
            registry.register(wrapper)
            active[name] = wrapper
            Timber.tag(TAG).i("tool %s now served by evolution module %s", name, candidate.moduleId)
        }
        publish()
        refused.forEach { Timber.tag(TAG).w("override refused: %s", it) }
        refused
    }

    private fun onTripped(wrapper: EvolutionOverrideTool, build: String, reason: String) {
        val name = wrapper.descriptor.name
        scope.launch {
            mutex.withLock {
                trippedBuilds += build
                if (active[name] === wrapper) unwireLocked(name)
                publish()
            }
            Timber.tag(TAG).w("override %s from %s reverted: %s", name, wrapper.moduleId, reason)
            runCatching { listener.onOverrideTripped(wrapper.moduleId, name, reason) }
                .onFailure { Timber.tag(TAG).w(it, "revert listener failed") }
        }
    }

    /** Puts the built-in back, but only if our wrapper is still what is registered. */
    private fun unwireLocked(name: String) {
        val wrapper = active.remove(name) ?: return
        if (registry.unregisterIfSame(name, wrapper)) registry.register(wrapper.builtIn)
    }

    private fun publish() {
        _overrides.value = active.mapValues { it.value.moduleId }
    }

    companion object {
        private const val TAG = "EvolutionOverride"

        /** Failures in a row before an override is reverted to the built-in. */
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 3
    }
}
