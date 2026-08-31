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
 * Tool allowing the agent to apply unified diffs, V4A patches, or SEARCH/REPLACE blocks
 * with fuzzy matching tolerance and rollback snapshots.
 *
 * Ports upstream `tools/file_tools.py` (`patch`).
 */
@Singleton
class PatchFileTool(
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
        name = "patch",
        description = "Apply a diff, V4A patch, or SEARCH/REPLACE block to a file within the workspace. Supports fuzzy matching for whitespace and automatically creates a rollback checkpoint.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = ToolParameterType.STRING,
                description = "The relative path to the file to patch.",
                required = true,
            ),
            ToolParameter(
                name = "patch",
                type = ToolParameterType.STRING,
                description = "The unified diff, V4A patch, or SEARCH/REPLACE block to apply.",
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

        val patchText = arguments["patch"]?.jsonPrimitive?.content
            ?: arguments["diff"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required argument 'patch'", System.currentTimeMillis() - startMs)

        val root = explicitRootDir ?: getEffectiveRootDir()

        val file = try {
            PathSecurity.resolveSafePath(pathStr, root)
        } catch (e: SecurityException) {
            return ToolResult.error("Security violation: ${e.message}", System.currentTimeMillis() - startMs)
        }

        if (!file.exists()) {
            return ToolResult.error("File not found to patch: $pathStr", System.currentTimeMillis() - startMs)
        }

        if (file.isDirectory) {
            return ToolResult.error("Target path is a directory: $pathStr", System.currentTimeMillis() - startMs)
        }

        val originalContent = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ToolResult.error("Failed to read file '$pathStr': ${e.message}", System.currentTimeMillis() - startMs)
        }

        // 1. Create a rollback checkpoint
        val checkpointId = checkpointStore.createCheckpoint(file)

        // 2. Apply fuzzy patch
        val patchResult = FuzzyPatcher.applyPatch(originalContent, patchText)
        if (!patchResult.success) {
            return ToolResult.error(
                "Patch failed: ${patchResult.errorMessage ?: "Could not match patch hunks"}",
                System.currentTimeMillis() - startMs,
            )
        }

        // 3. Write modified content
        try {
            file.writeText(patchResult.newContent, Charsets.UTF_8)
        } catch (e: Exception) {
            return ToolResult.error("Failed to write patched file '$pathStr': ${e.message}", System.currentTimeMillis() - startMs)
        }

        val resultJson = buildJsonObject {
            put("status", "success")
            put("path", pathStr)
            put("hunks_applied", patchResult.hunksApplied)
            put("bytes_written", file.length())
            put("checkpoint_id", checkpointId)
            put("message", "Successfully patched '$pathStr' (${patchResult.hunksApplied} hunks applied, checkpoint: $checkpointId)")
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
        abstract fun bindTool(tool: PatchFileTool): Tool
    }
}
