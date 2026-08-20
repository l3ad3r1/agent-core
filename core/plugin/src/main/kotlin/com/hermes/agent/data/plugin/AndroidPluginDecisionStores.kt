package com.hermes.agent.data.plugin

import android.content.Context
import android.content.SharedPreferences
import com.hermes.agent.domain.plugin.PluginInstallApproval
import com.hermes.agent.domain.plugin.PluginInstallApprovalRequest
import com.hermes.agent.domain.plugin.PluginInstallApprovalStore
import com.hermes.agent.domain.plugin.PluginPublisherTrust
import com.hermes.agent.domain.plugin.PluginPublisherTrustStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "plugin_decisions_v1"
private const val TRUST_KEY = "publisher_trust"
private const val APPROVAL_KEY = "install_approvals"
private const val MAX_RECORDS = 128

@Singleton
internal class PluginDecisionPreferences @Inject constructor(
    private val preferences: SharedPreferences,
) {
    val mutex = Mutex()
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }

    fun read(key: String): String? = preferences.getString(key, null)
    fun write(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "Could not persist plugin decision" }
    }
}

@Singleton
internal class AndroidPluginPublisherTrustStore @Inject constructor(
    private val storage: PluginDecisionPreferences,
) : PluginPublisherTrustStore {
    override suspend fun isTrusted(pluginId: String, signerCertificateSha256: String): Result<Boolean> =
        runCatching { storage.mutex.withLock { readTrust().any { it.pluginId == pluginId && it.signerCertificateSha256 == normalize(signerCertificateSha256) } } }

    override suspend fun setTrusted(pluginId: String, signerCertificateSha256: String, trustedAtEpochSeconds: Long): Result<Unit> =
        runCatching { storage.mutex.withLock {
            val trust = PluginPublisherTrust(requireId(pluginId), requireDigest(signerCertificateSha256), trustedAtEpochSeconds)
            val next = readTrust().filterNot { it.pluginId == trust.pluginId && it.signerCertificateSha256 == trust.signerCertificateSha256 } + trust
            storage.write(TRUST_KEY, storage.json.encodeToString(next.takeLast(MAX_RECORDS).map(StoredTrust::fromDomain)))
        } }

    override suspend fun revoke(pluginId: String, signerCertificateSha256: String): Result<Unit> =
        runCatching { storage.mutex.withLock {
            val id = requireId(pluginId); val signer = requireDigest(signerCertificateSha256)
            storage.write(TRUST_KEY, storage.json.encodeToString(readTrust().filterNot { it.pluginId == id && it.signerCertificateSha256 == signer }.map(StoredTrust::fromDomain)))
        } }

    private fun readTrust(): List<PluginPublisherTrust> = storage.read(TRUST_KEY)?.let { storage.json.decodeFromString<List<StoredTrust>>(it).map(StoredTrust::toDomain) } ?: emptyList()
}

@Singleton
internal class AndroidPluginInstallApprovalStore @Inject constructor(
    private val storage: PluginDecisionPreferences,
) : PluginInstallApprovalStore {
    override suspend fun save(approval: PluginInstallApproval): Result<Unit> = runCatching { storage.mutex.withLock {
        require(approval.approvedAtEpochSeconds >= 0) { "Approval timestamp is invalid" }
        val stored = StoredApproval.fromDomain(approval)
        val next = readApprovals().filterNot { it.sameArtifact(stored) } + stored
        storage.write(APPROVAL_KEY, storage.json.encodeToString(next.sortedByDescending { it.approvedAtEpochSeconds }.take(MAX_RECORDS)))
    } }

    override suspend fun find(request: PluginInstallApprovalRequest): Result<PluginInstallApproval?> = runCatching { storage.mutex.withLock {
        val expected = StoredApproval.fromDomain(PluginInstallApproval(request, 0))
        readApprovals().firstOrNull { it.sameArtifact(expected) }?.toDomain()
    } }

    override suspend fun revoke(request: PluginInstallApprovalRequest): Result<Unit> = runCatching { storage.mutex.withLock {
        val expected = StoredApproval.fromDomain(PluginInstallApproval(request, 0))
        storage.write(APPROVAL_KEY, storage.json.encodeToString(readApprovals().filterNot { it.sameArtifact(expected) }))
    } }

    private fun readApprovals(): List<StoredApproval> = storage.read(APPROVAL_KEY)?.let { storage.json.decodeFromString(it) } ?: emptyList()
}

@Serializable
private data class StoredTrust(val pluginId: String, val signerCertificateSha256: String, val trustedAtEpochSeconds: Long) {
    fun toDomain() = PluginPublisherTrust(pluginId, signerCertificateSha256, trustedAtEpochSeconds)
    companion object { fun fromDomain(value: PluginPublisherTrust) = StoredTrust(value.pluginId, value.signerCertificateSha256, value.trustedAtEpochSeconds) }
}

@Serializable
private data class StoredApproval(
    val request: PluginInstallApprovalRequest,
    val approvedAtEpochSeconds: Long,
) {
    fun sameArtifact(other: StoredApproval): Boolean = request == other.request
    fun toDomain() = PluginInstallApproval(request, approvedAtEpochSeconds)
    companion object { fun fromDomain(value: PluginInstallApproval) = StoredApproval(value.request.normalized(), value.approvedAtEpochSeconds) }
}

private fun PluginInstallApprovalRequest.normalized() = copy(apkSha256 = normalize(apkSha256), signerCertificateSha256 = normalize(signerCertificateSha256), permissions = permissions.toList())
private fun normalize(value: String): String = value.filterNot { it == ':' || it.isWhitespace() }.uppercase()
private fun requireDigest(value: String): String = normalize(value).also { require(it.length == 64 && it.all { character -> character in '0'..'9' || character in 'A'..'F' }) { "Signer fingerprint is invalid" } }
private fun requireId(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 200) { "Plugin id is invalid" } }

@Module
@InstallIn(SingletonComponent::class)
internal object PluginDecisionStorageModule {
    @Provides @Singleton
    fun providePreferences(@ApplicationContext context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PluginDecisionStoreBindings {
    @Binds abstract fun bindTrustStore(value: AndroidPluginPublisherTrustStore): PluginPublisherTrustStore
    @Binds abstract fun bindApprovalStore(value: AndroidPluginInstallApprovalStore): PluginInstallApprovalStore
}
