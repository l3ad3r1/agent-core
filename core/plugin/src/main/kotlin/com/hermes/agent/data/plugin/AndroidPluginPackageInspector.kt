package com.hermes.agent.data.plugin

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.hermes.agent.domain.plugin.PluginPackageEvidence
import com.hermes.agent.domain.plugin.PluginPackageInspector
import com.hermes.agent.util.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PluginApkArchiveMetadata(
    val packageName: String,
    val versionCode: Int,
    val signerCertificates: List<ByteArray>,
    val manifestJson: String,
    val exportedServiceClassNames: Set<String>,
)

internal fun interface PluginApkArchiveReader {
    fun read(apkPath: String): PluginApkArchiveMetadata
}

@Singleton
internal class AndroidPluginApkArchiveReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PluginApkArchiveReader {
    override fun read(apkPath: String): PluginApkArchiveMetadata {
        val flags = PackageManager.GET_META_DATA or
            PackageManager.GET_SERVICES or
            PackageManager.GET_SIGNING_CERTIFICATES
        val packageInfo = archiveInfo(apkPath, flags)
            ?: error("Android could not parse the plugin APK")
        val applicationInfo = packageInfo.applicationInfo
            ?: error("Plugin APK has no application metadata")
        val manifestJson = applicationInfo.metaData?.getString(PLUGIN_MANIFEST_METADATA_KEY)
            ?: error("Plugin APK is missing $PLUGIN_MANIFEST_METADATA_KEY")
        val signers = packageInfo.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray() }
            .orEmpty()
        val services = packageInfo.services
            .orEmpty()
            .filter { it.exported }
            .mapTo(mutableSetOf()) { it.name }
        val versionCode = packageInfo.longVersionCode
        require(versionCode in 1..Int.MAX_VALUE) { "Plugin versionCode is out of range" }
        return PluginApkArchiveMetadata(
            packageName = packageInfo.packageName,
            versionCode = versionCode.toInt(),
            signerCertificates = signers,
            manifestJson = manifestJson,
            exportedServiceClassNames = services,
        )
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(apkPath: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkPath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            context.packageManager.getPackageArchiveInfo(apkPath, flags)
        }

    companion object {
        const val PLUGIN_MANIFEST_METADATA_KEY = "com.hermes.agent.PLUGIN_MANIFEST_V1"
    }
}

@Singleton
class AndroidPluginPackageInspector @Inject internal constructor(
    private val archiveReader: PluginApkArchiveReader,
    private val manifestCodec: PluginManifestCodec,
    private val dispatchers: DispatcherProvider,
) : PluginPackageInspector {
    override suspend fun inspect(apkPath: String): Result<PluginPackageEvidence> =
        withContext(dispatchers.io) {
            resultPreservingInspectionCancellation {
                val apk = File(apkPath).canonicalFile
                require(apk.isFile) { "Plugin APK does not exist" }
                require(apk.extension.equals("apk", ignoreCase = true)) { "Plugin package must be an APK" }
                val archive = archiveReader.read(apk.path)
                require(archive.signerCertificates.size == 1) {
                    "Plugin APK must have exactly one current signer"
                }
                PluginPackageEvidence(
                    manifest = manifestCodec.decode(archive.manifestJson).getOrThrow(),
                    apkSha256 = sha256(apk),
                    sizeBytes = apk.length(),
                    packageName = archive.packageName,
                    versionCode = archive.versionCode,
                    signerCertificateSha256 = sha256(archive.signerCertificates.single()),
                    exportedServiceClassNames = archive.exportedServiceClassNames,
                )
            }
        }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }
}

private inline fun <T> resultPreservingInspectionCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PluginPackageInspectorModule {
    @Binds
    abstract fun bindPluginPackageInspector(
        implementation: AndroidPluginPackageInspector,
    ): PluginPackageInspector

    @Binds
    abstract fun bindPluginApkArchiveReader(
        implementation: AndroidPluginApkArchiveReader,
    ): PluginApkArchiveReader
}
