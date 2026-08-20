package com.hermes.agent.domain.plugin

/** Supplies the app-private directory used for immutable plugin APK downloads. */
fun interface PluginArtifactDirectoryProvider {
    fun directoryPath(): String
}

/** Exact verified and authorized package handed to Android's system installer. */
data class PluginInstallHandoffRequest(
    val artifact: DownloadedPluginArtifact,
    val verifiedPackage: VerifiedPluginPackage,
    val authorization: PluginInstallAuthorizationResult.Authorized,
)

sealed interface PluginInstallHandoffResult {
    data class Launched(
        val pluginId: String,
        val versionCode: Int,
    ) : PluginInstallHandoffResult

    data object PermissionRequired : PluginInstallHandoffResult
}

/** Platform boundary for transferring an approved APK to the system package installer. */
interface PluginPackageInstaller {
    fun canInstallPackages(): Boolean

    suspend fun openInstallPermissionSettings(): Result<Unit>

    suspend fun handoff(
        request: PluginInstallHandoffRequest,
    ): Result<PluginInstallHandoffResult>
}
