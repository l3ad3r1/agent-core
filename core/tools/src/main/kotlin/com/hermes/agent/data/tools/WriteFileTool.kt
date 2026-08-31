package com.hermes.agent.data.tools

import android.content.Context
import com.hermes.agent.data.local.FileCheckpointStore
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool allowing the agent to create or overwrite file content with automatic checkpoint creation.
 *
 * Ports upstream `tools/file_tools.py` (`write_file`).
 */
@Singleton
class WriteFileTool(
    private val context: Context?,
    private val settingsRepository: SettingsRepository?,
    private val checkpointStore: FileCheckpointStore,
    private val explicitRootDir: File?,
) : Tool {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        checkpointStore: FileCheckpointStore,
    ) : this(context, settingsRepository, checkpointStore, null)

    /** Standalone / test constructor */
    constructor(rootDir: File, checkpointStore: FileCheckpointStore) : this(
        context = null,
        settingsRepository = null,
        checkpointStore = checkpointStore,
        explicitRootDir = rootDir,
    )

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "write_file",
        description = "Create or overwrite a file within the workspace. Automatically creates a rollback checkpoint before saving.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = ToolParameterType.STRING,
                description = "The relative path to the file to create or write.",
                required = true,
            ),
            ToolParameter(
                name = "content",
                type = ToolParameterType.STRING,
                description = "The content to write into the file.",
                required = true,
            ),
        ),
        category = "files",
        requiresConfirmation = true,
        capabilities = setOf("files", "deferrable"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val startMs = System.currentTimeMillis()
        val pathStr = arguments["path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required argument 'path'", System.currentTimeMillis() - startMs)

        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required argument 'content'", System.currentTimeMillis() - startMs)

        val root = explicitRootDir ?: getEffectiveRootDir()

        val file = try {
            PathSecurity.resolveSafePath(pathStr, root)
        } catch (e: SecurityException) {
            return ToolResult.error("Security violation: ${e.message}", System.currentTimeMillis() - startMs)
        }

        if (file.exists() && file.isDirectory) {
            return ToolResult.error("Target path is an existing directory: $pathStr", System.currentTimeMillis() - startMs)
        }

        // 1. Create a rollback checkpoint before mutating
        val checkpointId = checkpointStore.createCheckpoint(file)

        // 2. Ensure parent directories exist and write content
        try {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            return ToolResult.error("Failed to write file '$pathStr': ${e.message}", System.currentTimeMillis() - startMs)
        }

        val resultJson = buildJsonObject {
            put("status", "success")
            put("path", pathStr)
            put("bytes_written", file.length())
            put("checkpoint_id", checkpointId)
            put("message", "Successfully wrote ${file.length()} bytes to '$pathStr' (checkpoint: $checkpointId)")
        }.toString()

        return ToolResult.ok(resultJson, System.currentTimeMillis() - startMs)
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
        abstract fun bindTool(tool: WriteFileTool): Tool
    }
}
