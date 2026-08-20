package com.hermes.agent.domain.plugin

/** Durable publisher decision, scoped to one plugin identity and signing certificate. */
data class PluginPublisherTrust(
    val pluginId: String,
    val signerCertificateSha256: String,
    val trustedAtEpochSeconds: Long,
)

/** Exact approval snapshot retained for install completion and restart recovery. */
data class PluginInstallApproval(
    val request: PluginInstallApprovalRequest,
    val approvedAtEpochSeconds: Long,
)

interface PluginPublisherTrustStore {
    suspend fun isTrusted(pluginId: String, signerCertificateSha256: String): Result<Boolean>
    suspend fun setTrusted(
        pluginId: String,
        signerCertificateSha256: String,
        trustedAtEpochSeconds: Long,
    ): Result<Unit>
    suspend fun revoke(pluginId: String, signerCertificateSha256: String): Result<Unit>
}

interface PluginInstallApprovalStore {
    suspend fun save(approval: PluginInstallApproval): Result<Unit>
    suspend fun find(request: PluginInstallApprovalRequest): Result<PluginInstallApproval?>
    suspend fun revoke(request: PluginInstallApprovalRequest): Result<Unit>
}
