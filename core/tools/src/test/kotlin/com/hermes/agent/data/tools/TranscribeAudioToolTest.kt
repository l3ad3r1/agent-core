package com.hermes.agent.data.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TranscribeAudioToolTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var tempFile: File

    private class FakeSettingsRepository(
        private var settings: UserSettings,
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
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        tempFile = File.createTempFile("test_audio", ".wav", context.cacheDir)
        tempFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
    }

    @After
    fun tearDown() {
        server.shutdown()
        if (tempFile.exists()) tempFile.delete()
    }

    private fun configuredSettings() = UserSettings(
        cloudEnabled = true,
        cloudApiKey = "test-key",
        cloudBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `descriptor has correct name and capability`() {
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(UserSettings()), Json)
        assertEquals("transcribe_audio", tool.descriptor.name)
        assertTrue("voice" in tool.descriptor.capabilities)
        assertTrue(tool.descriptor.parameters.any { it.name == "audio_path" && it.required })
    }

    @Test
    fun `missing audio_path parameter returns error`() = runTest {
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(configuredSettings()), Json)
        val result = tool.execute(emptyMap())
        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("missing required parameter"))
    }

    @Test
    fun `cloud not configured returns helpful error`() = runTest {
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(UserSettings(cloudEnabled = false)), Json)
        val result = tool.execute(mapOf("audio_path" to JsonPrimitive(tempFile.absolutePath)))
        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("Cloud access isn't configured"))
    }

    @Test
    fun `successful transcription returns text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hello from the test"}"""))
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(configuredSettings()), Json)

        val result = tool.execute(mapOf("audio_path" to JsonPrimitive(tempFile.absolutePath)))

        assertTrue(result.success)
        assertEquals("hello from the test", result.output)

        val recorded = server.takeRequest()
        assertTrue(recorded.path.orEmpty().endsWith("/audio/transcriptions"))
        assertTrue(recorded.getHeader("Authorization") == "Bearer test-key")
        assertTrue(recorded.body.readUtf8().contains("whisper-1"))
    }

    @Test
    fun `nonexistent local file returns error`() = runTest {
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(configuredSettings()), Json)
        val result = tool.execute(mapOf("audio_path" to JsonPrimitive("/no/such/file.wav")))
        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("File not found"))
    }

    @Test
    fun `401 response surfaces authentication error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(configuredSettings()), Json)

        val result = tool.execute(mapOf("audio_path" to JsonPrimitive(tempFile.absolutePath)))

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("Authentication failed"))
    }

    @Test
    fun `404 response hints at trying a different model`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(configuredSettings()), Json)

        val result = tool.execute(
            mapOf(
                "audio_path" to JsonPrimitive(tempFile.absolutePath),
                "model" to JsonPrimitive("whisper-large-v3-turbo"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("model"))
    }

    @Test
    fun `custom model and language are sent to the provider`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"bonjour"}"""))
        val tool = TranscribeAudioTool(context, okHttpClient, FakeSettingsRepository(configuredSettings()), Json)

        val result = tool.execute(
            mapOf(
                "audio_path" to JsonPrimitive(tempFile.absolutePath),
                "model" to JsonPrimitive("whisper-large-v3-turbo"),
                "language" to JsonPrimitive("fr"),
            ),
        )

        assertTrue(result.success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("whisper-large-v3-turbo"))
        assertTrue(body.contains("fr"))
    }
}
