package com.hermes.agent.data.settings
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.DEFAULT_MODULE_CATALOG_URL

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import timber.log.Timber
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.agent.core.settings.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hermesDataStore by preferencesDataStore(name = "hermes_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_BASE_URL = stringPreferencesKey("cloud_base_url")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed_v1")
        val APP_THEME = stringPreferencesKey("app_theme")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val AUX_MODEL = stringPreferencesKey("aux_model")
        val AUX_BASE_URL = stringPreferencesKey("aux_base_url")
        val AUX_API_KEY = stringPreferencesKey("aux_api_key")
        val CLOUD_PROVIDER_PROFILES = stringPreferencesKey("cloud_provider_profiles")
        val LOCAL_MODEL_URI = stringPreferencesKey("local_model_uri")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val MODEL_DOWNLOAD_DIR = stringPreferencesKey("model_download_dir")
        val BACKUP_PASSPHRASE = stringPreferencesKey("backup_passphrase")
        val TERMUX_HERMES_INSTALLED = booleanPreferencesKey("termux_hermes_installed")
        val SHOW_TOOL_CALLS = booleanPreferencesKey("show_tool_calls")
        val AUTO_APPROVE_PHONE_ACTIONS = booleanPreferencesKey("auto_approve_phone_actions")
        val TRUSTED_BACKGROUND_PHONE_ACTIONS = booleanPreferencesKey("trusted_background_phone_actions")
        val API_SERVER_ENABLED = booleanPreferencesKey("api_server_enabled")
        val API_SERVER_PORT = intPreferencesKey("api_server_port")
        val API_SERVER_KEY = stringPreferencesKey("api_server_key")
        val API_SERVER_ALLOW_LAN = booleanPreferencesKey("api_server_allow_lan")
        val SSH_HOST = stringPreferencesKey("ssh_host")
        val SSH_PORT = intPreferencesKey("ssh_port")
        val SSH_USER = stringPreferencesKey("ssh_user")
        val SSH_PASSWORD = stringPreferencesKey("ssh_password")
        val TELEGRAM_BOT_ENABLED = booleanPreferencesKey("telegram_bot_enabled")
        val TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        val TELEGRAM_ALLOWED_USER_IDS = stringPreferencesKey("telegram_allowed_user_ids")
        val MODULE_CATALOG_URL = stringPreferencesKey("module_catalog_url")
        val PRIVILEGED_SHELL_ENABLED = booleanPreferencesKey("privileged_shell_enabled")
    }

    override fun observe(): Flow<UserSettings> = context.hermesDataStore.data.map { prefs ->
        prefs.toUserSettings()
    }

    override suspend fun current(): UserSettings = observe().first()

    override suspend fun setCloudEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.CLOUD_ENABLED] = enabled }
    }

    override suspend fun setCloudApiKey(key: String) {
        context.hermesDataStore.edit { it[Keys.CLOUD_API_KEY] = key }
    }

    override suspend fun setCloudBaseUrl(url: String) {
        context.hermesDataStore.edit { it[Keys.CLOUD_BASE_URL] = url }
    }

    override suspend fun setCloudModel(model: String) {
        context.hermesDataStore.edit { it[Keys.CLOUD_MODEL] = model }
    }

    override suspend fun setAppTheme(themeName: String) {
        context.hermesDataStore.edit { it[Keys.APP_THEME] = themeName }
    }

    override suspend fun setReasoningEffort(effort: String) {
        val valid = setOf("minimal", "low", "medium", "high", "xhigh")
        if (effort in valid) context.hermesDataStore.edit { it[Keys.REASONING_EFFORT] = effort }
    }

    override suspend fun setAuxModel(model: String) {
        if (model.isNotBlank()) context.hermesDataStore.edit { it[Keys.AUX_MODEL] = model }
    }

    override suspend fun setAuxBaseUrl(url: String) {
        context.hermesDataStore.edit { it[Keys.AUX_BASE_URL] = url }
    }

    override suspend fun setAuxApiKey(key: String) {
        context.hermesDataStore.edit { it[Keys.AUX_API_KEY] = key }
    }

    override suspend fun setCloudProviderProfiles(profiles: List<CloudProviderProfile>) {
        val encoded = profilesJson.encodeToString(ListSerializer(CloudProviderProfile.serializer()), profiles)
        context.hermesDataStore.edit { it[Keys.CLOUD_PROVIDER_PROFILES] = encoded }
    }

    override suspend fun setLocalModelUri(uri: String) {
        context.hermesDataStore.edit { it[Keys.LOCAL_MODEL_URI] = uri }
    }

    override suspend fun setSelectedModelId(id: String) {
        context.hermesDataStore.edit { it[Keys.SELECTED_MODEL_ID] = id }
    }

    override suspend fun setModelDownloadDir(dir: String) {
        context.hermesDataStore.edit { it[Keys.MODEL_DOWNLOAD_DIR] = dir }
    }

    override suspend fun isOnboardingCompleted(): Boolean {
        return context.hermesDataStore.data.first()[Keys.ONBOARDING_COMPLETED] ?: false
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        context.hermesDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun purgeRetiredGistCredentials() {
        // Named literally rather than via Keys: these entries no longer belong
        // to the settings model, and nothing else may read them again.
        val pat = stringPreferencesKey("github_pat")
        val gistId = stringPreferencesKey("gist_id")
        val lastBackup = longPreferencesKey("last_backup_ts")
        context.hermesDataStore.edit { prefs ->
            val hadCredentials = prefs.contains(pat) || prefs.contains(gistId)
            prefs.remove(pat)
            prefs.remove(gistId)
            prefs.remove(lastBackup)
            if (hadCredentials) {
                Timber.tag("Settings").i("removed the retired Gist backup credentials")
            }
        }
    }

    override suspend fun setBackupPassphrase(passphrase: String) {
        context.hermesDataStore.edit { it[Keys.BACKUP_PASSPHRASE] = passphrase }
    }

    override suspend fun setTermuxHermesInstalled(installed: Boolean) {
        context.hermesDataStore.edit { it[Keys.TERMUX_HERMES_INSTALLED] = installed }
    }

    override suspend fun setShowToolCalls(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.SHOW_TOOL_CALLS] = enabled }
    }

    override suspend fun setAutoApprovePhoneActions(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.AUTO_APPROVE_PHONE_ACTIONS] = enabled }
    }

    override suspend fun setTrustedBackgroundPhoneActions(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.TRUSTED_BACKGROUND_PHONE_ACTIONS] = enabled }
    }

    override suspend fun setApiServerEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.API_SERVER_ENABLED] = enabled }
    }

    override suspend fun setApiServerPort(port: Int) {
        if (port in 1024..65535) context.hermesDataStore.edit { it[Keys.API_SERVER_PORT] = port }
    }

    override suspend fun setApiServerKey(key: String) {
        context.hermesDataStore.edit { it[Keys.API_SERVER_KEY] = key }
    }

    override suspend fun setApiServerAllowLan(allow: Boolean) {
        context.hermesDataStore.edit { it[Keys.API_SERVER_ALLOW_LAN] = allow }
    }

    override suspend fun setSshHost(host: String) {
        context.hermesDataStore.edit { it[Keys.SSH_HOST] = host.trim() }
    }

    override suspend fun setSshPort(port: Int) {
        if (port in 1..65535) context.hermesDataStore.edit { it[Keys.SSH_PORT] = port }
    }

    override suspend fun setSshUser(user: String) {
        context.hermesDataStore.edit { it[Keys.SSH_USER] = user.trim() }
    }

    override suspend fun setSshPassword(password: String) {
        context.hermesDataStore.edit { it[Keys.SSH_PASSWORD] = password }
    }

    override suspend fun setTelegramBotEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.TELEGRAM_BOT_ENABLED] = enabled }
    }

    override suspend fun setTelegramBotToken(token: String) {
        context.hermesDataStore.edit { it[Keys.TELEGRAM_BOT_TOKEN] = token.trim() }
    }

    override suspend fun setModuleCatalogUrl(url: String) {
        context.hermesDataStore.edit { it[Keys.MODULE_CATALOG_URL] = url.trim() }
    }

    override suspend fun setTelegramAllowedUserIds(userIds: String) {
        context.hermesDataStore.edit { it[Keys.TELEGRAM_ALLOWED_USER_IDS] = userIds.trim() }
    }

    override suspend fun setPrivilegedShellEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.PRIVILEGED_SHELL_ENABLED] = enabled }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        return UserSettings(
            cloudEnabled = this[Keys.CLOUD_ENABLED] ?: false,
            cloudApiKey = this[Keys.CLOUD_API_KEY] ?: BuildConfig.CLOUD_API_KEY,
            cloudBaseUrl = this[Keys.CLOUD_BASE_URL] ?: BuildConfig.CLOUD_BASE_URL,
            cloudModel = this[Keys.CLOUD_MODEL] ?: BuildConfig.CLOUD_MODEL,
            appTheme = this[Keys.APP_THEME] ?: "MIDNIGHT",
            reasoningEffort = this[Keys.REASONING_EFFORT] ?: "medium",
            auxModel = this[Keys.AUX_MODEL] ?: "gpt-4o-mini",
            auxBaseUrl = this[Keys.AUX_BASE_URL] ?: "",
            auxApiKey = this[Keys.AUX_API_KEY] ?: "",
            cloudProviderProfiles = decodeProviderProfiles(this[Keys.CLOUD_PROVIDER_PROFILES]),
            localModelUri = this[Keys.LOCAL_MODEL_URI] ?: "",
            selectedModelId = this[Keys.SELECTED_MODEL_ID] ?: "",
            modelDownloadDir = this[Keys.MODEL_DOWNLOAD_DIR] ?: "",
            backupPassphrase = this[Keys.BACKUP_PASSPHRASE] ?: "",
            termuxHermesInstalled = this[Keys.TERMUX_HERMES_INSTALLED] ?: false,
            showToolCalls = this[Keys.SHOW_TOOL_CALLS] ?: true,
            autoApprovePhoneActions = this[Keys.AUTO_APPROVE_PHONE_ACTIONS] ?: false,
            trustedBackgroundPhoneActions = this[Keys.TRUSTED_BACKGROUND_PHONE_ACTIONS] ?: false,
            apiServerEnabled = this[Keys.API_SERVER_ENABLED] ?: false,
            apiServerPort = this[Keys.API_SERVER_PORT] ?: 8642,
            apiServerKey = this[Keys.API_SERVER_KEY] ?: "",
            apiServerAllowLan = this[Keys.API_SERVER_ALLOW_LAN] ?: false,
            sshHost = this[Keys.SSH_HOST] ?: "",
            sshPort = this[Keys.SSH_PORT] ?: 22,
            sshUser = this[Keys.SSH_USER] ?: "",
            sshPassword = this[Keys.SSH_PASSWORD] ?: "",
            telegramBotEnabled = this[Keys.TELEGRAM_BOT_ENABLED] ?: false,
            telegramBotToken = this[Keys.TELEGRAM_BOT_TOKEN] ?: "",
            telegramAllowedUserIds = this[Keys.TELEGRAM_ALLOWED_USER_IDS] ?: "",
            moduleCatalogUrl = this[Keys.MODULE_CATALOG_URL] ?: DEFAULT_MODULE_CATALOG_URL,
            privilegedShellEnabled = this[Keys.PRIVILEGED_SHELL_ENABLED] ?: false,
        )
    }

    private fun decodeProviderProfiles(raw: String?): List<CloudProviderProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            profilesJson.decodeFromString(ListSerializer(CloudProviderProfile.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private companion object {
        val profilesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
