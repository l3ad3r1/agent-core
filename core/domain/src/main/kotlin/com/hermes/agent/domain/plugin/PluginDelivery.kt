package com.hermes.agent.domain.plugin

/** Supplies request authorization without making shared delivery code own credentials. */
fun interface PluginRequestAuthorizer {
    fun authorizationHeaderFor(url: String): String?

    companion object {
        val NONE = PluginRequestAuthorizer { null }
    }
}

/** Retrieves and validates one schema-versioned repository catalog. */
fun interface PluginCatalogFetcher {
    suspend fun fetch(
        catalogUrl: String,
        authorizer: PluginRequestAuthorizer,
    ): Result<PluginCatalog>

    suspend fun fetch(catalogUrl: String): Result<PluginCatalog> =
        fetch(catalogUrl, PluginRequestAuthorizer.NONE)
}

/** Immutable artifact staged locally after its catalog size and digest are verified. */
data class DownloadedPluginArtifact(
    val entry: PluginCatalogEntry,
    val apkPath: String,
    val sizeBytes: Long,
    val apkSha256: String,
)

/** Downloads one catalog artifact into a caller-owned private directory. */
fun interface PluginArtifactDownloader {
    suspend fun download(
        entry: PluginCatalogEntry,
        destinationDirectory: String,
        authorizer: PluginRequestAuthorizer,
    ): Result<DownloadedPluginArtifact>

    suspend fun download(
        entry: PluginCatalogEntry,
        destinationDirectory: String,
    ): Result<DownloadedPluginArtifact> =
        download(entry, destinationDirectory, PluginRequestAuthorizer.NONE)
}
