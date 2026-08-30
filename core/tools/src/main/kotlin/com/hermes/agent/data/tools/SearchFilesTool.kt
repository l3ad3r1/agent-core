package com.hermes.agent.data.tools

import android.content.Context
import com.hermes.agent.domain.files.PathSecurity
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool allowing the agent to search for files by name glob pattern or text content within the workspace.
 *
 * Ports upstream `tools/file_tools.py` (`search_files`).
 */
@Singleton
class SearchFilesTool(
    private val context: Context?,
    private val settingsRepository: SettingsRepository?,
    private val explicitRootDir: File?,
) : Tool {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
    ) : this(context, settingsRepository, null)

    /** Standalone / test constructor */
    constructor(rootDir: File) : this(null, null, rootDir)

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "search_files",
        description = "Search for files by name pattern or text content inside the workspace directory.",
        parameters = listOf(
            ToolParameter(
                name = "pattern",
                type = ToolParameterType.STRING,
                description = "The substring, pattern, or search query to find in filenames or file contents.",
                required = true,
            ),
            ToolParameter(
                name = "path",
                type = ToolParameterType.STRING,
                description = "Optional subfolder within the workspace to limit the search scope.",
                required = false,
            ),
            ToolParameter(
                name = "max_results",
                type = ToolParameterType.INTEGER,
                description = "Optional maximum number of matching results to return (default 50).",
                required = false,
            ),
        ),
        category = "files",
        capabilities = setOf("files"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val startMs = System.currentTimeMillis()
        val pattern = arguments["pattern"]?.jsonPrimitive?.content
            ?: arguments["query"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required argument 'pattern'", System.currentTimeMillis() - startMs)

        val subPath = arguments["path"]?.jsonPrimitive?.content ?: ""
        val maxResults = arguments["max_results"]?.jsonPrimitive?.intOrNull ?: 50

        val root = explicitRootDir ?: getEffectiveRootDir()

        val searchDir = try {
            if (subPath.isNotBlank()) {
                PathSecurity.resolveSafePath(subPath, root)
            } else {
                root
            }
        } catch (e: SecurityException) {
            return ToolResult.error("Security violation: ${e.message}", System.currentTimeMillis() - startMs)
        }

        if (!searchDir.exists()) {
            return ToolResult.error("Search directory does not exist: $subPath", System.currentTimeMillis() - startMs)
        }

        val results = mutableListOf<JsonObject>()
        val patternLower = pattern.lowercase()

        try {
            searchDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") && it.extension != "snapshot" && it.extension != "meta" }
                .take(1000)
                .forEach { file ->
                    if (results.size >= maxResults) return@forEach

                    val relPath = file.relativeTo(root).path.replace('\\', '/')

                    // 1. Filename match
                    if (file.name.lowercase().contains(patternLower)) {
                        results.add(
                            buildJsonObject {
                                put("type", "filename_match")
                                put("path", relPath)
                                put("size_bytes", file.length())
                            },
                        )
                    } else {
                        // 2. Content match (for text files < 1MB)
                        if (file.length() < 1_048_576) {
                            try {
                                file.useLines(Charsets.UTF_8) { lines ->
                                    lines.forEachIndexed { index, line ->
                                        if (results.size >= maxResults) return@useLines
                                        if (line.lowercase().contains(patternLower)) {
                                            results.add(
                                                buildJsonObject {
                                                    put("type", "content_match")
                                                    put("path", relPath)
                                                    put("line_number", index + 1)
                                                    put("snippet", line.trim().take(200))
                                                },
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip binary or unreadable files
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            return ToolResult.error("Search error: ${e.message}", System.currentTimeMillis() - startMs)
        }

        val outputJson = buildJsonObject {
            put("status", "success")
            put("pattern", pattern)
            put("results_count", results.size)
            put("results", buildJsonArray { results.forEach { add(it) } })
        }.toString()

        return ToolResult.ok(outputJson, System.currentTimeMillis() - startMs)
    }

    private suspend fun getEffectiveRootDir(): File {
        val settings = settingsRepository?.current()
        if (settings != null && settings.filesRootUri.isNotBlank()) {
            val rootFile = File(settings.filesRootUri)
            if (rootFile.exists() && rootFile.isDirectory) {
                return rootFile
            }
        }
        val ctx = context ?: return File(System.getProperty("java.io.tmpdir"), "hermes_workspace").apply { mkdirs() }
        val ext = ctx.getExternalFilesDir(null)
        val workspace = File(ext ?: ctx.filesDir, "workspace")
        if (!workspace.exists()) {
            workspace.mkdirs()
        }
        return workspace
    }

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class BindingsModule {
        @Binds
        @IntoSet
        abstract fun bindTool(tool: SearchFilesTool): Tool
    }
}
