package com.hermes.agent.domain.plugin

import kotlinx.serialization.Serializable

/** Versioned public-repository document describing downloadable plugin APKs. */
@Serializable
data class PluginCatalog(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val generatedAtEpochSeconds: Long,
    val plugins: List<PluginCatalogEntry>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Catalog identity and immutable artifact metadata for one published plugin version. */
@Serializable
data class PluginCatalogEntry(
    val manifest: PluginManifest,
    val artifact: PluginArtifact,
)

/** Fields that bind a catalog entry to one APK and its remote service endpoint. */
@Serializable
data class PluginArtifact(
    val apkUrl: String,
    val apkSha256: String,
    val sizeBytes: Long,
    val packageName: String,
    val serviceClassName: String,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
) {
    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
    }
}

/** Facts extracted from the downloaded APK by a platform package inspector. */
data class PluginPackageEvidence(
    val manifest: PluginManifest,
    val apkSha256: String,
    val sizeBytes: Long,
    val packageName: String,
    val versionCode: Int,
    val signerCertificateSha256: String,
)

/** Verified immutable package facts safe to present in an approval screen. */
data class VerifiedPluginPackage(
    val entry: PluginCatalogEntry,
    val evidence: PluginPackageEvidence,
    val publisherTrusted: Boolean,
) {
    fun approvalRequest(): PluginInstallApprovalRequest = PluginInstallApprovalRequest(
        pluginId = entry.manifest.id,
        displayName = entry.manifest.displayName,
        versionCode = entry.manifest.versionCode,
        versionName = entry.manifest.versionName,
        apkSha256 = normalizeSha256(evidence.apkSha256),
        signerCertificateSha256 = normalizeSha256(evidence.signerCertificateSha256),
        permissions = entry.manifest.permissions,
        publisherTrusted = publisherTrusted,
    )
}

/** Exact review snapshot shown to the user before installation is authorized. */
data class PluginInstallApprovalRequest(
    val pluginId: String,
    val displayName: String,
    val versionCode: Int,
    val versionName: String,
    val apkSha256: String,
    val signerCertificateSha256: String,
    val permissions: List<PluginPermission>,
    val publisherTrusted: Boolean,
)

/** User response bound to a specific immutable approval request. */
data class PluginInstallApprovalDecision(
    val request: PluginInstallApprovalRequest,
    val approved: Boolean,
    val trustPublisher: Boolean = false,
)

/** Opaque result consumed by the future downloader/installer orchestration. */
sealed interface PluginInstallAuthorizationResult {
    data class Authorized(
        val pluginId: String,
        val versionCode: Int,
        val apkSha256: String,
        val signerCertificateSha256: String,
        val trustPublisher: Boolean,
    ) : PluginInstallAuthorizationResult

    data class Denied(val reason: String) : PluginInstallAuthorizationResult
}

internal fun normalizeSha256(value: String): String =
    value.filterNot { it == ':' || it.isWhitespace() }.uppercase()
