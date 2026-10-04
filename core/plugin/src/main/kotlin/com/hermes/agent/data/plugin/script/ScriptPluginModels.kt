package com.hermes.agent.data.plugin.script

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Script-plugin contract, modelled on the system already shipping in Octo Jotter.
 *
 * A module is one `manifest.json` served over HTTPS, carrying its own JavaScript
 * in [main]. There is no APK, no signing authority, and no package installer:
 * the isolation boundary is the Rhino sandbox in [ScriptPluginEngine], not the
 * Android process boundary. That trade is what makes a module installable on
 * demand instead of requiring a full package install.
 *
 * The Hermes variant differs from Octo Jotter's in one substantial way: a
 * module declares [tools] with full parameter schemas. Hermes has to hand the
 * LLM a tool list before any code runs, so the schema must be readable from the
 * manifest without executing the script.
 */
@Serializable
data class ScriptPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val type: String = TYPE_TOOL,
    /** Host `versionCode` required; 0 means "any". */
    val minAppVersion: Int = 0,
    val permissions: List<String> = emptyList(),
    /**
     * The hosts a module with [ScriptPluginPermissions.NETWORK] may call, shown at
     * install and enforced on every request and redirect: `api.example.com`, or
     * `*.example.com` for its subdomains. Empty means any public host.
     */
    val hosts: List<String> = emptyList(),
    val tools: List<ScriptToolSpec> = emptyList(),
    /**
     * Names of existing tools this module's same-named tools are meant to stand in
     * for. Only honoured for a module installed locally through
     * [com.hermes.agent.data.plugin.ScriptPluginRepository.installLocal] — a registry
     * install that declares any is refused. The repository never replaces a tool
     * itself: it hands these to the host app as override candidates, and the app
     * decides (by its own policy) whether to shadow the built-in with them.
     */
    val overrides: List<String> = emptyList(),
    /** The plugin's JavaScript source. */
    val main: String = "",
) {
    companion object {
        const val TYPE_TOOL = "tool"

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** One tool a module exposes, including the schema shown to the model. */
@Serializable
data class ScriptToolSpec(
    val name: String,
    val description: String,
    val category: String = "plugin",
    val parameters: List<ScriptToolParameter> = emptyList(),
    /** Mirrors [ToolDescriptor.requiresConfirmation] for side-effecting tools. */
    val requiresConfirmation: Boolean = false,
) {
    /**
     * The module cannot opt out of confirmation for what its grants make risky:
     * writing the user's data, or reading it with a way to send it off-device.
     */
    fun toDescriptor(granted: Set<String> = emptySet()): ToolDescriptor = ToolDescriptor(
        name = name,
        description = description,
        parameters = parameters.map { it.toToolParameter() },
        category = category,
        requiresConfirmation = requiresConfirmation ||
            ScriptPluginPermissions.DATA_WRITE in granted ||
            (ScriptPluginPermissions.DATA_READ in granted && ScriptPluginPermissions.NETWORK in granted),
    )
}

@Serializable
data class ScriptToolParameter(
    val name: String,
    val type: String = "STRING",
    val description: String = "",
    val required: Boolean = false,
    @SerialName("enum") val enumValues: List<String>? = null,
) {
    fun toToolParameter(): ToolParameter = ToolParameter(
        name = name,
        // An unknown or misspelled type degrades to STRING rather than failing
        // the whole install: the model still gets a usable tool.
        type = runCatching { ToolParameterType.valueOf(type.uppercase()) }
            .getOrDefault(ToolParameterType.STRING),
        description = description,
        required = required,
        enumValues = enumValues,
    )
}

/** One row of the public registry index. */
@Serializable
data class ScriptPluginRegistryEntry(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val type: String = ScriptPluginManifest.TYPE_TOOL,
    val version: String = "",
    val manifestUrl: String,
    /**
     * Lowercase hex SHA-256 of the manifest document this entry points at.
     *
     * The registry is fetched over HTTPS, but the manifest it names is a
     * separate document that can change under a URL the user already approved.
     * Pinning the digest here means the bytes reviewed at the registry are the
     * bytes installed. Blank means unpinned — accepted for now so older
     * registries keep working, and reported to the user as unverified.
     */
    val sha256: String = "",
)

@Serializable
data class ScriptPluginRegistry(
    val plugins: List<ScriptPluginRegistryEntry> = emptyList(),
)

/**
 * Capabilities a module must request in its manifest to reach host data.
 *
 * A module with no permissions is pure computation: it can transform its own
 * arguments and return a string, and nothing else. Every escape from that is
 * named here and gated in [ScriptPluginEngine].
 */
object ScriptPluginPermissions {
    /** Read notes, todos, and bookmarks through the host. */
    const val DATA_READ = "data.read"

    /** Create or modify notes, todos, and bookmarks through the host. */
    const val DATA_WRITE = "data.write"

    /** Outbound HTTP through the host's client, never the plugin's own. */
    const val NETWORK = "network"

    val ALL = setOf(DATA_READ, DATA_WRITE, NETWORK)

    /** Human-readable text for the install confirmation. */
    fun describe(permission: String, hosts: List<String> = emptyList()): String = when (permission) {
        DATA_READ -> "Read your notes, tasks, and bookmarks"
        DATA_WRITE -> "Create and change your notes, tasks, and bookmarks"
        NETWORK -> if (hosts.isEmpty()) {
            "Connect to any website"
        } else {
            "Connect to ${hosts.joinToString(", ")}"
        }
        else -> permission
    }
}

/** Matching for [ScriptPluginManifest.hosts]. */
object ModuleHosts {
    /** A request's allowlist, carried as an OkHttp tag so redirects are checked too. */
    data class Allowed(val hosts: List<String>)

    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /** A bare lowercase hostname, optionally `*.`-prefixed: no scheme, port, path or IP. */
    fun isValidEntry(entry: String): Boolean {
        val name = entry.removePrefix("*.")
        val labels = name.split('.')
        return name.length <= 253 && labels.size >= 2 && labels.all { LABEL.matches(it) } &&
            !labels.last().all { it.isDigit() }
    }

    /** True when [host] is allowed by [entries]; an empty list allows any host. */
    fun matches(host: String, entries: List<String>): Boolean {
        if (entries.isEmpty()) return true
        val h = host.lowercase().trimEnd('.')
        return entries.any { entry ->
            if (entry.startsWith("*.")) h.endsWith(entry.substring(1)) else h == entry
        }
    }

    fun refusal(host: String, entries: List<String>): String =
        "This module may only connect to ${entries.joinToString(", ")}; $host is not one of them."
}
