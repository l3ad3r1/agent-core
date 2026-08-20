package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.DownloadedPluginArtifact
import com.hermes.agent.domain.plugin.PluginArtifactDownloader
import com.hermes.agent.domain.plugin.PluginCatalog
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginCatalogFetcher
import com.hermes.agent.domain.plugin.PluginRequestAuthorizer
import com.hermes.agent.util.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Singleton
class OkHttpPluginCatalogFetcher @Inject constructor(
    okHttpClient: OkHttpClient,
    private val codec: PluginCatalogCodec,
    private val dispatchers: DispatcherProvider,
) : PluginCatalogFetcher {
    private val client = okHttpClient.newBuilder()
        .followSslRedirects(false)
        .build()

    override suspend fun fetch(
        catalogUrl: String,
        authorizer: PluginRequestAuthorizer,
    ): Result<PluginCatalog> = withContext(dispatchers.io) {
        resultPreservingCancellation {
            val url = requireSecureUrl(catalogUrl, "Plugin catalog")
            val request = authorizedRequest(url, authorizer)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                requireSuccessfulHttps(response, "Plugin catalog")
                val body = response.body ?: error("Plugin catalog response was empty")
                val declaredLength = body.contentLength()
                require(declaredLength <= MAX_CATALOG_BYTES) { "Plugin catalog is too large" }
                val rawCatalog = readBoundedUtf8(body.byteStream(), MAX_CATALOG_BYTES)
                codec.decode(rawCatalog).getOrThrow()
            }
        }
    }
}

