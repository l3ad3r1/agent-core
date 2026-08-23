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
    val tools: List<ScriptToolSpec> = emptyList(),
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
    fun toDescriptor(): ToolDescriptor = ToolDescriptor(
        name = name,
        description = description,
        parameters = parameters.map { it.toToolParameter() },
        category = category,
        requiresConfirmation = requiresConfirmation,
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
    fun describe(permission: String): String = when (permission) {
        DATA_READ -> "Read your notes, tasks, and bookmarks"
        DATA_WRITE -> "Create and change your notes, tasks, and bookmarks"
        NETWORK -> "Make network requests"
        else -> permission
    }
}
