package com.hermes.agent.data.llm

enum class PreflightLevel {
    OPTIMAL,
    WARNING,
    BLOCKED,
}

data class PreflightDecision(
    val allowed: Boolean,
    val level: PreflightLevel,
    val effectiveContextTokens: Int,
    val detail: String,
)

/**
 * Pure, Android-free preflight evaluator for on-device llama.cpp model loading.
 * Ensures models are evaluated against physical RAM constraints before native allocation
 * to prevent native OOM kills.
 */
object LocalModelPreflight {
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L

    fun evaluate(
        modelBytes: Long,
        totalRamBytes: Long,
        availableRamBytes: Long,
        lowMemory: Boolean,
        requestedContextTokens: Int = 2048,
    ): PreflightDecision {
        if (lowMemory) {
            return PreflightDecision(
                allowed = false,
                level = PreflightLevel.BLOCKED,
                effectiveContextTokens = 0,
                detail = "Device is under critical memory pressure (lowMemory flag active).",
            )
        }

        val (estimatedWorkingSet, requiredTotalRam) = when {
            modelBytes < 3L * GIB -> {
                val ws = (0.65 * modelBytes + 384L * MIB).toLong()
                val req = (1.0 * modelBytes).toLong()
                ws to req
            }
            modelBytes <= 6L * GIB -> {
                val ws = (0.75 * modelBytes + 600L * MIB).toLong()
                val req = (1.35 * modelBytes).toLong()
                ws to req
            }
            else -> {
                val ws = (0.90 * modelBytes + 1024L * MIB).toLong()
                val req = (1.80 * modelBytes).toLong()
                ws to req
            }
        }

        if (totalRamBytes > 0 && totalRamBytes < requiredTotalRam) {
            return PreflightDecision(
                allowed = false,
                level = PreflightLevel.BLOCKED,
                effectiveContextTokens = 0,
                detail = "Device total RAM (${formatBytes(totalRamBytes)}) is below required RAM (${formatBytes(requiredTotalRam)}) for this model.",
            )
        }

        if (availableRamBytes > 0 && availableRamBytes < estimatedWorkingSet) {
            val isSevere = availableRamBytes < (0.65 * estimatedWorkingSet).toLong() || modelBytes >= 3L * GIB
            if (isSevere) {
                return PreflightDecision(
                    allowed = false,
                    level = PreflightLevel.BLOCKED,
                    effectiveContextTokens = 0,
                    detail = "Insufficient available RAM (${formatBytes(availableRamBytes)} free, ${formatBytes(estimatedWorkingSet)} estimated working set). Free memory and try again.",
                )
            } else {
                val clamped = requestedContextTokens.coerceAtMost(1024)
                return PreflightDecision(
                    allowed = true,
                    level = PreflightLevel.WARNING,
                    effectiveContextTokens = clamped,
                    detail = "Available RAM is tight (${formatBytes(availableRamBytes)} free, ${formatBytes(estimatedWorkingSet)} estimated). Context clamped to $clamped tokens.",
                )
            }
        }

        if (availableRamBytes > 0 && availableRamBytes < (estimatedWorkingSet * 1.2).toLong()) {
            val clamped = requestedContextTokens.coerceAtMost(1536)
            return PreflightDecision(
                allowed = true,
                level = PreflightLevel.WARNING,
                effectiveContextTokens = clamped,
                detail = "Available RAM is near the working set threshold. Context clamped to $clamped tokens.",
            )
        }

        return PreflightDecision(
            allowed = true,
            level = PreflightLevel.OPTIMAL,
            effectiveContextTokens = requestedContextTokens,
            detail = "RAM headroom is optimal (${formatBytes(availableRamBytes)} available).",
        )
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.1f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }
}
