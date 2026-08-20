package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PluginCatalog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Strict, version-aware JSON boundary for catalogs fetched from a public repository. */
@Singleton
class PluginCatalogCodec @Inject constructor(
    private val verifier: PluginPackageVerifier,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun decode(rawCatalog: String): Result<PluginCatalog> = runCatching {
        json.decodeFromString<PluginCatalog>(rawCatalog).also(::requireValid)
    }

    fun encode(catalog: PluginCatalog): Result<String> = runCatching {
        requireValid(catalog)
        json.encodeToString(catalog)
    }

    private fun requireValid(catalog: PluginCatalog) {
        val reasons = verifier.verifyCatalog(catalog)
        require(reasons.isEmpty()) { reasons.joinToString("; ") }
    }
}
