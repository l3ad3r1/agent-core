package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.PluginManifest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** JSON codec for the plugin manifest embedded in an APK's application metadata. */
@Singleton
class PluginManifestCodec @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun decode(rawManifest: String): Result<PluginManifest> =
        runCatching { json.decodeFromString<PluginManifest>(rawManifest) }

    fun encode(manifest: PluginManifest): String = json.encodeToString(manifest)
}
