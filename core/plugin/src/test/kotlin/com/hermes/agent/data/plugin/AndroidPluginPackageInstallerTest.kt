package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.DownloadedPluginArtifact
import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.PluginArtifact
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginInstallAuthorizationResult
import com.hermes.agent.domain.plugin.PluginInstallHandoffRequest
import com.hermes.agent.domain.plugin.PluginInstallHandoffResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPackageEvidence
import com.hermes.agent.domain.plugin.PluginPackageInspector
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.VerifiedPluginPackage
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.util.DispatcherProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidPluginPackageInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val io = dispatcher
        override val default = dispatcher
        override val main = dispatcher
        override val unconfined = dispatcher
    }

    private class FakePlatform(
        var installAllowed: Boolean = true,
    ) : PluginInstallerPlatform {
        var permissionSettingsOpened = false
        var launchedPath: String? = null
        var permissionFailure: Exception? = null
        var launchFailure: Exception? = null

        override fun canInstallPackages(): Boolean = installAllowed

        override fun openInstallPermissionSettings() {
            permissionFailure?.let { throw it }
            permissionSettingsOpened = true
        }

        override fun launchInstaller(apkPath: String) {
            launchFailure?.let { throw it }
            launchedPath = apkPath
        }
    }

    @Test
    fun `handoff rechecks approved package then launches system installer`() = runTest {
        val fixture = fixture()
        var inspections = 0
        val platform = FakePlatform()
        val installer = installer(
            inspector = PluginPackageInspector {
                inspections++
                Result.success(fixture.verified.evidence)
            },
            platform = platform,
        )

        val result = installer.handoff(fixture.request).getOrThrow()

        assertEquals(
            PluginInstallHandoffResult.Launched("com.example.weather", 7),
            result,
        )
        assertEquals(1, inspections)
        assertEquals(fixture.artifact.apkPath, platform.launchedPath)
    }

    @Test
    fun `handoff reports missing install permission without reading APK`() = runTest {
        val fixture = fixture()
        var inspections = 0
        val platform = FakePlatform(installAllowed = false)
        val installer = installer(
            inspector = PluginPackageInspector {
                inspections++
                Result.success(fixture.verified.evidence)
            },
            platform = platform,
        )

        val result = installer.handoff(fixture.request).getOrThrow()

        assertEquals(PluginInstallHandoffResult.PermissionRequired, result)
        assertEquals(0, inspections)
        assertEquals(null, platform.launchedPath)
    }

    @Test
    fun `handoff rejects stale authorization before platform launch`() = runTest {
        val fixture = fixture()
        val platform = FakePlatform()
        val staleRequest = fixture.request.copy(
            authorization = fixture.request.authorization.copy(versionCode = 6),
        )
        val installer = installer(
            inspector = PluginPackageInspector { error("must not inspect") },
            platform = platform,
        )

        val result = installer.handoff(staleRequest)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("authorization"))
        assertEquals(null, platform.launchedPath)
    }

    @Test
    fun `handoff rejects APK changed after approval`() = runTest {
        val fixture = fixture()
        val platform = FakePlatform()
        val changedEvidence = fixture.verified.evidence.copy(sizeBytes = 99)
        val installer = installer(
            inspector = PluginPackageInspector { Result.success(changedEvidence) },
            platform = platform,
        )

        val result = installer.handoff(fixture.request)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("changed after approval"))
        assertEquals(null, platform.launchedPath)
    }

    @Test
    fun `handoff preserves inspector cancellation`() = runTest {
        val fixture = fixture()
        val installer = installer(
            inspector = PluginPackageInspector { Result.failure(CancellationException("stop")) },
            platform = FakePlatform(),
        )

        var cancellation: CancellationException? = null
        try {
            installer.handoff(fixture.request)
        } catch (failure: CancellationException) {
            cancellation = failure
        }

        assertEquals("stop", cancellation?.message)
    }

    @Test
    fun `permission settings failures are returned to caller`() = runTest {
        val platform = FakePlatform().apply {
            permissionFailure = IllegalStateException("settings unavailable")
        }
        val installer = installer(
            inspector = PluginPackageInspector { error("unused") },
            platform = platform,
        )

        val result = installer.openInstallPermissionSettings()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("settings unavailable"))
        assertFalse(platform.permissionSettingsOpened)
    }

    @Test
    fun `system installer launch failures are returned to caller`() = runTest {
        val fixture = fixture()
        val platform = FakePlatform().apply {
            launchFailure = IllegalStateException("package installer unavailable")
        }
        val installer = installer(
            inspector = PluginPackageInspector { Result.success(fixture.verified.evidence) },
            platform = platform,
        )

        val result = installer.handoff(fixture.request)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("installer unavailable"))
        assertEquals(null, platform.launchedPath)
    }

    @Test
    fun `artifact path must remain inside private plugin directory`() {
        val pluginDirectory = temporaryFolder.newFolder("plugins")
        val inside = File(pluginDirectory, "weather.apk").apply { writeBytes(byteArrayOf(1)) }
        val outside = temporaryFolder.newFile("outside.apk")

        assertEquals(
            inside.canonicalFile,
            requirePluginArtifactPath(inside.path, pluginDirectory.path),
        )
        val result = runCatching {
            requirePluginArtifactPath(outside.path, pluginDirectory.path)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("outside private"))
    }

    private fun installer(
        inspector: PluginPackageInspector,
        platform: PluginInstallerPlatform,
    ) = AndroidPluginPackageInstaller(
        inspector = inspector,
        platform = platform,
        dispatchers = TestDispatchers(Dispatchers.Unconfined),
    )

    private fun fixture(): Fixture {
        val apk = temporaryFolder.newFolder("plugins-${System.nanoTime()}")
            .resolve("weather.apk")
            .apply { writeBytes("verified-apk".toByteArray()) }
        val manifest = PluginManifest(
            id = "com.example.weather",
            displayName = "Weather",
            versionCode = 7,
            versionName = "1.2.0",
            author = "Example",
            signatureFingerprint = "B".repeat(64),
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
            permissions = listOf(PluginPermission(PermissionType.NETWORK, "Fetch weather")),
        )
        val entry = PluginCatalogEntry(
            manifest = manifest,
            artifact = PluginArtifact(
                apkUrl = "https://plugins.example/weather.apk",
                apkSha256 = "A".repeat(64),
                sizeBytes = apk.length(),
                packageName = manifest.id,
                serviceClassName = "com.example.weather.PluginService",
            ),
        )
        val evidence = PluginPackageEvidence(
            manifest = manifest,
            apkSha256 = entry.artifact.apkSha256,
            sizeBytes = apk.length(),
            packageName = manifest.id,
            versionCode = manifest.versionCode,
            signerCertificateSha256 = manifest.signatureFingerprint,
            exportedServiceClassNames = setOf(entry.artifact.serviceClassName),
        )
        val verified = VerifiedPluginPackage(
            entry = entry,
            evidence = evidence,
            publisherTrusted = true,
        )
        val artifact = DownloadedPluginArtifact(
            entry = entry,
            apkPath = apk.path,
            sizeBytes = apk.length(),
            apkSha256 = entry.artifact.apkSha256,
        )
        val authorization = PluginInstallAuthorizationResult.Authorized(
            pluginId = manifest.id,
            versionCode = manifest.versionCode,
            apkSha256 = entry.artifact.apkSha256,
            signerCertificateSha256 = manifest.signatureFingerprint,
            trustPublisher = false,
        )
        return Fixture(
            artifact = artifact,
            verified = verified,
            request = PluginInstallHandoffRequest(artifact, verified, authorization),
        )
    }

    private data class Fixture(
        val artifact: DownloadedPluginArtifact,
        val verified: VerifiedPluginPackage,
        val request: PluginInstallHandoffRequest,
    )
}
