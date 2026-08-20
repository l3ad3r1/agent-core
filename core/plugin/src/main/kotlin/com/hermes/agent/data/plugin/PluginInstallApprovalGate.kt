package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PluginInstallApprovalDecision
import com.hermes.agent.domain.plugin.PluginInstallAuthorizationResult
import com.hermes.agent.domain.plugin.VerifiedPluginPackage
import javax.inject.Inject
import javax.inject.Singleton

/** Ensures approval cannot be replayed for a changed artifact, signer, version, or permission set. */
@Singleton
class PluginInstallApprovalGate @Inject constructor() {
    fun authorize(
        verifiedPackage: VerifiedPluginPackage,
        decision: PluginInstallApprovalDecision,
    ): PluginInstallAuthorizationResult {
        val expected = verifiedPackage.approvalRequest()
        if (decision.request != expected) {
            return PluginInstallAuthorizationResult.Denied("Approval does not match the verified package")
        }
        if (!decision.approved) {
            return PluginInstallAuthorizationResult.Denied("User denied plugin installation")
        }
        if (!expected.publisherTrusted && !decision.trustPublisher) {
            return PluginInstallAuthorizationResult.Denied("Plugin publisher is not trusted")
        }
        return PluginInstallAuthorizationResult.Authorized(
            pluginId = expected.pluginId,
            versionCode = expected.versionCode,
            apkSha256 = expected.apkSha256,
            signerCertificateSha256 = expected.signerCertificateSha256,
            trustPublisher = !expected.publisherTrusted && decision.trustPublisher,
        )
    }
}
