package com.hermes.agent.data.plugin.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sandboxed JavaScript runtime for `tool` modules, backed by Mozilla Rhino in
 * interpreted mode. Ported from Octo Jotter's `ScriptEngine`, which has been
 * running community plugins in production.
 *
 * Safety posture, unchanged from that original:
 *  - interpreted mode (`optimizationLevel = -1`), so nothing is compiled to
 *    bytecode and no classloading tricks are reachable;
 *  - a [Context.setClassShutter] that denies *every* Java class, cutting off
 *    reflection, file IO, and sockets;
 *  - a sealed safe standard scope (no `Packages`, no `getClass`);
 *  - a per-run instruction budget and wall-clock deadline that abort runaway
 *    or infinite-loop scripts (see [RunGuard]).
 *
 * A module's only capability is the injected `hermes` object. Everything that
 * reaches host data is permission-gated against the manifest.
 *
 * Rhino [Context]s are not thread-safe and a module's top-level scope must stay
 * alive so its registered functions remain callable, so access to any one
 * module is serialized through that module's own lock on a background
 * dispatcher. [mutex] guards only the [plugins] map, and is never held across a
 * script call — otherwise one slow or hostile module would block every other
 * module's tools and all future loads.
 */
@Singleton
class ScriptPluginEngine @Inject constructor() {

    /** A module to load: its id, JS source, and the permissions the user granted. */
    data class PluginSpec(
        val id: String,
        val source: String,
        val permissions: Set<String>,
    )

    private class RegisteredTool(val pluginId: String, val name: String, val fn: Function)
    private class LoadedPlugin(
        val scope: Scriptable,
        val tools: MutableList<RegisteredTool> = mutableListOf(),
        /** Serializes calls into this module's scope, and only this module's. */
        val lock: Mutex = Mutex(),
    )

    private val mutex = Mutex()
    private val plugins = LinkedHashMap<String, LoadedPlugin>()
    private val factory = SandboxContextFactory()

    /** Host callbacks, set once by the app layer. Null keeps the engine pure. */
    @Volatile
    var host: ScriptPluginHost? = null

    /**
     * Replaces every loaded module with [specs].
     *
     * A module whose script throws at load time is skipped and reported, never
     * propagated: one broken third-party module must not stop the others from
     * loading or take down the agent.
     */
    suspend fun reload(specs: List<PluginSpec>): List<String> = withContext(Dispatchers.Default) {
        val failures = mutableListOf<String>()
        mutex.withLock {
            plugins.clear()
            for (spec in specs) {
                try {
                    val cx = factory.enterContext()
                    try {
                        // A module's top-level code runs under the same budget
                        // and deadline as its tools: `while (true) {}` at load
                        // time must not wedge the load of every other module.
                        RunGuard.begin(cx)
                        val scope = cx.initSafeStandardObjects(null, true)
                        val loaded = LoadedPlugin(scope)
                        installApi(cx, scope, spec.id, spec.permissions, loaded)
                        cx.evaluateString(scope, spec.source, spec.id, 1, null)
                        plugins[spec.id] = loaded
                    } finally {
                        RunGuard.end(cx)
                        Context.exit()
                    }
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Module %s failed to load", spec.id)
                    failures += "${spec.id}: ${t.message ?: "failed to load"}"
                }
            }
        }
        failures
    }

    /** Tool names a module actually registered at runtime. */
    suspend fun registeredToolNames(pluginId: String): List<String> = mutex.withLock {
        plugins[pluginId]?.tools?.map { it.name }.orEmpty()
    }

