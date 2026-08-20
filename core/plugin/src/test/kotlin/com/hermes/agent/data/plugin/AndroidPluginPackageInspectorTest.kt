package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class AndroidPluginPackageInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val manifest = PluginManifest(
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

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val io = dispatcher
        override val default = dispatcher
        override val main = dispatcher
        override val unconfined = dispatcher
    }

    @Test
    fun `inspector hashes APK signer and decodes embedded manifest`() = runTest {
        val apk = temporaryFolder.newFile("weather.apk").apply { writeBytes("apk-body".toByteArray()) }
        val signer = "signer-certificate".toByteArray()
        val serviceName = "com.example.weather.PluginService"
        val codec = PluginManifestCodec()
        val reader = PluginApkArchiveReader {
            PluginApkArchiveMetadata(
                packageName = manifest.id,
                versionCode = manifest.versionCode,
                signerCertificates = listOf(signer),
                manifestJson = codec.encode(manifest),
                exportedServiceClassNames = setOf(serviceName),
            )
        }
        val inspector = AndroidPluginPackageInspector(
            reader,
            codec,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )

        val evidence = inspector.inspect(apk.path).getOrThrow()

        assertEquals(manifest, evidence.manifest)
        assertEquals(manifest.id, evidence.packageName)
        assertEquals(manifest.versionCode, evidence.versionCode)
        assertEquals(apk.length(), evidence.sizeBytes)
        assertEquals(sha256(apk.readBytes()), evidence.apkSha256)
        assertEquals(sha256(signer), evidence.signerCertificateSha256)
        assertEquals(setOf(serviceName), evidence.exportedServiceClassNames)
    }

    @Test
    fun `inspector rejects packages with multiple current signers`() = runTest {
        val apk = temporaryFolder.newFile("weather.apk").apply { writeBytes(byteArrayOf(1)) }
        val codec = PluginManifestCodec()
        val inspector = AndroidPluginPackageInspector(
            PluginApkArchiveReader {
                PluginApkArchiveMetadata(
                    packageName = manifest.id,
                    versionCode = manifest.versionCode,
                    signerCertificates = listOf(byteArrayOf(1), byteArrayOf(2)),
                    manifestJson = codec.encode(manifest),
                    exportedServiceClassNames = emptySet(),
                )
            },
            codec,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )

        val result = inspector.inspect(apk.path)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("exactly one"))
    }

    @Test
    fun `inspector rejects non APK inputs before archive parsing`() = runTest {
        val input = temporaryFolder.newFile("weather.zip")
        var archiveReads = 0
        val inspector = AndroidPluginPackageInspector(
            PluginApkArchiveReader {
                archiveReads++
                error("must not run")
            },
            PluginManifestCodec(),
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )

        val result = inspector.inspect(input.path)

        assertTrue(result.isFailure)
        assertEquals(0, archiveReads)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02X".format(it) }
}
