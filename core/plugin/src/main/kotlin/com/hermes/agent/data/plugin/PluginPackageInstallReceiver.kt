package com.hermes.agent.data.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/** Receives Android's package completion event and records only a pending exact package. */
class PluginPackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val packageName = intent.data?.schemeSpecificPart
        if (intent.action !in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED) || packageName.isNullOrBlank()) {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    PluginInstallReceiverEntryPoint::class.java,
                )
                entryPoint.installStateStore.markInstalled(packageName, System.currentTimeMillis() / 1_000L)
                    .onFailure { Timber.e(it, "Could not record plugin installation for %s", packageName) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PluginInstallReceiverEntryPoint {
    val installStateStore: com.hermes.agent.domain.plugin.PluginInstallStateStore
}
