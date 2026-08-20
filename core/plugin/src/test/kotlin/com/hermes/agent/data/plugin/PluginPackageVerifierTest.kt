package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.PluginArtifact
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginCatalog
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginInstallApprovalDecision
import com.hermes.agent.domain.plugin.PluginInstallAuthorizationResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPackageEvidence
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.tool.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPackageVerifierTest {
    private val verifier = PluginPackageVerifier()
    private val approvalGate = PluginInstallApprovalGate()
    private val codec = PluginCatalogCodec(verifier)

    private val apkSha = "A".repeat(64)
    private val signerSha = "B".repeat(64)

    private fun entry(
        apkUrl: String = "https://plugins.example/weather.apk",
        apkSha256: String = apkSha,
        signerFingerprint: String = signerSha,
        protocolVersion: Int = PluginArtifact.CURRENT_PROTOCOL_VERSION,
        permissions: List<PluginPermission> = listOf(
            PluginPermission(PermissionType.NETWORK, "Fetch weather data"),
        ),
    ): PluginCatalogEntry {
        val manifest = PluginManifest(
            id = "com.example.weather",
            displayName = "Weather",
            versionCode = 7,
            versionName = "1.2.0",
            author = "Example",
            signatureFingerprint = signerFingerprint,
            capabilities = listOf(
                PluginCapability(
                    name = "weather",
                    description = "Weather lookup",
                    toolDescriptors = listOf(
                        ToolDescriptor(
                            name = "weather_lookup",
                            description = "Look up weather",
                            parameters = emptyList(),
                        ),
                    ),
                ),
            ),
            permissions = permissions,
            minAppVersion = 5,
        )
        return PluginCatalogEntry(
            manifest = manifest,
            artifact = PluginArtifact(
                apkUrl = apkUrl,
                apkSha256 = apkSha256,
                sizeBytes = 42_000,
                packageName = manifest.id,
                serviceClassName = "com.example.weather.PluginService",
                protocolVersion = protocolVersion,
            ),
        )
    }

    private fun evidence(entry: PluginCatalogEntry = entry()) = PluginPackageEvidence(
        manifest = entry.manifest,
        apkSha256 = entry.artifact.apkSha256,
        sizeBytes = entry.artifact.sizeBytes,
        packageName = entry.artifact.packageName,
        versionCode = entry.manifest.versionCode,
        signerCertificateSha256 = entry.manifest.signatureFingerprint,
        exportedServiceClassNames = setOf(entry.artifact.serviceClassName),
    )

    @Test
    fun `matching package from trusted publisher verifies`() {
        val entry = entry()
        val result = verifier.verifyPackage(entry, evidence(entry), 10, setOf(signerSha.lowercase()))

        assertTrue(result is PluginPackageVerificationResult.Verified)
        val verified = (result as PluginPackageVerificationResult.Verified).value
        assertTrue(verified.publisherTrusted)
        assertEquals(entry, verified.entry)
    }

    @Test
    fun `untrusted publisher requires explicit trust decision`() {
        val entry = entry()
        val verified = (
            verifier.verifyPackage(entry, evidence(entry), 10, emptySet())
                as PluginPackageVerificationResult.Verified
            ).value
        assertFalse(verified.publisherTrusted)

        val denied = approvalGate.authorize(
            verified,
            PluginInstallApprovalDecision(verified.approvalRequest(), approved = true),
        )
        val authorized = approvalGate.authorize(
            verified,
            PluginInstallApprovalDecision(
                verified.approvalRequest(),
                approved = true,
                trustPublisher = true,
            ),
        )

        assertTrue(denied is PluginInstallAuthorizationResult.Denied)
        assertTrue(authorized is PluginInstallAuthorizationResult.Authorized)
        assertTrue((authorized as PluginInstallAuthorizationResult.Authorized).trustPublisher)
    }

    @Test
    fun `approval cannot be replayed after reviewed package changes`() {
        val original = entry()
        val verified = (
            verifier.verifyPackage(original, evidence(original), 10, setOf(signerSha))
                as PluginPackageVerificationResult.Verified
            ).value
        val staleRequest = verified.approvalRequest().copy(apkSha256 = "C".repeat(64))

        val result = approvalGate.authorize(
            verified,
            PluginInstallApprovalDecision(staleRequest, approved = true),
        )

        assertTrue(result is PluginInstallAuthorizationResult.Denied)
    }

    @Test
    fun `tampered package reports every mismatched immutable field`() {
        val entry = entry()
        val tampered = evidence(entry).copy(
            manifest = entry.manifest.copy(versionName = "tampered"),
            apkSha256 = "C".repeat(64),
            sizeBytes = 1,
            packageName = "com.attacker.weather",
            versionCode = 99,
            signerCertificateSha256 = "D".repeat(64),
            exportedServiceClassNames = emptySet(),
        )

        val result = verifier.verifyPackage(entry, tampered, 10, setOf(signerSha))

        assertTrue(result is PluginPackageVerificationResult.Rejected)
        val reasons = (result as PluginPackageVerificationResult.Rejected).reasons.joinToString("\n")
        assertTrue(reasons.contains("manifest"))
        assertTrue(reasons.contains("package name"))
        assertTrue(reasons.contains("version"))
        assertTrue(reasons.contains("size"))
        assertTrue(reasons.contains("APK SHA-256"))
        assertTrue(reasons.contains("signing certificate"))
        assertTrue(reasons.contains("export"))
    }

    @Test
    fun `incompatible host version is rejected`() {
        val entry = entry()
        val result = verifier.verifyPackage(entry, evidence(entry), 4, setOf(signerSha))

        assertTrue(result is PluginPackageVerificationResult.Rejected)
        assertTrue(
            (result as PluginPackageVerificationResult.Rejected).reasons
                .any { it.contains("requires host version") },
        )
    }

    @Test
    fun `catalog rejects insecure urls unsupported protocol and duplicate ids`() {
        val invalid = entry(apkUrl = "http://plugins.example/weather.apk", protocolVersion = 2)
        val catalog = PluginCatalog(
            generatedAtEpochSeconds = 1,
            plugins = listOf(invalid, invalid),
        )

        val reasons = verifier.verifyCatalog(catalog).joinToString("\n")

        assertTrue(reasons.contains("Duplicate plugin ids"))
        assertTrue(reasons.contains("must use HTTPS"))
        assertTrue(reasons.contains("Unsupported plugin protocol"))
    }

    @Test
    fun `catalog JSON round trips with stable schema`() {
        val catalog = PluginCatalog(
            generatedAtEpochSeconds = 1_775_000_000,
            plugins = listOf(entry()),
        )

        val encoded = codec.encode(catalog).getOrThrow()
        val decoded = codec.decode(encoded).getOrThrow()

        assertEquals(catalog, decoded)
        assertTrue(encoded.contains("\"schemaVersion\":1"))
        assertTrue(encoded.contains("\"apkSha256\""))
    }

    @Test
    fun `catalog codec rejects unsupported schema`() {
        val catalog = PluginCatalog(
            schemaVersion = 99,
            generatedAtEpochSeconds = 1,
            plugins = listOf(entry()),
        )

        val result = codec.encode(catalog)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Unsupported catalog schema"))
    }
}
