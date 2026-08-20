package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.DownloadedPluginArtifact
import com.hermes.agent.domain.plugin.PluginArtifactDirectoryProvider
import com.hermes.agent.domain.plugin.PluginArtifactDownloader
import com.hermes.agent.domain.plugin.PluginCatalog
import com.hermes.agent.domain.plugin.PluginCatalogEntry
import com.hermes.agent.domain.plugin.PluginCatalogFetcher
import com.hermes.agent.domain.plugin.PluginModuleDownloadCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginModuleDownloadCoordinatorImpl @Inject constructor(
    private val catalogFetcher: PluginCatalogFetcher,
    private val artifactDownloader: PluginArtifactDownloader,
    private val directoryProvider: PluginArtifactDirectoryProvider,
    private val verifier: PluginPackageVerifier,
) : PluginModuleDownloadCoordinator {
    override suspend fun loadCatalog(catalogUrl: String): Result<PluginCatalog> {
        val normalizedUrl = catalogUrl.trim()
        if (normalizedUrl.isBlank()) return Result.failure(IllegalArgumentException("Enter a module repository URL."))
        return catalogFetcher.fetch(normalizedUrl).mapCatching { catalog ->
            val errors = verifier.verifyCatalog(catalog)
            require(errors.isEmpty()) { errors.joinToString("; ") }
            catalog
        }
    }

    override suspend fun download(entry: PluginCatalogEntry): Result<DownloadedPluginArtifact> =
        artifactDownloader.download(entry, directoryProvider.directoryPath())
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PluginModuleDownloadCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindPluginModuleDownloadCoordinator(
        implementation: PluginModuleDownloadCoordinatorImpl,
    ): PluginModuleDownloadCoordinator
}
