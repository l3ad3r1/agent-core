package com.hermes.agent.data.settings
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.DEFAULT_MODULE_CATALOG_URL

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// A torn write (process death mid-save, storage corruption) previously left this
// file unparseable forever: DataStore has no default recovery, so every future
// launch rethrew the same CorruptionException before the app could even reach
// a UI that might let the user clear it. One real device hit exactly this and
// was hard-bricked on cold launch. Falling back to empty preferences trades the
// corrupted settings for a working app; providers/credentials/HA config would
// need re-entry, but that is already true of unreadable data.
private val Context.hermesDataStore by preferencesDataStore(
    name = "hermes_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { ex: CorruptionException ->
        Timber.e(ex, "hermes_settings DataStore was corrupt; resetting to defaults")
        emptyPreferences()
    },
)

@Singleton
class SettingsRepositoryImpl(
    private val context: Context,
    private val secretCipher: SecretCipher,
) : SettingsRepository {

    /**
     * The constructor Hilt uses. Credentials are encrypted with a hardware-bound
     * keystore key; the two-argument form exists so unit tests can swap in
     * [PlaintextSecretCipher], since there is no Android Keystore on the JVM.
     */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, KeystoreSecretCipher())

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
        val LOCAL_LLM_ENABLED = booleanPreferencesKey("local_llm_enabled")
        val HOME_ASSISTANT_URL = stringPreferencesKey("home_assistant_url")
        val HOME_ASSISTANT_TOKEN = stringPreferencesKey("home_assistant_token")
        val FILES_ROOT_URI = stringPreferencesKey("files_root_uri")
    }

    /**
     * Preference keys whose values are credentials. Everything else stays in
     * clear text so the store remains inspectable when something goes wrong.
     */
    private val secretKeys
        get() = listOf(
            Keys.CLOUD_API_KEY,
            Keys.AUX_API_KEY,
            Keys.CLOUD_PROVIDER_PROFILES,
            Keys.API_SERVER_KEY,
            Keys.SSH_PASSWORD,
            Keys.TELEGRAM_BOT_TOKEN,
            Keys.BACKUP_PASSPHRASE,
            Keys.HOME_ASSISTANT_TOKEN,
        )

    private suspend fun putSecret(key: Preferences.Key<String>, value: String) {
        context.hermesDataStore.edit { it[key] = secretCipher.encrypt(value) }
    }

    /** Reads a credential, transparently accepting values written before encryption. */
    private fun Preferences.secret(key: Preferences.Key<String>): String? =
        this[key]?.let(secretCipher::decrypt)

    private val secretsMigrated = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Re-writes any credential still held in clear text, once per process.
     *
     * Reads already tolerate plaintext, so this is not needed for correctness —
     * it is what stops a key written before this change from sitting unencrypted
     * forever, without making the user re-enter anything.
     */
    private suspend fun migrateSecretsAtRest() {
        if (!secretsMigrated.compareAndSet(false, true)) return
        try {
            val stored = context.hermesDataStore.data.first()
            val stale = secretKeys.filter { key ->
                val value = stored[key]
                !value.isNullOrEmpty() && !secretCipher.isEncrypted(value)
            }
            if (stale.isEmpty()) return
            context.hermesDataStore.edit { prefs ->
                stale.forEach { key -> prefs[key]?.let { prefs[key] = secretCipher.encrypt(it) } }
            }
            Timber.tag("Settings").i("Encrypted %d credential(s) previously stored in clear text", stale.size)
        } catch (t: Throwable) {
            // Never let this break settings; reads work either way.
            Timber.tag("Settings").e(t, "Could not migrate credentials at rest")
        }
    }

    override fun observe(): Flow<UserSettings> = context.hermesDataStore.data
        .map { prefs -> prefs.toUserSettings() }
        // Every surface in both apps observes settings at startup, which makes
        // this the one place guaranteed to run once per process without either
        // app having to remember to call a migration hook.
        .onStart { migrateSecretsAtRest() }

    override suspend fun current(): UserSettings = observe().first()

    override suspend fun setCloudEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.CLOUD_ENABLED] = enabled }
    }

    override suspend fun setCloudApiKey(key: String) {
        putSecret(Keys.CLOUD_API_KEY, key)
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
        putSecret(Keys.AUX_API_KEY, key)
    }

    override suspend fun setCloudProviderProfiles(profiles: List<CloudProviderProfile>) {
        val encoded = profilesJson.encodeToString(ListSerializer(CloudProviderProfile.serializer()), profiles)
        putSecret(Keys.CLOUD_PROVIDER_PROFILES, encoded)
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
        putSecret(Keys.BACKUP_PASSPHRASE, passphrase)
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
        putSecret(Keys.API_SERVER_KEY, key)
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
        putSecret(Keys.SSH_PASSWORD, password)
    }

    override suspend fun setTelegramBotEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.TELEGRAM_BOT_ENABLED] = enabled }
    }

    override suspend fun setTelegramBotToken(token: String) {
        putSecret(Keys.TELEGRAM_BOT_TOKEN, token.trim())
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

    override suspend fun setLocalLlmEnabled(enabled: Boolean) {
        context.hermesDataStore.edit { it[Keys.LOCAL_LLM_ENABLED] = enabled }
    }

    override suspend fun setHomeAssistantUrl(url: String) {
        context.hermesDataStore.edit { it[Keys.HOME_ASSISTANT_URL] = url.trim() }
    }

    override suspend fun setHomeAssistantToken(token: String) {
        putSecret(Keys.HOME_ASSISTANT_TOKEN, token.trim())
    }

    override suspend fun setFilesRootUri(uri: String) {
        context.hermesDataStore.edit { it[Keys.FILES_ROOT_URI] = uri.trim() }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        return UserSettings(
            cloudEnabled = this[Keys.CLOUD_ENABLED] ?: false,
            cloudApiKey = this.secret(Keys.CLOUD_API_KEY) ?: BuildConfig.CLOUD_API_KEY,
            cloudBaseUrl = this[Keys.CLOUD_BASE_URL] ?: BuildConfig.CLOUD_BASE_URL,
            cloudModel = this[Keys.CLOUD_MODEL] ?: BuildConfig.CLOUD_MODEL,
            appTheme = this[Keys.APP_THEME] ?: "MIDNIGHT",
            reasoningEffort = this[Keys.REASONING_EFFORT] ?: "medium",
            auxModel = this[Keys.AUX_MODEL] ?: "gpt-4o-mini",
            auxBaseUrl = this[Keys.AUX_BASE_URL] ?: "",
            auxApiKey = this.secret(Keys.AUX_API_KEY) ?: "",
            cloudProviderProfiles = decodeProviderProfiles(this.secret(Keys.CLOUD_PROVIDER_PROFILES)),
            localModelUri = this[Keys.LOCAL_MODEL_URI] ?: "",
            selectedModelId = this[Keys.SELECTED_MODEL_ID] ?: "",
            modelDownloadDir = this[Keys.MODEL_DOWNLOAD_DIR] ?: "",
            backupPassphrase = this.secret(Keys.BACKUP_PASSPHRASE) ?: "",
            termuxHermesInstalled = this[Keys.TERMUX_HERMES_INSTALLED] ?: false,
            showToolCalls = this[Keys.SHOW_TOOL_CALLS] ?: true,
            autoApprovePhoneActions = this[Keys.AUTO_APPROVE_PHONE_ACTIONS] ?: false,
            trustedBackgroundPhoneActions = this[Keys.TRUSTED_BACKGROUND_PHONE_ACTIONS] ?: false,
            apiServerEnabled = this[Keys.API_SERVER_ENABLED] ?: false,
            apiServerPort = this[Keys.API_SERVER_PORT] ?: 8642,
            apiServerKey = this.secret(Keys.API_SERVER_KEY) ?: "",
            apiServerAllowLan = this[Keys.API_SERVER_ALLOW_LAN] ?: false,
            sshHost = this[Keys.SSH_HOST] ?: "",
            sshPort = this[Keys.SSH_PORT] ?: 22,
            sshUser = this[Keys.SSH_USER] ?: "",
            sshPassword = this.secret(Keys.SSH_PASSWORD) ?: "",
            telegramBotEnabled = this[Keys.TELEGRAM_BOT_ENABLED] ?: false,
            telegramBotToken = this.secret(Keys.TELEGRAM_BOT_TOKEN) ?: "",
            telegramAllowedUserIds = this[Keys.TELEGRAM_ALLOWED_USER_IDS] ?: "",
            moduleCatalogUrl = this[Keys.MODULE_CATALOG_URL] ?: DEFAULT_MODULE_CATALOG_URL,
            privilegedShellEnabled = this[Keys.PRIVILEGED_SHELL_ENABLED] ?: false,
            localLlmEnabled = this[Keys.LOCAL_LLM_ENABLED] ?: true,
            homeAssistantUrl = this[Keys.HOME_ASSISTANT_URL] ?: "http://homeassistant.local:8123",
            homeAssistantToken = this.secret(Keys.HOME_ASSISTANT_TOKEN) ?: "",
            filesRootUri = this[Keys.FILES_ROOT_URI] ?: "",
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
