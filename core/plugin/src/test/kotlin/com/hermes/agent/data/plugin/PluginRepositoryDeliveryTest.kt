package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.PluginArtifact
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginCatalog
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.PluginRequestAuthorizer
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.util.DispatcherProvider
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginRepositoryDeliveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val verifier = PluginPackageVerifier()
    private val codec = PluginCatalogCodec(verifier)

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val io = dispatcher
        override val default = dispatcher
        override val main = dispatcher
        override val unconfined = dispatcher
    }

    private class StaticResponseInterceptor(
        private val code: Int = 200,
        private val responseBytes: ByteArray,
    ) : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(responseBytes.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
    }

    @Test
    fun `catalog fetch uses scoped authorization and strict decoding`() = runTest {
        val entry = entry("catalog artifact".toByteArray())
        val catalog = PluginCatalog(1, 1234, listOf(entry))
        val response = StaticResponseInterceptor(
            responseBytes = codec.encode(catalog).getOrThrow().toByteArray(),
        )
        val fetcher = OkHttpPluginCatalogFetcher(
            client(response),
            codec,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )

        val fetched = fetcher.fetch(
            "https://plugins.example/catalog-v1.json",
            PluginRequestAuthorizer { "Bearer repository-token" },
        ).getOrThrow()

        assertEquals(catalog, fetched)
        assertEquals("Bearer repository-token", response.requests.single().header("Authorization"))
        assertEquals("application/json", response.requests.single().header("Accept"))
    }

    @Test
    fun `catalog fetch rejects plaintext URL before transport`() = runTest {
        val response = StaticResponseInterceptor(responseBytes = byteArrayOf())
        val fetcher = OkHttpPluginCatalogFetcher(
            client(response),
            codec,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )

        val result = fetcher.fetch("http://plugins.example/catalog-v1.json")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTPS"))
        assertTrue(response.requests.isEmpty())
    }

    @Test
    fun `catalog fetch rejects a response over the bounded size`() = runTest {
        val response = StaticResponseInterceptor(responseBytes = ByteArray(1024 * 1024 + 1))
        val fetcher = OkHttpPluginCatalogFetcher(
            client(response),
            codec,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )

        val result = fetcher.fetch("https://plugins.example/catalog-v1.json")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("too large"))
    }

    @Test
    fun `artifact download verifies bytes then reuses immutable file`() = runTest {
        val apkBytes = "signed plugin apk".toByteArray()
        val entry = entry(apkBytes)
        val response = StaticResponseInterceptor(responseBytes = apkBytes)
        val downloader = OkHttpPluginArtifactDownloader(
            client(response),
            verifier,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )
        val destination = temporaryFolder.newFolder("plugins")

        val first = downloader.download(
            entry,
            destination.path,
            PluginRequestAuthorizer { "Bearer artifact-token" },
        ).getOrThrow()
        val second = downloader.download(entry, destination.path).getOrThrow()

        assertEquals(sha256(apkBytes), first.apkSha256)
        assertEquals(apkBytes.size.toLong(), first.sizeBytes)
        assertEquals(first, second)
        assertTrue(java.io.File(first.apkPath).readBytes().contentEquals(apkBytes))
        assertEquals(1, response.requests.size)
        assertEquals("Bearer artifact-token", response.requests.single().header("Authorization"))
        assertEquals("application/vnd.android.package-archive", response.requests.single().header("Accept"))
    }

    @Test
    fun `artifact digest mismatch leaves no staged or promoted file`() = runTest {
        val expectedBytes = "expected plugin apk".toByteArray()
        val downloadedBytes = "tampered plugin apk".toByteArray()
        assertEquals(expectedBytes.size, downloadedBytes.size)
        val response = StaticResponseInterceptor(responseBytes = downloadedBytes)
        val downloader = OkHttpPluginArtifactDownloader(
            client(response),
            verifier,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )
        val destination = temporaryFolder.newFolder("plugins")

        val result = downloader.download(entry(expectedBytes), destination.path)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SHA-256"))
        assertFalse(destination.listFiles().orEmpty().any { it.extension == "apk" })
        assertFalse(destination.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun `artifact download rejects catalog size mismatch before writing`() = runTest {
        val expectedBytes = "expected plugin apk".toByteArray()
        val response = StaticResponseInterceptor(responseBytes = byteArrayOf(1, 2, 3))
        val downloader = OkHttpPluginArtifactDownloader(
            client(response),
            verifier,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )
        val destination = temporaryFolder.newFolder("plugins")

        val result = downloader.download(entry(expectedBytes), destination.path)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Content-Length"))
        assertTrue(destination.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `artifact download rejects an oversized catalog entry before transport`() = runTest {
        val response = StaticResponseInterceptor(responseBytes = byteArrayOf())
        val downloader = OkHttpPluginArtifactDownloader(
            client(response),
            verifier,
            TestDispatchers(StandardTestDispatcher(testScheduler)),
        )
        val validEntry = entry("plugin".toByteArray())
        val oversizedEntry = validEntry.copy(
            artifact = validEntry.artifact.copy(sizeBytes = PluginArtifact.MAX_SIZE_BYTES + 1),
        )

        val result = downloader.download(oversizedEntry, temporaryFolder.newFolder("plugins").path)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("between 1"))
        assertTrue(response.requests.isEmpty())
    }

    private fun entry(apkBytes: ByteArray): PluginCatalogEntry {
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
        return PluginCatalogEntry(
            manifest = manifest,
            artifact = PluginArtifact(
                apkUrl = "https://plugins.example/weather.apk",
                apkSha256 = sha256(apkBytes),
                sizeBytes = apkBytes.size.toLong(),
                packageName = manifest.id,
                serviceClassName = "com.example.weather.PluginService",
            ),
        )
    }

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02X".format(it) }
}
