package com.hermes.agent.domain.device

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction for privileged command execution (e.g. Shizuku ADB shell or root).
 * Kept in core:domain so core:tools can run privileged commands without hard-depending
 * on platform/framework-specific Shizuku libraries.
 */
interface PrivilegedShellBackend {

    enum class Status {
        NOT_INSTALLED,
        DEAD,
        PERMISSION_REQUIRED,
        READY,
    }

    data class PrivilegedStatus(
        val status: Status,
        val uid: Int = -1,
        val version: Int = -1,
    )

    data class ExecResult(
        val exitCode: Int,
        val output: String,
    )

    suspend fun getStatus(): PrivilegedStatus
    suspend fun requestPermission(): Boolean
    suspend fun execute(command: String, timeoutMs: Long): Result<ExecResult>
}

/**
 * Fallback no-op backend for environments/consumers where privileged execution is not bound.
 */
@Singleton
class NoOpPrivilegedShellBackend @Inject constructor() : PrivilegedShellBackend {
    override suspend fun getStatus(): PrivilegedShellBackend.PrivilegedStatus =
        PrivilegedShellBackend.PrivilegedStatus(PrivilegedShellBackend.Status.NOT_INSTALLED)

    override suspend fun requestPermission(): Boolean = false

    override suspend fun execute(command: String, timeoutMs: Long): Result<PrivilegedShellBackend.ExecResult> =
        Result.failure(UnsupportedOperationException("Privileged shell is not available in this build."))
}
