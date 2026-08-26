package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import java.io.File

internal object LocalModelInstaller {

    data class VerifiedSidecar(
        val sha256: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    fun sidecarFile(modelFile: File): File = File(modelFile.parentFile, "${modelFile.name}.verified")

    fun writeSidecar(modelFile: File, sha256: String): Boolean {
        return try {
            val sidecar = sidecarFile(modelFile)
            val content = buildString {
                appendLine("sha256=${sha256.lowercase().trim()}")
                appendLine("sizeBytes=${modelFile.length()}")
                appendLine("lastModified=${modelFile.lastModified()}")
            }
            sidecar.writeText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun readSidecar(modelFile: File): VerifiedSidecar? {
        val sidecar = sidecarFile(modelFile)
        if (!sidecar.isFile || !sidecar.canRead()) return null
        return try {
            var sha256: String? = null
            var sizeBytes: Long? = null
            var lastModified: Long? = null

            sidecar.forEachLine { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("sha256=") -> sha256 = trimmed.removePrefix("sha256=").trim().lowercase()
                    trimmed.startsWith("sizeBytes=") -> sizeBytes = trimmed.removePrefix("sizeBytes=").trim().toLongOrNull()
                    trimmed.startsWith("lastModified=") -> lastModified = trimmed.removePrefix("lastModified=").trim().toLongOrNull()
                }
            }

            if (sha256 != null && sizeBytes != null && lastModified != null) {
                VerifiedSidecar(sha256 = sha256!!, sizeBytes = sizeBytes!!, lastModified = lastModified!!)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isSidecarValid(modelFile: File, expectedSha256: String, expectedSizeBytes: Long): Boolean {
        if (!modelFile.isFile || modelFile.length() != expectedSizeBytes) return false
        val sidecar = readSidecar(modelFile) ?: return false
        return sidecar.sha256.equals(expectedSha256.trim(), ignoreCase = true) &&
            sidecar.sizeBytes == expectedSizeBytes &&
            sidecar.lastModified == modelFile.lastModified()
    }

    /** Promotes a verified staged model while retaining the old file until promotion succeeds. */
    fun moveIntoPlace(
        staging: File,
        destDir: File,
        fileName: String,
        expectedSize: Long,
        expectedSha256: String? = null,
    ): Boolean {
        return try {
            if (!staging.isFile || staging.length() != expectedSize) return false
            if ((!destDir.exists() && !destDir.mkdirs()) || !destDir.isDirectory) return false
            val dest = File(destDir, fileName)
            val incoming = File(destDir, ".$fileName.incoming")
            val previous = File(destDir, ".$fileName.previous")
            if (incoming.exists() && !incoming.delete()) return false
            if (previous.exists() && !previous.delete()) return false
            if (!staging.renameTo(incoming)) {
                staging.copyTo(incoming, overwrite = false)
                if (incoming.length() != expectedSize || !staging.delete()) {
                    incoming.delete()
                    return false
                }
            }
            if (incoming.length() != expectedSize) {
                incoming.delete()
                return false
            }
            if (dest.exists() && !dest.renameTo(previous)) return false
            val promoted = incoming.renameTo(dest) && dest.length() == expectedSize
            if (promoted) {
                previous.delete()
                if (!expectedSha256.isNullOrBlank()) {
                    writeSidecar(dest, expectedSha256)
                }
                true
            } else {
                dest.delete()
                previous.renameTo(dest)
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
