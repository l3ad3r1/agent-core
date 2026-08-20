package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.DownloadedPluginArtifact
import com.hermes.agent.domain.plugin.PluginInstallApproval
import com.hermes.agent.domain.plugin.PluginInstallApprovalDecision
import com.hermes.agent.domain.plugin.PluginInstallApprovalRequest
import com.hermes.agent.domain.plugin.PluginInstallApprovalStore
import com.hermes.agent.domain.plugin.PluginInstallAuthorizationResult
import com.hermes.agent.domain.plugin.PluginInstallHandoffResult
import com.hermes.agent.domain.plugin.PluginInstallReviewCoordinator
import com.hermes.agent.domain.plugin.PluginInstallAttempt
import com.hermes.agent.domain.plugin.PluginInstallRecord
import com.hermes.agent.domain.plugin.PluginInstallStateStore
import com.hermes.agent.domain.plugin.PluginArtifact
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPackageEvidence
import com.hermes.agent.domain.plugin.PluginPackageInstaller
import com.hermes.agent.domain.plugin.PluginPublisherTrustStore
import com.hermes.agent.domain.plugin.VerifiedPluginPackage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInstallReviewCoordinatorTest {
    @Test fun beginLoadsOnlyTheExactPersistedReview() = runTest {
        val request = request(publisherTrusted = true)
        val persisted = PluginInstallApproval(request, 12)
        val approvals = FakeApprovalStore(persisted)
        val coordinator = coordinator(approvals = approvals)

        val review = coordinator.begin(verified(request, true)).getOrThrow()

        assertEquals(persisted, review.persistedApproval)
        assertEquals(request, review.request)
    }

    @Test fun deniedDecisionDoesNotWriteTrustOrApproval() = runTest {
        val request = request(publisherTrusted = false)
        val trust = FakeTrustStore()
        val approvals = FakeApprovalStore()
        val result = coordinator(trust, approvals).approve(
            verified(request, false),
            PluginInstallApprovalDecision(request, approved = false),
            approvedAtEpochSeconds = 20,
        ).getOrThrow()

        assertTrue(result is PluginInstallAuthorizationResult.Denied)
        assertTrue(trust.saved.isEmpty())
        assertTrue(approvals.saved.isEmpty())
    }

    @Test fun beginRefreshesPublisherTrustBeforeBuildingReview() = runTest {
        val request = request(publisherTrusted = false)
        val trust = FakeTrustStore().also {
            it.setTrusted(request.pluginId, request.signerCertificateSha256, 11)
        }

        val review = coordinator(trust = trust).begin(verified(request, false)).getOrThrow()

        assertTrue(review.request.publisherTrusted)
    }

    @Test fun trustedApprovalPersistsBeforeHandoffAndHandoffRequiresPersistence() = runTest {
        val request = request(publisherTrusted = false)
        val trust = FakeTrustStore()
        val approvals = FakeApprovalStore()
        val installer = FakeInstaller()
        val state = FakeInstallStateStore()
        val coordinator = coordinator(trust, approvals, installer, state)
        val verified = verified(request, false)
        val authorization = coordinator.approve(
            verified,
            PluginInstallApprovalDecision(request, approved = true, trustPublisher = true),
            approvedAtEpochSeconds = 21,
        ).getOrThrow() as PluginInstallAuthorizationResult.Authorized

        assertEquals(1, trust.saved.size)
        assertEquals(1, approvals.saved.size)
        val handoff = coordinator.handoff(artifact(request), verified, authorization, handedOffAtEpochSeconds = 22).getOrThrow()

        assertEquals(PluginInstallHandoffResult.Launched(request.pluginId, request.versionCode), handoff)
        assertEquals(1, installer.calls)
        assertEquals(1, state.records.size)
    }

    private fun coordinator(
        trust: FakeTrustStore = FakeTrustStore(),
        approvals: FakeApprovalStore = FakeApprovalStore(),
        installer: PluginPackageInstaller = FakeInstaller(),
        state: FakeInstallStateStore = coordinatorState(),
    ): PluginInstallReviewCoordinator = PluginInstallReviewCoordinatorImpl(PluginInstallApprovalGate(), trust, approvals, state, installer)

    private fun coordinatorState() = FakeInstallStateStore()

    private fun verified(request: PluginInstallApprovalRequest, trusted: Boolean): VerifiedPluginPackage =
        VerifiedPluginPackage(
            entry = PluginCatalogEntry(
                manifest = PluginManifest(
                    id = request.pluginId, displayName = request.displayName, versionCode = request.versionCode,
                    versionName = request.versionName, author = "Test", signatureFingerprint = request.signerCertificateSha256,
                    capabilities = emptyList(), permissions = request.permissions,
                ),
                artifact = PluginArtifact("https://example.com/plugin.apk", request.apkSha256, 1L, request.pluginId, "Service"),
            ),
            evidence = PluginPackageEvidence(
                manifest = PluginManifest(
                    id = request.pluginId, displayName = request.displayName, versionCode = request.versionCode,
                    versionName = request.versionName, author = "Test", signatureFingerprint = request.signerCertificateSha256,
                    capabilities = emptyList(), permissions = request.permissions,
                ),
                apkSha256 = request.apkSha256, sizeBytes = 1L, packageName = request.pluginId,
                versionCode = request.versionCode, signerCertificateSha256 = request.signerCertificateSha256,
                exportedServiceClassNames = setOf("Service"),
            ),
            publisherTrusted = trusted,
        )

    private fun request(publisherTrusted: Boolean) = PluginInstallApprovalRequest(
        pluginId = "com.example.weather", displayName = "Weather", versionCode = 7, versionName = "1.2.0",
        apkSha256 = "AA".repeat(32), signerCertificateSha256 = "BB".repeat(32), permissions = emptyList(), publisherTrusted = publisherTrusted,
    )

    private fun artifact(request: PluginInstallApprovalRequest) = mockk<DownloadedPluginArtifact> {
        every { entry } returns mockk(relaxed = true)
        every { apkSha256 } returns request.apkSha256
        every { sizeBytes } returns 1L
        every { apkPath } returns "plugin.apk"
    }

    private class FakeTrustStore : PluginPublisherTrustStore {
        val saved = mutableListOf<Triple<String, String, Long>>()
        override suspend fun isTrusted(pluginId: String, signerCertificateSha256: String) = Result.success(saved.any { it.first == pluginId && it.second == signerCertificateSha256 })
        override suspend fun setTrusted(pluginId: String, signerCertificateSha256: String, trustedAtEpochSeconds: Long): Result<Unit> { saved += Triple(pluginId, signerCertificateSha256, trustedAtEpochSeconds); return Result.success(Unit) }
        override suspend fun revoke(pluginId: String, signerCertificateSha256: String) = Result.success(Unit)
    }

    private class FakeApprovalStore(initial: PluginInstallApproval? = null) : PluginInstallApprovalStore {
        val saved = mutableListOf<PluginInstallApproval>()
        private var current: PluginInstallApproval? = initial
        override suspend fun save(approval: PluginInstallApproval): Result<Unit> { saved += approval; current = approval; return Result.success(Unit) }
        override suspend fun find(request: PluginInstallApprovalRequest) = Result.success(current?.takeIf { it.request == request })
        override suspend fun revoke(request: PluginInstallApprovalRequest) = Result.success(Unit)
    }

    private class FakeInstaller : PluginPackageInstaller {
        var calls = 0
        override fun canInstallPackages() = true
        override suspend fun openInstallPermissionSettings() = Result.success(Unit)
        override suspend fun handoff(request: com.hermes.agent.domain.plugin.PluginInstallHandoffRequest): Result<PluginInstallHandoffResult> { calls++; return Result.success(PluginInstallHandoffResult.Launched("com.example.weather", 7)) }
    }

    private class FakeInstallStateStore : PluginInstallStateStore {
        val records = mutableListOf<PluginInstallRecord>()
        override suspend fun recordHandoff(attempt: PluginInstallAttempt): Result<Unit> { records += PluginInstallRecord(attempt, com.hermes.agent.domain.plugin.PluginInstallStatus.HANDED_OFF); return Result.success(Unit) }
        override suspend fun pendingForPackage(packageName: String) = Result.success(records.firstOrNull { it.attempt.packageName == packageName && it.status == com.hermes.agent.domain.plugin.PluginInstallStatus.HANDED_OFF })
        override suspend fun markInstalled(packageName: String, installedAtEpochSeconds: Long) = Result.success(null)
        override suspend fun latestForPlugin(pluginId: String) = Result.success(records.lastOrNull { it.attempt.pluginId == pluginId })
    }
}
