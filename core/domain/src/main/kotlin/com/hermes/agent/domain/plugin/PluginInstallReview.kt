package com.hermes.agent.domain.plugin

/** UI-neutral snapshot used to render a plugin install review. */
data class PluginInstallReview(
    val request: PluginInstallApprovalRequest,
    val persistedApproval: PluginInstallApproval?,
)

/** Coordinates review decisions without prescribing a screen, navigation, or copy. */
interface PluginInstallReviewCoordinator {
    suspend fun begin(verifiedPackage: VerifiedPluginPackage): Result<PluginInstallReview>

    suspend fun approve(
        verifiedPackage: VerifiedPluginPackage,
        decision: PluginInstallApprovalDecision,
        approvedAtEpochSeconds: Long,
    ): Result<PluginInstallAuthorizationResult>

    suspend fun handoff(
        artifact: DownloadedPluginArtifact,
        verifiedPackage: VerifiedPluginPackage,
        authorization: PluginInstallAuthorizationResult.Authorized,
    ): Result<PluginInstallHandoffResult>
}
