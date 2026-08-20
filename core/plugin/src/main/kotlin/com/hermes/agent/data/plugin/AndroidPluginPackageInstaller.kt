package com.hermes.agent.data.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hermes.agent.domain.plugin.PluginArtifactDirectoryProvider
import com.hermes.agent.domain.plugin.PluginInstallHandoffRequest
import com.hermes.agent.domain.plugin.PluginInstallHandoffResult
import com.hermes.agent.domain.plugin.PluginPackageInspector
import com.hermes.agent.domain.plugin.PluginPackageInstaller
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
import kotlinx.coroutines.withContext

@Singleton
class AndroidPluginArtifactDirectoryProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PluginArtifactDirectoryProvider {
    override fun directoryPath(): String = File(context.filesDir, PLUGIN_DIRECTORY_NAME).path
}

internal interface PluginInstallerPlatform {
    fun canInstallPackages(): Boolean
    fun openInstallPermissionSettings()
    fun launchInstaller(apkPath: String)
}

@Singleton
internal class AndroidPluginInstallerPlatform @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val directoryProvider: PluginArtifactDirectoryProvider,
) : PluginInstallerPlatform {
    override fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun launchInstaller(apkPath: String) {
        val apk = requirePluginArtifactPath(apkPath, directoryProvider.directoryPath())
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

@Singleton
class AndroidPluginPackageInstaller @Inject internal constructor(
    private val inspector: PluginPackageInspector,
    private val platform: PluginInstallerPlatform,
    private val dispatchers: DispatcherProvider,
) : PluginPackageInstaller {
    override fun canInstallPackages(): Boolean = platform.canInstallPackages()

    override suspend fun openInstallPermissionSettings(): Result<Unit> =
        withContext(dispatchers.main) {
            resultPreservingInstallerCancellation {
                platform.openInstallPermissionSettings()
            }
        }

    override suspend fun handoff(
        request: PluginInstallHandoffRequest,
    ): Result<PluginInstallHandoffResult> = withContext(dispatchers.io) {
        resultPreservingInstallerCancellation {
            validateAuthorizationBinding(request)
            if (!platform.canInstallPackages()) {
                return@resultPreservingInstallerCancellation PluginInstallHandoffResult.PermissionRequired
            }

            val currentEvidence = inspector.inspect(request.artifact.apkPath).getOrThrow()
            require(currentEvidence == request.verifiedPackage.evidence) {
                "Plugin APK changed after approval"
            }
            withContext(dispatchers.main) {
                platform.launchInstaller(request.artifact.apkPath)
            }
            PluginInstallHandoffResult.Launched(
                pluginId = request.verifiedPackage.entry.manifest.id,
                versionCode = request.verifiedPackage.entry.manifest.versionCode,
            )
        }
    }

    private fun validateAuthorizationBinding(request: PluginInstallHandoffRequest) {
        val artifact = request.artifact
        val verified = request.verifiedPackage
        val authorization = request.authorization
        val approval = verified.approvalRequest()

        require(artifact.entry == verified.entry) {
            "Downloaded artifact does not match the verified package"
        }
        require(artifact.sizeBytes == verified.evidence.sizeBytes) {
            "Downloaded artifact size does not match the verified package"
        }
        require(secureDigestEquals(artifact.apkSha256, verified.evidence.apkSha256)) {
            "Downloaded artifact digest does not match the verified package"
        }
        require(
            authorization.pluginId == approval.pluginId &&
                authorization.versionCode == approval.versionCode &&
                secureDigestEquals(authorization.apkSha256, approval.apkSha256) &&
                secureDigestEquals(
                    authorization.signerCertificateSha256,
                    approval.signerCertificateSha256,
                ),
        ) { "Install authorization does not match the verified package" }
    }
}

internal fun requirePluginArtifactPath(apkPath: String, directoryPath: String): File {
    val directory = File(directoryPath).canonicalFile
    val apk = File(apkPath).canonicalFile
    require(directory.isDirectory) { "Plugin artifact directory is unavailable" }
    require(apk.isFile && apk.extension.equals("apk", ignoreCase = true)) {
        "Plugin artifact is not an APK file"
    }
    require(apk.toPath().startsWith(directory.toPath()) && apk != directory) {
        "Plugin artifact is outside private plugin storage"
    }
    return apk
}

private fun secureDigestEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    normalizeInstallerDigest(left).toByteArray(Charsets.US_ASCII),
    normalizeInstallerDigest(right).toByteArray(Charsets.US_ASCII),
)

private fun normalizeInstallerDigest(value: String): String =
    value.filterNot { it == ':' || it.isWhitespace() }.uppercase()

private inline fun <T> resultPreservingInstallerCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PluginPackageInstallerModule {
    @Binds
    abstract fun bindPluginArtifactDirectoryProvider(
        implementation: AndroidPluginArtifactDirectoryProvider,
    ): PluginArtifactDirectoryProvider

    @Binds
    abstract fun bindPluginInstallerPlatform(
        implementation: AndroidPluginInstallerPlatform,
    ): PluginInstallerPlatform

    @Binds
    abstract fun bindPluginPackageInstaller(
        implementation: AndroidPluginPackageInstaller,
    ): PluginPackageInstaller
}

private const val PLUGIN_DIRECTORY_NAME = "plugins"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
