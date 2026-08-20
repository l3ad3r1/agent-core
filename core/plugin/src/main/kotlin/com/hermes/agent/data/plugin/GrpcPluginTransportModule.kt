package com.hermes.agent.data.plugin

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Extension point for an app-provided remote plugin transport. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GrpcPluginTransportModule {
    @Multibinds
    abstract fun transports(): Set<GrpcPluginTransport>
}
