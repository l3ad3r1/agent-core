package com.hermes.agent.data.tools

import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StandingOrdersToolTest {

    private class FakeSettingsRepository(
        var settings: UserSettings = UserSettings(),
    ) : SettingsRepository {
        override fun observe(): Flow<UserSettings> = flowOf(settings)
        override suspend fun current(): UserSettings = settings
        override suspend fun setCloudEnabled(enabled: Boolean) {}
        override suspend fun setCloudApiKey(key: String) {}
        override suspend fun setCloudBaseUrl(url: String) {}
        override suspend fun setCloudModel(model: String) {}
        override suspend fun setAppTheme(themeName: String) {}
        override suspend fun setReasoningEffort(effort: String) {}
        override suspend fun setAuxModel(model: String) {}
        override suspend fun setAuxBaseUrl(url: String) {}
        override suspend fun setAuxApiKey(key: String) {}
        override suspend fun setCloudProviderProfiles(profiles: List<CloudProviderProfile>) {}
        override suspend fun setLocalModelUri(uri: String) {}
        override suspend fun setSelectedModelId(id: String) {}
        override suspend fun setModelDownloadDir(dir: String) {}
        override suspend fun isOnboardingCompleted(): Boolean = true
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override suspend fun setBackupPassphrase(passphrase: String) {}
        override suspend fun purgeRetiredGistCredentials() {}
        override suspend fun setTermuxHermesInstalled(installed: Boolean) {}
        override suspend fun setShowToolCalls(enabled: Boolean) {}
        override suspend fun setAutoApprovePhoneActions(enabled: Boolean) {}
        override suspend fun setAutoApproveHomeAssistantControl(enabled: Boolean) {}
        override suspend fun setTrustedBackgroundPhoneActions(enabled: Boolean) {}
        override suspend fun setApiServerEnabled(enabled: Boolean) {}
        override suspend fun setApiServerPort(port: Int) {}
        override suspend fun setApiServerKey(key: String) {}
        override suspend fun setApiServerAllowLan(allow: Boolean) {}
        override suspend fun setSshHost(host: String) {}
        override suspend fun setSshPort(port: Int) {}
        override suspend fun setSshUser(user: String) {}
        override suspend fun setSshPassword(password: String) {}
        override suspend fun setTelegramBotEnabled(enabled: Boolean) {}
        override suspend fun setTelegramBotToken(token: String) {}
        override suspend fun setTelegramAllowedUserIds(userIds: String) {}
        override suspend fun setModuleCatalogUrl(url: String) {}
        override suspend fun setPrivilegedShellEnabled(enabled: Boolean) {}
        override suspend fun setLocalLlmEnabled(enabled: Boolean) {}
        override suspend fun setHomeAssistantUrl(url: String) {}
        override suspend fun setHomeAssistantToken(token: String) {}
        override suspend fun setFilesRootUri(uri: String) {}
        override suspend fun setHeartbeatEnabled(enabled: Boolean) { settings = settings.copy(heartbeatEnabled = enabled) }
        override suspend fun setHeartbeatIntervalMinutes(minutes: Int) { settings = settings.copy(heartbeatIntervalMinutes = minutes) }
        override suspend fun setStandingOrdersJson(json: String) { settings = settings.copy(standingOrdersJson = json) }
        override suspend fun setStandingInstructions(text: String) { settings = settings.copy(standingInstructions = text) }
        override suspend fun setPresenceEnabled(enabled: Boolean) { settings = settings.copy(presenceEnabled = enabled) }
        override suspend fun setPresencePlacesJson(json: String) { settings = settings.copy(presencePlacesJson = json) }
        override suspend fun setNotificationsAgentReadEnabled(enabled: Boolean) { settings = settings.copy(notificationsAgentReadEnabled = enabled) }
    }

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var tool: StandingOrdersTool

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        tool = StandingOrdersTool(settingsRepository)
    }

    @Test
    fun `descriptor properties match specification`() {
        assertEquals("standing_orders", tool.descriptor.name)
        assertEquals("automation", tool.descriptor.category)
        assertTrue(tool.descriptor.capabilities.contains("standing_orders"))
        assertTrue(tool.descriptor.requiresConfirmation)
        assertTrue(tool.descriptor.parameters.any { it.name == "action" && it.required })
    }

    @Test
    fun `list on empty returns empty message`() = runTest {
        val result = tool.execute(mapOf("action" to JsonPrimitive("list")))
        assertTrue(result.success)
        assertTrue(result.output.contains("No standing orders configured"))
    }

    @Test
    fun `create standing order succeeds and persists`() = runTest {
        val result = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Daily Battery Health"),
                "instruction" to JsonPrimitive("Check battery level and report if under 20%"),
                "interval_minutes" to JsonPrimitive(60),
            )
        )

        assertTrue(result.success)
        assertTrue(result.output.contains("Standing order created successfully"))
        assertTrue(settingsRepository.settings.standingOrdersJson.contains("Daily Battery Health"))
    }
}
