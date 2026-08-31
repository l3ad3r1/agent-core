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
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lists and restores the snapshots [WriteFileTool] and [PatchFileTool] take
 * before they mutate a file.
 *
 * Both of those tools already checkpointed every write, but nothing exposed
 * [FileCheckpointStore.restoreCheckpoint], so the snapshots accumulated unused
 * and "undo that edit" was not something the agent could do. This is the other
 * half of upstream `tools/checkpoint_manager.py`.
 */
@Singleton
class FileCheckpointTool(
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
        name = "file_checkpoint",
        description = "List the rollback snapshots taken before file writes and patches, and restore " +
            "one. Use this to undo an edit that went wrong: list first to find the checkpoint id, " +
            "then restore it.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "'list' to see available checkpoints, 'restore' to roll a file back.",
                enumValues = listOf("list", "restore"),
            ),
            ToolParameter(
                name = "path",
                type = ToolParameterType.STRING,
                description = "For 'list': optionally limit the results to one file's checkpoints.",
            ),
            ToolParameter(
                name = "checkpoint_id",
                type = ToolParameterType.STRING,
                description = "For 'restore': the id from a 'list' call, e.g. chk_1724... .",
            ),
        ),
        category = "files",
        requiresConfirmation = true,
        capabilities = setOf("files"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val startMs = System.currentTimeMillis()
        val action = arguments["action"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: "list"
        val root = explicitRootDir ?: getEffectiveRootDir()

        return when (action) {
            "list" -> list(arguments, root, startMs)
            "restore" -> restore(arguments, root, startMs)
            else -> ToolResult.error(
                "Unknown action '$action'. Use 'list' or 'restore'.",
                System.currentTimeMillis() - startMs,
            )
        }
    }

    private fun list(arguments: Map<String, JsonElement>, root: File, startMs: Long): ToolResult {
        val pathArg = arguments["path"]?.jsonPrimitive?.content?.trim()
        val filter = if (!pathArg.isNullOrBlank()) {
            val resolved = try {
                PathSecurity.resolveSafePath(pathArg, root)
            } catch (e: SecurityException) {
                return ToolResult.error(
                    "Security violation: ${e.message}",
                    System.currentTimeMillis() - startMs,
                )
            }
            resolved.absolutePath
        } else {
            null
        }

        // Only ever show checkpoints for files inside the current workspace. A
        // checkpoint taken under a previous root is not this workspace's business.
        val checkpoints = checkpointStore.listCheckpoints(filter)
            .filter { isInsideRoot(File(it.filePath), root) }

        if (checkpoints.isEmpty()) {
            return ToolResult.ok("No checkpoints available.", System.currentTimeMillis() - startMs)
        }

        val body = checkpoints.joinToString("\n") { c ->
            "${c.id}  ${c.formattedTime}  ${c.sizeBytes} bytes  ${File(c.filePath).name}"
        }
        return ToolResult.ok(
            "${checkpoints.size} checkpoint(s), newest first:\n$body",
            System.currentTimeMillis() - startMs,
        )
    }

    private fun restore(arguments: Map<String, JsonElement>, root: File, startMs: Long): ToolResult {
        val id = arguments["checkpoint_id"]?.jsonPrimitive?.content?.trim()
        if (id.isNullOrBlank()) {
            return ToolResult.error(
                "Missing required argument 'checkpoint_id'. Call action='list' first.",
                System.currentTimeMillis() - startMs,
            )
        }

        val checkpoint = checkpointStore.getCheckpoint(id)
            ?: return ToolResult.error("Checkpoint '$id' not found.", System.currentTimeMillis() - startMs)

        // The target path was resolved when the snapshot was taken. If the
        // workspace root has changed since, restoring would write outside the
        // granted tree — refuse rather than trust the recorded path.
        if (!isInsideRoot(File(checkpoint.filePath), root)) {
            return ToolResult.error(
                "Security violation: checkpoint '$id' targets a file outside the current workspace.",
                System.currentTimeMillis() - startMs,
            )
        }

        return checkpointStore.restoreCheckpoint(id).fold(
            onSuccess = { message -> ToolResult.ok(message, System.currentTimeMillis() - startMs) },
            onFailure = { e ->
                ToolResult.error(
                    "Restore failed: ${e.message}",
                    System.currentTimeMillis() - startMs,
                )
            },
        )
    }

    private fun isInsideRoot(file: File, root: File): Boolean = try {
        val rootPath = root.canonicalFile.absolutePath
        val filePath = file.canonicalFile.absolutePath
        filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    } catch (e: Exception) {
        false
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
        return File(ext ?: ctx.filesDir, "workspace").apply { mkdirs() }
    }

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class BindingsModule {
        @Binds
        @IntoSet
        abstract fun bindFileCheckpointTool(tool: FileCheckpointTool): Tool
    }
}
