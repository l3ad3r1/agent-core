package com.hermes.agent.domain.settings
import com.hermes.agent.domain.llm.*

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observe(): Flow<UserSettings>
    suspend fun current(): UserSettings

    suspend fun setCloudEnabled(enabled: Boolean)
    suspend fun setCloudApiKey(key: String)
    suspend fun setCloudBaseUrl(url: String)
    suspend fun setCloudModel(model: String)

    suspend fun setAppTheme(themeName: String)
    suspend fun setReasoningEffort(effort: String)
    suspend fun setAuxModel(model: String)
    suspend fun setAuxBaseUrl(url: String)
    suspend fun setAuxApiKey(key: String)
    suspend fun setCloudProviderProfiles(profiles: List<CloudProviderProfile>)
    suspend fun setLocalModelUri(uri: String)
    suspend fun setSelectedModelId(id: String)
    suspend fun setModelDownloadDir(dir: String)

    suspend fun isOnboardingCompleted(): Boolean
    suspend fun setOnboardingCompleted(completed: Boolean)

    // Backup
    suspend fun setBackupPassphrase(passphrase: String)

    /**
     * Deletes the credentials left behind by the retired Gist backup.
     *
     * Removing the feature stops Hermes reading the token, but it does not
     * remove it: the GitHub PAT, the gist id, and the last-backup timestamp
     * stay in DataStore until something deletes them. A token we no longer use
     * has no business sitting on the device, so this runs once at startup.
     */
    suspend fun purgeRetiredGistCredentials()

    suspend fun setTermuxHermesInstalled(installed: Boolean)

    suspend fun setShowToolCalls(enabled: Boolean)
    suspend fun setAutoApprovePhoneActions(enabled: Boolean)
    suspend fun setTrustedBackgroundPhoneActions(enabled: Boolean)

    // Local API server
    suspend fun setApiServerEnabled(enabled: Boolean)
    suspend fun setApiServerPort(port: Int)
    suspend fun setApiServerKey(key: String)
    suspend fun setApiServerAllowLan(allow: Boolean)

    // Remote shell (SSH)
    suspend fun setSshHost(host: String)
    suspend fun setSshPort(port: Int)
    suspend fun setSshUser(user: String)
    suspend fun setSshPassword(password: String)

    // Telegram Bot Gateway
    suspend fun setTelegramBotEnabled(enabled: Boolean)
    suspend fun setTelegramBotToken(token: String)
    suspend fun setTelegramAllowedUserIds(userIds: String)

    // Module repository
    suspend fun setModuleCatalogUrl(url: String)

    // Privileged shell (Shizuku)
    suspend fun setPrivilegedShellEnabled(enabled: Boolean)
}