@Singleton
class OkHttpPluginArtifactDownloader @Inject constructor(
    okHttpClient: OkHttpClient,
    private val verifier: PluginPackageVerifier,
    private val dispatchers: DispatcherProvider,
) : PluginArtifactDownloader {
    private val client = okHttpClient.newBuilder()
        .followSslRedirects(false)
        .build()
    private val downloadMutex = Mutex()

    override suspend fun download(
        entry: PluginCatalogEntry,
        destinationDirectory: String,
        authorizer: PluginRequestAuthorizer,
    ): Result<DownloadedPluginArtifact> = withContext(dispatchers.io) {
        resultPreservingCancellation {
            downloadMutex.withLock {
                val entryErrors = verifier.verifyEntry(entry)
                require(entryErrors.isEmpty()) { entryErrors.joinToString("; ") }
                val destination = requirePrivateDirectory(destinationDirectory)
                val expectedDigest = normalizeDigest(entry.artifact.apkSha256)
                val finalApk = File(
                    destination,
                    "${entry.manifest.id}-${entry.manifest.versionCode}-${expectedDigest.take(12)}.apk",
                )
                if (finalApk.exists()) {
                    require(finalApk.isFile) { "Plugin artifact destination is not a file" }
                    require(finalApk.length() == entry.artifact.sizeBytes) {
                        "Existing plugin artifact has the wrong size"
                    }
                    require(digestsEqual(expectedDigest, sha256(finalApk))) {
                        "Existing plugin artifact has the wrong SHA-256"
                    }
                    return@withLock downloaded(entry, finalApk, expectedDigest)
                }

                val requiredSpace = entry.artifact.sizeBytes + DOWNLOAD_HEADROOM_BYTES
                if (destination.usableSpace > 0L) {
                    require(destination.usableSpace >= requiredSpace) {
                        "Not enough private storage for the plugin artifact"
                    }
                }

                val staging = File.createTempFile(".${entry.manifest.id}-", ".part", destination)
                try {
                    downloadToStaging(entry, staging, authorizer)
                    require(staging.renameTo(finalApk)) { "Could not promote the verified plugin artifact" }
                    downloaded(entry, finalApk, expectedDigest)
                } finally {
                    if (staging.exists()) staging.delete()
                }
            }
        }
    }

    private suspend fun downloadToStaging(
        entry: PluginCatalogEntry,
        staging: File,
        authorizer: PluginRequestAuthorizer,
    ) {
        val url = requireSecureUrl(entry.artifact.apkUrl, "Plugin artifact")
        val request = authorizedRequest(url, authorizer)
            .header("Accept", "application/vnd.android.package-archive")
            .build()
        client.newCall(request).execute().use { response ->
            requireSuccessfulHttps(response, "Plugin artifact")
            val body = response.body ?: error("Plugin artifact response was empty")
            val expectedSize = entry.artifact.sizeBytes
            val declaredLength = body.contentLength()
            require(declaredLength < 0L || declaredLength == expectedSize) {
                "Plugin artifact Content-Length does not match the catalog"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            FileOutputStream(staging).use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= expectedSize) {
                            "Plugin artifact exceeded its catalog size"
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            require(written == expectedSize) { "Plugin artifact was incomplete" }
            require(digestsEqual(entry.artifact.apkSha256, digest.digest().toHex())) {
                "Plugin artifact SHA-256 does not match the catalog"
            }
        }
    }

    private fun requirePrivateDirectory(path: String): File {
        val directory = File(path).canonicalFile
        require(directory.isAbsolute && directory.parentFile != null) {
            "Plugin destination must be a private subdirectory"
        }
        require((directory.exists() || directory.mkdirs()) && directory.isDirectory) {
            "Plugin destination directory is unavailable"
        }
        require(directory.canWrite()) { "Plugin destination directory is not writable" }
        return directory
    }

    private fun downloaded(
        entry: PluginCatalogEntry,
        apk: File,
        digest: String,
    ) = DownloadedPluginArtifact(
        entry = entry,
        apkPath = apk.path,
        sizeBytes = apk.length(),
        apkSha256 = normalizeDigest(digest),
    )
}

private fun requireSecureUrl(rawUrl: String, label: String): HttpUrl {
    val url = rawUrl.toHttpUrlOrNull() ?: error("$label URL is invalid")
    require(url.isHttps) { "$label URL must use HTTPS" }
    require(url.username.isEmpty() && url.password.isEmpty()) {
        "$label URL must not contain credentials"
    }
    return url
}

private fun authorizedRequest(
    url: HttpUrl,
    authorizer: PluginRequestAuthorizer,
): Request.Builder = Request.Builder().url(url).apply {
    authorizer.authorizationHeaderFor(url.toString())
        ?.takeIf { it.isNotBlank() }
        ?.let { authorization ->
            require('\r' !in authorization && '\n' !in authorization) {
                "Plugin authorization header is invalid"
            }
            header("Authorization", authorization)
        }
}

private fun requireSuccessfulHttps(response: Response, label: String) {
    require(response.request.url.isHttps) { "$label redirected outside HTTPS" }
    if (!response.isSuccessful) {
        if (response.code >= 500) throw IOException("$label server returned ${response.code}")
        error("$label request was rejected (${response.code})")
    }
}

private suspend fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Long): String {
    val output = ByteArrayOutputStream()
    input.buffered().use { source ->
        val buffer = ByteArray(CATALOG_BUFFER_BYTES)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = source.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Plugin catalog is too large" }
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

private suspend fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun digestsEqual(left: String, right: String): Boolean = MessageDigest.isEqual(
    normalizeDigest(left).toByteArray(Charsets.US_ASCII),
    normalizeDigest(right).toByteArray(Charsets.US_ASCII),
)

private fun normalizeDigest(value: String): String =
    value.filterNot { it == ':' || it.isWhitespace() }.uppercase()

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }

private inline fun <T> resultPreservingCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PluginRepositoryDeliveryModule {
    @Binds
    abstract fun bindPluginCatalogFetcher(
        implementation: OkHttpPluginCatalogFetcher,
    ): PluginCatalogFetcher

    @Binds
    abstract fun bindPluginArtifactDownloader(
        implementation: OkHttpPluginArtifactDownloader,
    ): PluginArtifactDownloader
}

private const val MAX_CATALOG_BYTES = 1024L * 1024L
private const val DOWNLOAD_HEADROOM_BYTES = 8L * 1024L * 1024L
private const val CATALOG_BUFFER_BYTES = 8 * 1024
private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
