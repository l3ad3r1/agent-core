package com.hermes.agent.data.security

import com.hermes.agent.domain.backup.CredentialsBackup
import com.hermes.agent.domain.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads credentials out of settings and puts them back.
 *
 * Kept apart from [BackupCipher] because the two answer different questions —
 * what counts as a credential, versus how a file is sealed — and only this half
 * needs to know about settings.
 */
@Singleton
class CredentialVault @Inject constructor(
    private val settings: SettingsRepository,
) {

    suspend fun collect(): CredentialsBackup {
        val s = settings.current()
        return CredentialsBackup(
            cloudApiKey = s.cloudApiKey,
            auxApiKey = s.auxApiKey,
            apiServerKey = s.apiServerKey,
            sshPassword = s.sshPassword,
            homeAssistantToken = s.homeAssistantToken,
            providerKeys = s.cloudProviderProfiles
                .filter { it.apiKey.isNotBlank() }
                .associate { it.id to it.apiKey },
        )
    }

    /** Applies [credentials], returning how many values were actually written. */
    suspend fun apply(credentials: CredentialsBackup): Int {
        var restored = 0
        if (credentials.cloudApiKey.isNotBlank()) {
            settings.setCloudApiKey(credentials.cloudApiKey); restored++
        }
        if (credentials.auxApiKey.isNotBlank()) {
            settings.setAuxApiKey(credentials.auxApiKey); restored++
        }
        if (credentials.homeAssistantToken.isNotBlank()) {
            settings.setHomeAssistantToken(credentials.homeAssistantToken); restored++
        }
        if (credentials.providerKeys.isNotEmpty()) {
            val profiles = settings.current().cloudProviderProfiles
            val updated = profiles.map { profile ->
                val key = credentials.providerKeys[profile.id] ?: return@map profile
                restored++
                profile.copy(apiKey = key)
            }
            if (updated != profiles) settings.setCloudProviderProfiles(updated)
        }
        return restored
    }
}
