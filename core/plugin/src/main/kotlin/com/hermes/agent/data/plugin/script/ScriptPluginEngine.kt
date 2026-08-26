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
 *  - an instruction budget that aborts runaway or infinite-loop scripts.
 *
 * A module's only capability is the injected `hermes` object. Everything that
 * reaches host data is permission-gated against the manifest.
 *
 * Rhino [Context]s are not thread-safe and a module's top-level scope must stay
 * alive so its registered functions remain callable, so all engine access is
 * serialized through [mutex] on a background dispatcher.
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
                        val scope = cx.initSafeStandardObjects(null, true)
                        val loaded = LoadedPlugin(scope)
                        installApi(cx, scope, spec.id, spec.permissions, loaded)
                        cx.evaluateString(scope, spec.source, spec.id, 1, null)
                        plugins[spec.id] = loaded
                    } finally {
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
        mutex.withLock {
            val plugin = plugins[pluginId]
                ?: return@withLock Result.failure(IllegalStateException("Module '$pluginId' is not loaded"))
            val tool = plugin.tools.firstOrNull { it.name == toolName }
                ?: return@withLock Result.failure(
                    IllegalStateException("Module '$pluginId' did not register a tool named '$toolName'"),
                )
            try {
                val cx = factory.enterContext()
                try {
                    val argsObject = arguments.toJsObject(cx, plugin.scope)
                    val result = tool.fn.call(cx, plugin.scope, plugin.scope, arrayOf<Any>(argsObject))
                    Result.success(result.toOutputString())
                } finally {
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

    /** Denies Java-class access and enforces the per-run instruction budget. */
    private class SandboxContextFactory : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = 10_000
            cx.setClassShutter { false }
            return cx
        }

        override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
            if (instructionCount > INSTRUCTION_BUDGET) {
                throw Error("Module exceeded its instruction budget")
            }
        }
    }

    private companion object {
        const val TAG = "ScriptPlugin"
        const val INSTRUCTION_BUDGET = 5_000_000
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