    /**
     * Invokes one registered tool with [arguments], returning its output as text.
     *
     * JS values come back as whatever the module returned; anything that is not
     * already a string is serialized to JSON so structured returns survive into
     * the model's context intact.
     */
    suspend fun execute(
        pluginId: String,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): Result<String> = withContext(Dispatchers.Default) {
        // Look the module up under the map lock, then release it: the script
        // call below runs under the module's own lock, so a module that spins
        // cannot block other modules or a concurrent reload.
        val plugin = mutex.withLock { plugins[pluginId] }
            ?: return@withContext Result.failure(IllegalStateException("Module '$pluginId' is not loaded"))

        plugin.lock.withLock {
            val tool = plugin.tools.firstOrNull { it.name == toolName }
                ?: return@withLock Result.failure(
                    IllegalStateException("Module '$pluginId' did not register a tool named '$toolName'"),
                )
            try {
                val cx = factory.enterContext()
                try {
                    RunGuard.begin(cx)
                    val argsObject = arguments.toJsObject(cx, plugin.scope)
                    val result = tool.fn.call(cx, plugin.scope, plugin.scope, arrayOf<Any>(argsObject))
                    Result.success(result.toOutputString())
                } finally {
                    RunGuard.end(cx)
                    Context.exit()
                }
            } catch (t: Throwable) {
                Result.failure(Exception(t.message ?: "Module tool failed"))
            }
        }
    }

