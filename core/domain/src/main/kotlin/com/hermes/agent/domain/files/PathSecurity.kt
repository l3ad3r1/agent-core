package com.hermes.agent.domain.files

import java.io.File
import java.io.IOException

/**
 * Path validation and security helpers for agent file tools.
 *
 * Ports upstream `tools/path_security.py` to prevent path traversal, symlink escapes,
 * and dangerous device / system access outside granted root directories.
 */
object PathSecurity {

    private val BLOCKED_DEVICE_PREFIXES = listOf(
        "/dev",
        "/proc",
        "/sys",
        "/etc",
        "/system",
    )

    /**
     * Checks if [pathStr] contains `..` path traversal segments.
     */
    fun hasTraversalComponent(pathStr: String): Boolean {
        val normalized = pathStr.replace('\\', '/')
        val parts = normalized.split('/')
        return parts.any { it == ".." }
    }

    /**
     * Resolves and validates [rawPath] relative to [rootDir].
     *
     * @param rawPath The relative or absolute path requested by the agent
     * @param rootDir The allowed sandbox root directory
     * @return The safe, canonicalized [File] strictly inside [rootDir]
     * @throws SecurityException if the path attempts traversal, escapes [rootDir], or points to a blocked system path
     */
    fun resolveSafePath(rawPath: String, rootDir: File): File {
        if (rawPath.isBlank()) {
            throw SecurityException("File path cannot be blank")
        }

        val normalized = rawPath.trim().replace('\\', '/')

        for (blocked in BLOCKED_DEVICE_PREFIXES) {
            if (normalized == blocked || normalized.startsWith("$blocked/")) {
                throw SecurityException("Access to system or device path '$normalized' is blocked")
            }
        }

        if (hasTraversalComponent(normalized)) {
            throw SecurityException("Path traversal attempt detected with '..' in path: $rawPath")
        }

        val canonicalRoot = try {
            rootDir.canonicalFile
        } catch (e: IOException) {
            rootDir.absoluteFile
        }

        val targetFile = if (File(rawPath).isAbsolute) {
            File(rawPath)
        } else {
            File(canonicalRoot, rawPath)
        }

        val canonicalTarget = try {
            targetFile.canonicalFile
        } catch (e: IOException) {
            targetFile.absoluteFile
        }

        val rootPathStr = canonicalRoot.path
        val targetPathStr = canonicalTarget.path

        val isInside = if (rootPathStr.endsWith(File.separator)) {
            targetPathStr.startsWith(rootPathStr)
        } else {
            targetPathStr == rootPathStr || targetPathStr.startsWith(rootPathStr + File.separator)
        }

        if (!isInside) {
            throw SecurityException("Path '$rawPath' resolves to '$targetPathStr' which escapes allowed directory '$rootPathStr'")
        }

        return canonicalTarget
    }

    /**
     * Validates that [file] resolves inside [rootDir].
     *
     * @return null if safe, or an error string if validation fails.
     */
    fun validateWithinDir(file: File, rootDir: File): String? {
        return try {
            resolveSafePath(file.path, rootDir)
            null
        } catch (e: SecurityException) {
            e.message ?: "Path validation failed"
        }
    }
}
