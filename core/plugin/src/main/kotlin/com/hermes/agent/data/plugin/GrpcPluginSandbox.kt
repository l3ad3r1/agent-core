package com.hermes.agent.data.plugin

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginSandbox
import com.hermes.agent.domain.tool.ToolRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * gRPC-oriented [PluginSandbox] for remotely hosted plugins.
 *
 * Per Section 3.3 of the plan: "Plugins are packaged as Android APK
 * modules with a defined interface contract that exposes capabilities
 * to the agent orchestration layer." Third-party plugins run in their
 * own process, isolated from the host by the Android sandbox, and
 * communicate with the host via gRPC over a local UNIX-domain socket.
 *
 * Android service discovery and wire serialization are supplied by a [GrpcPluginTransport]
 * contribution. With no contribution, the sandbox is honestly unavailable. This avoids the
 * previous native-library probe: grpc-java/grpc-android does not provide a `grpc_wrap` library,
 * so that probe could never establish whether a usable transport was installed.
 */
@Singleton
class GrpcPluginSandbox @Inject constructor(
    private val toolRegistry: ToolRegistry,
    transports: Set<@JvmSuppressWildcards GrpcPluginTransport>,
) : PluginSandbox {

    override val name: String = "Grpc"
    private val transports = transports.sortedBy { it.name }
    private val mutex = Mutex()
    private val activeTransports = mutableMapOf<String, GrpcPluginTransport>()

    override suspend fun load(plugin: Plugin, context: PluginContext): PluginLifecycleResult {
        val transport = availableTransport()
        if (transport == null) {
            return PluginLifecycleResult.Failure(
                message = "No remote plugin transport is available for ${plugin.manifest.id}.",
                recoverable = true,
            )
        }
        val result = runCatching { transport.load(plugin, context) }
            .getOrElse { return failure(plugin, "load", it) }
        if (result !is PluginLifecycleResult.Success) return result

        val registeredTools = mutableListOf<String>()
        return try {
            plugin.tools().forEach { tool ->
                toolRegistry.register(tool)
                registeredTools += tool.descriptor.name
            }
            mutex.withLock { activeTransports[plugin.manifest.id] = transport }
            result
        } catch (throwable: Throwable) {
            registeredTools.forEach { toolName ->
                runCatching { toolRegistry.unregister(toolName) }
            }
            runCatching { transport.unload(plugin) }
                .onFailure { Timber.tag("GrpcSandbox").w(it, "Rollback unload failed") }
            failure(plugin, "tool registration", throwable)
        }
    }

    override suspend fun suspend_(plugin: Plugin): PluginLifecycleResult {
        val transport = activeTransport(plugin) ?: return notLoaded(plugin)
        return runCatching { transport.suspend_(plugin) }
            .getOrElse { failure(plugin, "suspend", it) }
    }

    override suspend fun resume(plugin: Plugin): PluginLifecycleResult {
        val transport = activeTransport(plugin) ?: return notLoaded(plugin)
        return runCatching { transport.resume(plugin) }
            .getOrElse { failure(plugin, "resume", it) }
    }

    override suspend fun unload(plugin: Plugin): PluginLifecycleResult {
        val transport = activeTransport(plugin) ?: return notLoaded(plugin)
        var cleanupFailure: Throwable? = null
        runCatching { plugin.tools().map { it.descriptor.name } }
            .onSuccess { toolNames ->
                toolNames.forEach { toolName ->
                    runCatching { toolRegistry.unregister(toolName) }
                        .onFailure { if (cleanupFailure == null) cleanupFailure = it }
                }
            }
            .onFailure { cleanupFailure = it }

        val unloadResult = runCatching { transport.unload(plugin) }
            .getOrElse { failure(plugin, "unload", it) }
        mutex.withLock { activeTransports.remove(plugin.manifest.id) }
        return cleanupFailure?.let { failure(plugin, "tool cleanup", it) } ?: unloadResult
    }

    override suspend fun isAvailable(): Boolean = availableTransport() != null

    private suspend fun availableTransport(): GrpcPluginTransport? {
        for (transport in transports) {
            val available = runCatching { transport.isAvailable() }
                .onFailure {
                    Timber.tag("GrpcSandbox").w(it, "Availability probe failed for %s", transport.name)
                }
                .getOrDefault(false)
            if (available) return transport
        }
        return null
    }

    private suspend fun activeTransport(plugin: Plugin): GrpcPluginTransport? =
        mutex.withLock { activeTransports[plugin.manifest.id] }

    private fun notLoaded(plugin: Plugin) = PluginLifecycleResult.Failure(
        message = "Remote plugin ${plugin.manifest.id} is not loaded.",
        recoverable = true,
    )

    private fun failure(plugin: Plugin, operation: String, throwable: Throwable): PluginLifecycleResult.Failure {
        Timber.tag("GrpcSandbox").w(throwable, "%s failed for %s", operation, plugin.manifest.id)
        return PluginLifecycleResult.Failure(
            message = throwable.message ?: "$operation failed",
            recoverable = true,
        )
    }
}
