package com.hermes.agent.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Metadata and snapshot content for a file checkpoint.
 */
data class FileCheckpoint(
    val id: String,
    val filePath: String,
    val timestamp: Long,
    val formattedTime: String,
    val content: String,
    val sizeBytes: Long = content.toByteArray(Charsets.UTF_8).size.toLong(),
)

/**
 * Checkpoint and snapshot manager for file mutations (writes and patches).
 *
 * Ports upstream `tools/checkpoint_manager.py` to allow snapshot creation prior
 * to destructive file modifications and instant rollback if edits are corrupted.
 */
@Singleton
class FileCheckpointStore(
    private val checkpointDir: File,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "checkpoints"))

    init {
        if (!checkpointDir.exists()) {
            checkpointDir.mkdirs()
        }
    }

    /**
     * Creates a checkpoint for [file]. If [file] does not exist, an empty snapshot is recorded.
     *
     * @param file The file being modified
     * @return The generated checkpoint ID
     */
    @Synchronized
    fun createCheckpoint(file: File): String {
        val timestamp = System.currentTimeMillis()
        val id = "chk_${timestamp}_${UUID.randomUUID().toString().take(8)}"
        val content = if (file.exists() && file.isFile) {
            try {
                file.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                ""
            }
        } else {
            ""
        }

        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        val snapshotFile = File(checkpointDir, "$id.snapshot")
        val metaFile = File(checkpointDir, "$id.meta")

        try {
            snapshotFile.writeText(content, Charsets.UTF_8)
            metaFile.writeText("${file.absolutePath}\n$timestamp\n$formattedTime", Charsets.UTF_8)
        } catch (e: IOException) {
            // Ignore write failures to avoid breaking the tool if checkpoint dir has issues
        }

        pruneOldCheckpoints(maxCheckpoints = 100)
        return id
    }

    /**
     * Retrieves a checkpoint by its ID.
     */
    @Synchronized
    fun getCheckpoint(id: String): FileCheckpoint? {
        val snapshotFile = File(checkpointDir, "$id.snapshot")
        val metaFile = File(checkpointDir, "$id.meta")

        if (!snapshotFile.exists() || !metaFile.exists()) return null

        return try {
            val metaLines = metaFile.readLines(Charsets.UTF_8)
            val filePath = metaLines.getOrNull(0) ?: return null
            val timestamp = metaLines.getOrNull(1)?.toLongOrNull() ?: 0L
            val formattedTime = metaLines.getOrNull(2) ?: ""
            val content = snapshotFile.readText(Charsets.UTF_8)

            FileCheckpoint(
                id = id,
                filePath = filePath,
                timestamp = timestamp,
                formattedTime = formattedTime,
                content = content,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Lists available checkpoints, newest first.
     */
    @Synchronized
    fun listCheckpoints(filterFilePath: String? = null): List<FileCheckpoint> {
        val metaFiles = checkpointDir.listFiles { file -> file.extension == "meta" } ?: return emptyList()

        return metaFiles.mapNotNull { metaFile ->
            val id = metaFile.nameWithoutExtension
            getCheckpoint(id)
        }.filter {
            filterFilePath == null || it.filePath == filterFilePath
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Restores a checkpoint to its original target file.
     *
     * @return Result message indicating success or failure reason.
     */
    @Synchronized
    fun restoreCheckpoint(id: String): Result<String> {
        val checkpoint = getCheckpoint(id) ?: return Result.failure(IllegalArgumentException("Checkpoint '$id' not found"))
        val targetFile = File(checkpoint.filePath)

        return try {
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(checkpoint.content, Charsets.UTF_8)
            Result.success("Restored '${targetFile.name}' to checkpoint $id (${checkpoint.formattedTime})")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun pruneOldCheckpoints(maxCheckpoints: Int) {
        val metaFiles = checkpointDir.listFiles { file -> file.extension == "meta" } ?: return
        if (metaFiles.size <= maxCheckpoints) return

        val sorted = metaFiles.sortedBy { it.lastModified() }
        val toDeleteCount = sorted.size - maxCheckpoints
        for (i in 0 until toDeleteCount) {
            val meta = sorted[i]
            val id = meta.nameWithoutExtension
            val snapshot = File(checkpointDir, "$id.snapshot")
            meta.delete()
            snapshot.delete()
        }
    }
}
