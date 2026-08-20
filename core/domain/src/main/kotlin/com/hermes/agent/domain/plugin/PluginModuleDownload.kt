package com.hermes.agent.domain.plugin

/** Shared application boundary for browsing and downloading public plugin modules. */
interface PluginModuleDownloadCoordinator {
    suspend fun loadCatalog(catalogUrl: String): Result<PluginCatalog>

    suspend fun download(entry: PluginCatalogEntry): Result<DownloadedPluginArtifact>
}
