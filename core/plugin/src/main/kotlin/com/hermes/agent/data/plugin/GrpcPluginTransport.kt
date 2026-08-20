package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult

/**
 * Transport used by [GrpcPluginSandbox] to control a plugin hosted outside the app process.
 *
 * The shared engine owns lifecycle routing and tool registration. A concrete transport owns
 * Android service discovery, connection setup, and wire serialization. Keeping those concerns
 * behind this interface lets the host add the Android/gRPC implementation without changing the
 * registry or treating a compiled-in plugin as remote.
 */
interface GrpcPluginTransport {
    /** Stable name used to make transport selection deterministic. */
    val name: String

    suspend fun isAvailable(): Boolean

    suspend fun load(plugin: Plugin, context: PluginContext): PluginLifecycleResult

    suspend fun suspend_(plugin: Plugin): PluginLifecycleResult

    suspend fun resume(plugin: Plugin): PluginLifecycleResult

    suspend fun unload(plugin: Plugin): PluginLifecycleResult
}
