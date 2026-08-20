package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.DownloadedPluginArtifact
import com.hermes.agent.domain.plugin.PluginInstallApproval
import com.hermes.agent.domain.plugin.PluginInstallApprovalDecision
import com.hermes.agent.domain.plugin.PluginInstallAuthorizationResult
import com.hermes.agent.domain.plugin.PluginInstallHandoffRequest
import com.hermes.agent.domain.plugin.PluginInstallHandoffResult
import com.hermes.agent.domain.plugin.PluginInstallReview
import com.hermes.agent.domain.plugin.PluginInstallReviewCoordinator
import com.hermes.agent.domain.plugin.PluginInstallApprovalStore
import com.hermes.agent.domain.plugin.PluginPackageInstaller
import com.hermes.agent.domain.plugin.PluginPublisherTrustStore
import com.hermes.agent.domain.plugin.VerifiedPluginPackage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class PluginInstallReviewCoordinatorImpl @Inject constructor(
    private val gate: PluginInstallApprovalGate,
    private val trustStore: PluginPublisherTrustStore,
    private val approvalStore: PluginInstallApprovalStore,
    private val installer: PluginPackageInstaller,
) : PluginInstallReviewCoordinator {
    override suspend fun begin(verifiedPackage: VerifiedPluginPackage): Result<PluginInstallReview> = preservingCancellation {
        val request = effectiveVerifiedPackage(verifiedPackage).approvalRequest()
        PluginInstallReview(request, approvalStore.find(request).getOrThrow())
    }

    override suspend fun approve(
        verifiedPackage: VerifiedPluginPackage,
        decision: PluginInstallApprovalDecision,
        approvedAtEpochSeconds: Long,
    ): Result<PluginInstallAuthorizationResult> = preservingCancellation {
        require(approvedAtEpochSeconds >= 0) { "Approval timestamp is invalid" }
        val effectivePackage = effectiveVerifiedPackage(verifiedPackage)
        val authorization = gate.authorize(effectivePackage, decision)
        if (authorization is PluginInstallAuthorizationResult.Authorized) {
            if (authorization.trustPublisher) {
                trustStore.setTrusted(
                    pluginId = authorization.pluginId,
                    signerCertificateSha256 = authorization.signerCertificateSha256,
                    trustedAtEpochSeconds = approvedAtEpochSeconds,
                ).getOrThrow()
            }
            val persistedRequest = if (authorization.trustPublisher) {
                effectivePackage.copy(publisherTrusted = true).approvalRequest()
            } else {
                decision.request
            }
            approvalStore.save(PluginInstallApproval(persistedRequest, approvedAtEpochSeconds)).getOrThrow()
        }
        authorization
    }

    override suspend fun handoff(
        artifact: DownloadedPluginArtifact,
        verifiedPackage: VerifiedPluginPackage,
        authorization: PluginInstallAuthorizationResult.Authorized,
    ): Result<PluginInstallHandoffResult> = preservingCancellation {
        val effectivePackage = effectiveVerifiedPackage(verifiedPackage)
        val request = effectivePackage.approvalRequest()
        val persisted = approvalStore.find(request).getOrThrow()
            ?: error("Plugin approval is no longer available; review it again")
        require(persisted.request == request) { "Plugin approval changed; review it again" }
        installer.handoff(PluginInstallHandoffRequest(artifact, effectivePackage, authorization)).getOrThrow()
    }

    private suspend fun effectiveVerifiedPackage(
        verifiedPackage: VerifiedPluginPackage,
    ): VerifiedPluginPackage {
        if (verifiedPackage.publisherTrusted) return verifiedPackage
        val trusted = trustStore.isTrusted(
            pluginId = verifiedPackage.entry.manifest.id,
            signerCertificateSha256 = verifiedPackage.evidence.signerCertificateSha256,
        ).getOrThrow()
        return if (trusted) verifiedPackage.copy(publisherTrusted = true) else verifiedPackage
    }
}

private inline fun <T> preservingCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PluginInstallReviewCoordinatorModule {
    @Binds
    abstract fun bindPluginInstallReviewCoordinator(
        implementation: PluginInstallReviewCoordinatorImpl,
    ): PluginInstallReviewCoordinator
}
