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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool allowing the agent to read file content with line offset and limit pagination.
 *
 * Ports upstream `tools/file_tools.py` (`read_file`).
 */
@Singleton
class ReadFileTool(
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
        name = "read_file",
        description = "Read the contents of a file within the workspace. Supports pagination using line offset (1-indexed) and limit.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = ToolParameterType.STRING,
                description = "The relative path to the file to read.",
                required = true,
            ),
            ToolParameter(
                name = "offset",
                type = ToolParameterType.INTEGER,
                description = "Optional 1-indexed starting line number to read from.",
                required = false,
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Optional maximum number of lines to read.",
                required = false,
            ),
        ),
        category = "files",
        capabilities = setOf("files"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val startMs = System.currentTimeMillis()
        val pathStr = arguments["path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required argument 'path'", System.currentTimeMillis() - startMs)

        val offset = arguments["offset"]?.jsonPrimitive?.intOrNull ?: 1
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull

        val root = explicitRootDir ?: getEffectiveRootDir()

        val file = try {
            PathSecurity.resolveSafePath(pathStr, root)
        } catch (e: SecurityException) {
            return ToolResult.error("Security violation: ${e.message}", System.currentTimeMillis() - startMs)
        }

        if (!file.exists()) {
            return ToolResult.error("File not found: $pathStr", System.currentTimeMillis() - startMs)
        }

        if (file.isDirectory) {
            return ToolResult.error("Path is a directory, not a file: $pathStr", System.currentTimeMillis() - startMs)
        }

        val allLines = try {
            file.readLines(Charsets.UTF_8)
        } catch (e: Exception) {
            return ToolResult.error("Failed to read file: ${e.message}", System.currentTimeMillis() - startMs)
        }

        val totalLines = allLines.size
        val startLineIdx = (offset - 1).coerceAtLeast(0)

        if (startLineIdx >= totalLines && totalLines > 0) {
            return ToolResult.error("Offset $offset exceeds total line count $totalLines", System.currentTimeMillis() - startMs)
        }

        val selectedLines = if (limit != null && limit > 0) {
            allLines.drop(startLineIdx).take(limit)
        } else {
            allLines.drop(startLineIdx)
        }

        val formattedContent = selectedLines.mapIndexed { idx, line ->
            val lineNum = startLineIdx + idx + 1
            "$lineNum: $line"
        }.joinToString("\n")

        val maxChars = 100_000
        val isTruncated = formattedContent.length > maxChars
        val outputContent = if (isTruncated) {
            formattedContent.take(maxChars) + "\n... [truncated to 100,000 characters]"
        } else {
            formattedContent
        }

        val resultJson = buildJsonObject {
            put("status", "success")
            put("path", pathStr)
            put("total_lines", totalLines)
            put("start_line", startLineIdx + 1)
            put("lines_read", selectedLines.size)
            put("size_bytes", file.length())
            put("truncated", isTruncated)
            put("content", outputContent)
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
        abstract fun bindTool(tool: ReadFileTool): Tool
    }
}