    private fun installApi(
        cx: Context,
        scope: Scriptable,
        pluginId: String,
        permissions: Set<String>,
        loaded: LoadedPlugin,
    ) {
        val hermes = cx.newObject(scope)

        // hermes.registerTool(name, fn) — the entry point every module uses.
        ScriptableObject.putProperty(
            hermes,
            "registerTool",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    val name = args?.getOrNull(0)?.let { Context.toString(it) } ?: return Undefined.instance
                    val fn = args.getOrNull(1) as? Function ?: return Undefined.instance
                    loaded.tools.add(RegisteredTool(pluginId, name, fn))
                    return Undefined.instance
                }
            },
        )

        ScriptableObject.putProperty(
            hermes,
            "log",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    host?.log(pluginId, args?.getOrNull(0)?.let { Context.toString(it) } ?: "")
                    return Undefined.instance
                }
            },
        )

        // hermes.http.get(url) — permission-gated, and routed through the host's
        // client so a module never opens its own socket.
        val http = cx.newObject(scope)
        ScriptableObject.putProperty(
            http,
            "get",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    requirePermission(permissions, ScriptPluginPermissions.NETWORK)
                    val url = args?.getOrNull(0)?.let { Context.toString(it) } ?: return ""
                    return host?.httpGet(pluginId, url) ?: ""
                }
            },
        )
        ScriptableObject.putProperty(hermes, "http", http)

        // hermes.data.* — permission-gated host storage.
        val data = cx.newObject(scope)
        ScriptableObject.putProperty(
            data,
            "read",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    requirePermission(permissions, ScriptPluginPermissions.DATA_READ)
                    val collection = args?.getOrNull(0)?.let { Context.toString(it) } ?: return ""
                    val query = args.getOrNull(1)?.let { Context.toString(it) } ?: ""
                    return host?.readData(pluginId, collection, query) ?: ""
                }
            },
        )
        ScriptableObject.putProperty(
            data,
            "write",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    requirePermission(permissions, ScriptPluginPermissions.DATA_WRITE)
                    val collection = args?.getOrNull(0)?.let { Context.toString(it) } ?: return ""
                    val payload = args.getOrNull(1)?.let { Context.toString(it) } ?: ""
                    return host?.writeData(pluginId, collection, payload) ?: ""
                }
            },
        )
        ScriptableObject.putProperty(hermes, "data", data)

        ScriptableObject.putProperty(scope, "hermes", hermes)
    }

    /** Throws a JS-catchable error when a module calls an API it was not granted. */
    private fun requirePermission(granted: Set<String>, permission: String) {
        if (permission !in granted) {
            throw IllegalStateException(
                "Permission denied: '$permission' was not granted to this module.",
            )
        }
    }

    private fun Map<String, JsonElement>.toJsObject(cx: Context, scope: Scriptable): Scriptable {
        val obj = cx.newObject(scope)
        forEach { (key, value) ->
            ScriptableObject.putProperty(obj, key, value.toJsValue(cx, scope))
        }
        return obj
    }

    private fun JsonElement.toJsValue(cx: Context, scope: Scriptable): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            content == "true" -> true
            content == "false" -> false
            else -> content.toDoubleOrNull() ?: content
        }
        is JsonArray -> cx.newArray(scope, map { it.toJsValue(cx, scope) }.toTypedArray())
        is JsonObject -> {
            val obj = cx.newObject(scope)
            forEach { (key, value) -> ScriptableObject.putProperty(obj, key, value.toJsValue(cx, scope)) }
            obj
        }
    }

    /** Converts a returned JS value to the text the model will read. */
    private fun Any?.toOutputString(): String = when (this) {
        null, Undefined.instance -> ""
        is CharSequence -> toString()
        is NativeArray, is NativeObject -> runCatching {
            // Rhino's own JSON.stringify, so nested plain objects survive.
            NativeJsonStringify.stringify(this)
        }.getOrElse { Context.toString(this) }
        else -> Context.toString(this)
    }

    /**
     * Per-run accounting for the instruction budget and the wall-clock deadline.
     *
     * Rhino hands [ContextFactory.observeInstructionCount] the count *since the
     * previous callback* and then resets its own counter, so the value is always
     * approximately [OBSERVER_THRESHOLD] and never the running total. Comparing
     * it directly against a budget is a comparison that can never be true — the
     * guard has to accumulate the windows itself.
     *
     * The deadline is not redundant with the budget: instructions are only
     * counted while the interpreter is running JS, so a script blocked inside a
     * host callback burns wall-clock time without moving the instruction count.
     */
    private class RunGuard {
        var instructions: Long = 0L
        var deadlineNanos: Long = 0L

        companion object {
            private const val KEY = "hermes.script.runGuard"

            fun begin(cx: Context) {
                cx.putThreadLocal(
                    KEY,
                    RunGuard().apply {
                        instructions = 0L
                        deadlineNanos = System.nanoTime() + RUN_DEADLINE_NANOS
                    },
                )
            }

            fun end(cx: Context) = cx.removeThreadLocal(KEY)

            /** Charges one observer window; throws [ScriptAbort] when a limit is hit. */
            fun charge(cx: Context, window: Int) {
                val guard = cx.getThreadLocal(KEY) as? RunGuard ?: return
                guard.instructions += window.toLong()
                if (guard.instructions > INSTRUCTION_BUDGET) {
                    throw ScriptAbort(
                        "Module exceeded its instruction budget " +
                            "(${guard.instructions} > $INSTRUCTION_BUDGET instructions)",
                    )
                }
                if (System.nanoTime() > guard.deadlineNanos) {
                    throw ScriptAbort("Module exceeded its ${RUN_DEADLINE_MS}ms time limit")
                }
            }
        }
    }

    /**
     * Thrown to unwind a module that blew its budget or deadline.
     *
     * This is an [Error] rather than an [Exception] on purpose: Rhino wraps
     * thrown [Exception]s so a script's own `try { ... } catch (e) { }` can
     * swallow them, which would let a hostile module defeat the guard simply by
     * wrapping its loop. [Error] propagates past JS catch clauses.
     */
    class ScriptAbort(message: String) : Error(message)

    /** Denies Java-class access and enforces the per-run budget and deadline. */
    private class SandboxContextFactory : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = OBSERVER_THRESHOLD
            cx.setClassShutter { false }
            return cx
        }

        override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
            RunGuard.charge(cx ?: return, instructionCount)
        }
    }

    private companion object {
        const val TAG = "ScriptPlugin"

        /** How often Rhino calls the observer, in interpreted instructions. */
        const val OBSERVER_THRESHOLD = 10_000

        /** Total interpreted instructions one load or one tool call may use. */
        const val INSTRUCTION_BUDGET = 5_000_000L

        /** Wall-clock ceiling for one load or one tool call. */
        const val RUN_DEADLINE_MS = 5_000L
        const val RUN_DEADLINE_NANOS = RUN_DEADLINE_MS * 1_000_000L
    }
}

/** Rhino has no public JSON helper, so reach its builtin through the scope. */
private object NativeJsonStringify {
    fun stringify(value: Any): String {
        val cx = Context.getCurrentContext() ?: return value.toString()
        val scope = (value as? Scriptable)?.parentScope ?: return value.toString()
        val json = ScriptableObject.getProperty(scope, "JSON") as? Scriptable ?: return value.toString()
        val fn = ScriptableObject.getProperty(json, "stringify") as? Function ?: return value.toString()
        return Context.toString(fn.call(cx, scope, json, arrayOf(value)))
    }
}
