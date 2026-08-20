package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PluginArtifact
import com.hermes.agent.domain.plugin.PluginCatalog
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginPackageEvidence
import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.VerifiedPluginPackage
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PluginPackageVerificationResult {
    data class Verified(val value: VerifiedPluginPackage) : PluginPackageVerificationResult
    data class Rejected(val reasons: List<String>) : PluginPackageVerificationResult
}

/**
 * Fail-closed validation between public catalog metadata and facts extracted from an APK.
 * A catalog-declared certificate is not itself a trust anchor: publisher trust comes from a
 * separately maintained host trust store or from an explicit user decision.
 */
@Singleton
class PluginPackageVerifier @Inject constructor() {

    fun verifyCatalog(catalog: PluginCatalog): List<String> = buildList {
        if (catalog.schemaVersion != PluginCatalog.CURRENT_SCHEMA_VERSION) {
            add("Unsupported catalog schema ${catalog.schemaVersion}")
        }
        val duplicateIds = catalog.plugins.groupingBy { it.manifest.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) add("Duplicate plugin ids: ${duplicateIds.sorted().joinToString()}")
        catalog.plugins.forEach { entry -> addAll(validateEntry(entry)) }
    }.distinct()

    fun verifyPackage(
        entry: PluginCatalogEntry,
        evidence: PluginPackageEvidence,
        hostVersionCode: Int,
        trustedSignerFingerprints: Set<String>,
    ): PluginPackageVerificationResult {
        val reasons = validateEntry(entry).toMutableList()
        val expectedSigner = normalizeSha256(entry.manifest.signatureFingerprint)
        val actualSigner = normalizeSha256(evidence.signerCertificateSha256)

        if (entry.manifest.minAppVersion > hostVersionCode) {
            reasons += "Plugin requires host version ${entry.manifest.minAppVersion}"
        }
        if (evidence.manifest != entry.manifest) reasons += "APK manifest does not match catalog manifest"
        if (evidence.packageName != entry.artifact.packageName) reasons += "APK package name does not match catalog"
        if (evidence.versionCode != entry.manifest.versionCode) reasons += "APK version does not match catalog"
        if (evidence.sizeBytes != entry.artifact.sizeBytes) reasons += "APK size does not match catalog"
        if (!secureSha256Equals(entry.artifact.apkSha256, evidence.apkSha256)) {
            reasons += "APK SHA-256 does not match catalog"
        }
        if (!secureSha256Equals(expectedSigner, actualSigner)) {
            reasons += "APK signing certificate does not match catalog"
        }
        if (reasons.isNotEmpty()) return PluginPackageVerificationResult.Rejected(reasons.distinct())

        val trustedSigners = trustedSignerFingerprints.mapTo(mutableSetOf(), ::normalizeSha256)
        return PluginPackageVerificationResult.Verified(
            VerifiedPluginPackage(
                entry = entry,
                evidence = evidence,
                publisherTrusted = actualSigner in trustedSigners,
            ),
        )
    }

    private fun validateEntry(entry: PluginCatalogEntry): List<String> = buildList {
        val manifest = entry.manifest
        val artifact = entry.artifact
        if (!PLUGIN_ID.matches(manifest.id)) add("Invalid plugin id: ${manifest.id}")
        if (manifest.id != artifact.packageName) add("Plugin id must match APK package name")
        if (manifest.versionCode <= 0) add("Plugin versionCode must be positive")
        if (manifest.minAppVersion <= 0) add("Plugin minAppVersion must be positive")
        if (manifest.displayName.isBlank()) add("Plugin display name is required")
        if (manifest.author.isBlank()) add("Plugin author is required")
        if (manifest.capabilities.isEmpty()) add("Plugin must declare at least one capability")
        if (!isSha256(manifest.signatureFingerprint)) add("Invalid signing certificate SHA-256")
        if (!isSha256(artifact.apkSha256)) add("Invalid APK SHA-256")
        if (artifact.sizeBytes <= 0) add("APK size must be positive")
        if (artifact.protocolVersion != PluginArtifact.CURRENT_PROTOCOL_VERSION) {
            add("Unsupported plugin protocol ${artifact.protocolVersion}")
        }
        if (artifact.serviceClassName.isBlank()) add("Plugin service class is required")
        val uri = runCatching { URI(artifact.apkUrl) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) add("Plugin APK URL must use HTTPS")
        val duplicateTools = manifest.capabilities
            .flatMap { it.toolDescriptors }
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateTools.isNotEmpty()) add("Duplicate tool names: ${duplicateTools.sorted().joinToString()}")
        val duplicateCapabilities = manifest.capabilities.groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateCapabilities.isNotEmpty()) {
            add("Duplicate capabilities: ${duplicateCapabilities.sorted().joinToString()}")
        }
        manifest.permissions.forEach { permission ->
            if (permission.rationale.isBlank()) add("Every plugin permission needs a rationale")
            if (permission.type == PermissionType.CUSTOM && permission.custom.isNullOrBlank()) {
                add("Custom plugin permissions need an identifier")
            }
        }
    }

    private fun secureSha256Equals(left: String, right: String): Boolean {
        val leftBytes = normalizeSha256(left).toByteArray(Charsets.US_ASCII)
        val rightBytes = normalizeSha256(right).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }

    private fun isSha256(value: String): Boolean = SHA256.matches(normalizeSha256(value))

    private fun normalizeSha256(value: String): String =
        value.filterNot { it == ':' || it.isWhitespace() }.uppercase()

    private companion object {
        val SHA256 = Regex("[0-9A-F]{64}")
        val PLUGIN_ID = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    }
}
