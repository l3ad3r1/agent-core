package com.hermes.agent.domain.plugin

import kotlinx.serialization.Serializable

/** Exact package facts retained while Android's asynchronous installer finishes. */
data class PluginInstallAttempt(
    val pluginId: String,
    val packageName: String,
    val versionCode: Int,
    val apkSha256: String,
    val signerCertificateSha256: String,
    val handedOffAtEpochSeconds: Long,
)

@Serializable
enum class PluginInstallStatus { HANDED_OFF, INSTALLED }

data class PluginInstallRecord(
    val attempt: PluginInstallAttempt,
    val status: PluginInstallStatus,
    val installedAtEpochSeconds: Long? = null,
)

interface PluginInstallStateStore {
    suspend fun recordHandoff(attempt: PluginInstallAttempt): Result<Unit>
    suspend fun pendingForPackage(packageName: String): Result<PluginInstallRecord?>
    suspend fun markInstalled(packageName: String, installedAtEpochSeconds: Long): Result<PluginInstallRecord?>
    suspend fun latestForPlugin(pluginId: String): Result<PluginInstallRecord?>
}
