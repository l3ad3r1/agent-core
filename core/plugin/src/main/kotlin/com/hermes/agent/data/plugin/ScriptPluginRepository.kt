package com.hermes.agent.data.plugin

import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.plugin.script.ModuleHosts
import com.hermes.agent.data.plugin.script.ScriptModuleDigest
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptPluginHost
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptPluginPermissions
import com.hermes.agent.data.plugin.script.ScriptPluginRegistry
import com.hermes.agent.data.plugin.script.ScriptPluginRegistryEntry
import com.hermes.agent.data.plugin.script.ScriptPluginTool
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs, enables, and loads script modules.
 *
 * Owns the whole lifecycle: fetch the public registry, fetch and validate one
 * manifest, persist it with the permissions the user approved, then hand the
 * enabled set to [ScriptPluginEngine] and register the resulting tools so the
 * agent can call them.
 */
@Singleton
class ScriptPluginRepository @Inject constructor(
    private val dao: ScriptPluginDao,
    private val engine: ScriptPluginEngine,
    private val toolRegistry: ToolRegistry,
    host: ScriptPluginHost,
) {

    init {
        // The engine stays host-agnostic and unit-testable without this being
        // wired; here is where the real, Room/OkHttp-backed implementation is
        // handed to it for actual installs.
        engine.host = host
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Exact entries this repository published. Keeping the instance matters:
     * unregistering by name alone could remove a built-in that was registered
     * after a module reload.
     */
    private val registeredTools = mutableMapOf<String, Tool>()

    /**
     * A tool a locally installed module supplies under the name of a tool that
     * already exists (its manifest's [ScriptPluginManifest.overrides]).
     *
     * The repository never publishes these into the [ToolRegistry]: replacing a
     * registered tool changes what the model sees and how it is confirmed, so
     * whether and how to shadow the existing tool is the host app's decision.
     */
    data class OverrideCandidate(val moduleId: String, val toolName: String, val tool: Tool)

    /** Serializes reloads: install, enable, and app start can all ask for one at once. */
    private val reloadMutex = Mutex()

    @Volatile
    private var overrides: Map<String, OverrideCandidate> = emptyMap()

    private val _reloadGeneration = MutableStateFlow(0L)

    /**
     * Bumped after every [reloadEnabled], whoever triggered it, so a host that
     * layers state on top of the loaded modules (override wiring) can resync.
     */
    val reloadGeneration: StateFlow<Long> = _reloadGeneration.asStateFlow()

    /** Override candidates from the last reload, keyed by tool name. */
    fun overrideCandidates(): Map<String, OverrideCandidate> = overrides

    fun observeInstalled(): Flow<List<ScriptPluginEntity>> = dao.observeAll()

    suspend fun fetchRegistry(url: String = DEFAULT_REGISTRY_URL): Result<List<ScriptPluginRegistryEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = httpGet(url)
                ScriptPluginManifest.json.decodeFromString<ScriptPluginRegistry>(body).plugins
            }
        }

    /** Fetches and validates a manifest without installing it, for the approval prompt. */
    suspend fun fetchManifest(entry: ScriptPluginRegistryEntry): Result<ScriptPluginManifest> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(entry.manifestUrl.startsWith("https://")) {
                    "Module manifests must be served over HTTPS"
                }
                val body = httpGet(entry.manifestUrl)
                verifyDigest(entry, body)
                val manifest = ScriptPluginManifest.json.decodeFromString<ScriptPluginManifest>(body)
                validate(manifest)
                manifest
            }
        }

    /** Refuses a manifest whose bytes do not match the digest the registry pinned. */
    private fun verifyDigest(entry: ScriptPluginRegistryEntry, body: String) {
        when (val result = ScriptModuleDigest.check(entry.sha256, body)) {
            is ScriptModuleDigest.Result.Match -> Unit
            is ScriptModuleDigest.Result.Unpinned ->
                Timber.tag(TAG).w("Module %s has no pinned digest in the registry", entry.id)
            is ScriptModuleDigest.Result.Mismatch ->
                throw IllegalStateException(with(ScriptModuleDigest) { result.message(entry.id) })
        }
    }

    /**
     * Persists [manifest] as installed, granting exactly the permissions it
     * declared and the user approved, then reloads the engine.
     */
    suspend fun install(
        manifest: ScriptPluginManifest,
        sourceUrl: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            validate(manifest)
            // The local namespace means "built and pinned on this device"; reload trusts it
            // with overrides, so only installLocal may write it.
            require(!isLocalSource(sourceUrl)) { "A registry install cannot use a local module source" }
            require(manifest.overrides.isEmpty()) {
                "Only a module built and approved on this device may override an existing tool"
            }
            require(dao.getById(manifest.id)?.let { isLocalSource(it.sourceUrl) } != true) {
                "Module id '${manifest.id}' belongs to a module built on this device; " +
                    "a registry install may not replace it"
            }
            require(manifest.tools.none { spec ->
                toolRegistry.byName(spec.name)?.let { existing ->
                    registeredTools[spec.name] !== existing
                } ?: false
            }) {
                "Module tool name conflicts with an existing tool"
            }
            dao.upsert(
                ScriptPluginEntity(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    author = manifest.author,
                    description = manifest.description,
                    manifestJson = ScriptPluginManifest.json.encodeToString(manifest),
                    grantedPermissions = manifest.permissions.joinToString(","),
                    enabled = true,
                    sourceUrl = sourceUrl,
                ),
            )
            reloadEnabled()
            Unit
        }
    }

    /**
     * Installs a module that was produced and reviewed on this device rather than
     * fetched from the registry — the path the app's feature-evolution flow uses.
     *
     * [manifestJson] is persisted verbatim: those are the exact bytes the user
     * approved, and their SHA-256 is pinned into the row's source. Every later
     * [reloadEnabled] recomputes it and refuses to load the module if the stored
     * manifest no longer matches, so a row edited behind the user's back does not
     * run. When [expectedSha256] is given (the digest shown at approval), bytes
     * that differ from it are refused before anything is parsed or stored.
     *
     * Only permissions both declared by the manifest and in [approvedPermissions]
     * are granted. Unlike [install], the manifest may declare
     * [ScriptPluginManifest.overrides]; those tools are exposed through
     * [overrideCandidates] instead of being registered.
     *
     * A module that fails to load after install is switched off again (or, for an
     * upgrade, the previously installed row is put back) and the failure is
     * returned, so a broken local build never lingers half-enabled.
     */
    suspend fun installLocal(
        manifestJson: String,
        approvedPermissions: Set<String>,
        source: String = SOURCE_EVOLUTION,
        expectedSha256: String? = null,
    ): Result<ScriptPluginManifest> = withContext(Dispatchers.IO) {
        runCatching {
            require(LOCAL_SOURCE_NAME.matches(source)) { "Invalid local module source '$source'" }
            require(manifestJson.length <= MAX_LOCAL_MANIFEST_CHARS) {
                "Local module manifest is larger than $MAX_LOCAL_MANIFEST_CHARS characters"
            }
            if (expectedSha256 != null) {
                when (val result = ScriptModuleDigest.check(expectedSha256, manifestJson)) {
                    is ScriptModuleDigest.Result.Match -> Unit
                    is ScriptModuleDigest.Result.Unpinned ->
                        throw IllegalArgumentException("A blank digest cannot pin a local module")
                    is ScriptModuleDigest.Result.Mismatch -> throw IllegalStateException(
                        with(ScriptModuleDigest) { result.message("local module") },
                    )
                }
            }
            val manifest = ScriptPluginManifest.json.decodeFromString<ScriptPluginManifest>(manifestJson)
            validate(manifest)
            validateLocal(manifest)

            val existing = dao.getById(manifest.id)
            require(existing == null || isLocalSource(existing.sourceUrl)) {
                "Module id '${manifest.id}' is already installed from the registry"
            }
            require(
                manifest.tools.filter { it.name !in manifest.overrides }.none { spec ->
                    toolRegistry.byName(spec.name)?.let { registeredTools[spec.name] !== it } ?: false
                },
            ) { "Module tool name conflicts with an existing tool" }

            val digest = ScriptModuleDigest.sha256Hex(manifestJson)
            dao.upsert(
                ScriptPluginEntity(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    author = manifest.author,
                    description = manifest.description,
                    manifestJson = manifestJson,
                    grantedPermissions = manifest.permissions
                        .filter { it in approvedPermissions }
                        .distinct()
                        .joinToString(","),
                    enabled = true,
                    sourceUrl = localSourceUrl(source, digest),
                ),
            )
            val failures = reloadEnabled().filter { it.startsWith("${manifest.id}:") }
            if (failures.isNotEmpty()) {
                if (existing != null) {
                    // An upgrade that does not load puts the version that was there back,
                    // rather than leaving the module switched off on the broken bytes.
                    dao.upsert(existing)
                    reloadEnabled()
                } else {
                    setEnabled(manifest.id, false)
                }
                throw IllegalStateException(failures.joinToString("; "))
            }
            manifest
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        reloadEnabled()
    }

    suspend fun uninstall(id: String) {
        dao.delete(id)
        reloadEnabled()
    }

    /**
     * Loads every enabled module and republishes its tools.
     *
     * Previously registered tools are unregistered first so a disabled or
     * uninstalled module's tools stop being offered to the model immediately,
     * rather than lingering until the next process restart.
     */
    suspend fun reloadEnabled(): List<String> = reloadMutex.withLock {
        try {
            reloadLocked()
        } finally {
            _reloadGeneration.value = _reloadGeneration.value + 1
        }
    }

    private suspend fun reloadLocked(): List<String> {
        registeredTools.forEach { (name, tool) ->
            runCatching { toolRegistry.unregisterIfSame(name, tool) }
        }
        registeredTools.clear()
        overrides = emptyMap()

        val refused = mutableListOf<String>()
        // Fail closed: a local module whose stored bytes no longer match the
        // digest pinned when the user approved them is not handed to the engine.
        val installed = dao.getEnabled().filter { entity ->
            localPinHolds(entity).also { holds ->
                if (!holds) {
                    Timber.tag(TAG).w("Local module %s no longer matches its pinned digest", entity.id)
                    refused += "${entity.id}: manifest no longer matches the digest pinned at install; not loaded"
                }
            }
        }
        val specs = installed.mapNotNull { entity ->
            runCatching {
                val manifest = ScriptPluginManifest.json
                    .decodeFromString<ScriptPluginManifest>(entity.manifestJson)
                ScriptPluginEngine.PluginSpec(
                    id = manifest.id,
                    source = manifest.main,
                    // Grant only what was approved at install time, not whatever
                    // the manifest happens to ask for now.
                    permissions = entity.grantedPermissions
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                    // From the manifest stored at install, which is the list the user saw.
                    hosts = manifest.hosts,
                )
            }.onFailure {
                Timber.tag(TAG).w(it, "Could not prepare module %s", entity.id)
            }.getOrNull()
        }

        val failures = (refused + engine.reload(specs)).toMutableList()
        val candidates = mutableMapOf<String, OverrideCandidate>()

        installed.forEach { entity ->
            val manifest = runCatching {
                ScriptPluginManifest.json.decodeFromString<ScriptPluginManifest>(entity.manifestJson)
            }.getOrNull() ?: return@forEach

            // Only publish a tool the script actually registered: a manifest may
            // declare a tool whose registerTool call never ran.
            val live = engine.registeredToolNames(manifest.id).toSet()
            // Overrides are only ever honoured for a module installed locally.
            val overriding = if (isLocalSource(entity.sourceUrl)) manifest.overrides.toSet() else emptySet()
            manifest.tools.filter { it.name in live }.forEach { spec ->
                runCatching {
                    val granted = entity.grantedPermissions.split(",").map { it.trim() }.toSet()
                    val tool = ScriptPluginTool(spec.toDescriptor(granted), manifest.id, engine)
                    if (spec.name in overriding) {
                        check(spec.name !in candidates) {
                            "Tool '${spec.name}' is already overridden by ${candidates[spec.name]?.moduleId}"
                        }
                        candidates[spec.name] = OverrideCandidate(manifest.id, spec.name, tool)
                        return@runCatching
                    }
                    check(toolRegistry.registerIfAbsent(tool)) {
                        "Tool name '${spec.name}' conflicts with an existing tool"
                    }
                    registeredTools[spec.name] = tool
                }.onFailure {
                    Timber.tag(TAG).w(it, "Could not register %s from %s", spec.name, manifest.id)
                    failures += "${manifest.id}: ${it.message ?: "could not register ${spec.name}"}"
                }
            }
        }
        overrides = candidates.toMap()
        return failures
    }

    /** True for every registry module; for a local one, only while its pinned digest still holds. */
    private fun localPinHolds(entity: ScriptPluginEntity): Boolean {
        if (!isLocalSource(entity.sourceUrl)) return true
        val pinned = pinnedDigest(entity.sourceUrl) ?: return false
        return ScriptModuleDigest.check(pinned, entity.manifestJson) is ScriptModuleDigest.Result.Match
    }

    private fun validateLocal(manifest: ScriptPluginManifest) {
        val names = manifest.tools.map { it.name }
        require(names.toSet().size == names.size) { "Module declares the same tool name twice" }
        require(manifest.overrides.toSet().size == manifest.overrides.size) {
            "Module lists the same override twice"
        }
        manifest.overrides.forEach { name ->
            require(name in names) { "Override '$name' does not name one of the module's own tools" }
        }
    }

    private fun validate(manifest: ScriptPluginManifest) {
        require(manifest.id.isNotBlank()) { "Module id is required" }
        require(manifest.type == ScriptPluginManifest.TYPE_TOOL) {
            "Unsupported module type '${manifest.type}'"
        }
        require(manifest.main.isNotBlank()) { "Module has no script" }
        require(manifest.tools.isNotEmpty()) { "Module declares no tools" }
        manifest.tools.forEach { tool ->
            require(tool.name.isNotBlank()) { "Every tool needs a name" }
        }
        manifest.hosts.forEach { entry ->
            require(ModuleHosts.isValidEntry(entry)) {
                "Module host '$entry' must be a lowercase hostname such as api.example.com or *.example.com"
            }
        }
        require(manifest.hosts.isEmpty() || ScriptPluginPermissions.NETWORK in manifest.permissions) {
            "Module lists hosts but does not ask for network access"
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val TAG = "ScriptPluginRepo"

        /** The source the app's feature-evolution flow installs under. */
        const val SOURCE_EVOLUTION = "evolution"

        /** Upper bound on a locally installed manifest, script included. */
        const val MAX_LOCAL_MANIFEST_CHARS = 64 * 1024

        private const val LOCAL_PREFIX = "local:"
        private val LOCAL_SOURCE_NAME = Regex("^[a-z0-9-]{1,32}$")
        private val LOCAL_SOURCE_URL = Regex("^local:([a-z0-9-]{1,32})#sha256=([0-9a-f]{64})$")

        /** `local:<source>#sha256=<hex>` — where a local module came from, and the bytes approved. */
        fun localSourceUrl(source: String, sha256: String): String = "$LOCAL_PREFIX$source#sha256=$sha256"

        fun isLocalSource(sourceUrl: String): Boolean = sourceUrl.startsWith(LOCAL_PREFIX)

        /** The source name of a local module (`evolution`), or null for a registry module. */
        fun localSourceName(sourceUrl: String): String? = LOCAL_SOURCE_URL.matchEntire(sourceUrl)?.groupValues?.get(1)

        /** The digest pinned in a local module's source, or null when absent or malformed. */
        fun pinnedDigest(sourceUrl: String): String? = LOCAL_SOURCE_URL.matchEntire(sourceUrl)?.groupValues?.get(2)

        /** Public module index. Pre-filled in the Modules screen. */
        const val DEFAULT_REGISTRY_URL =
            "https://raw.githubusercontent.com/l3ad3r1/hermes-jeeves-modules/main/registry.json"
    }
}
