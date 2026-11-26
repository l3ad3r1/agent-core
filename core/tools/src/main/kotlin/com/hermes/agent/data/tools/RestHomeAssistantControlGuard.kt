package com.hermes.agent.data.tools

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.tool.HomeAssistantControlGuard
import com.hermes.agent.domain.tool.HomeAssistantProtectedNames
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HomeAssistantControlGuard] that reads the target's friendly name from Home Assistant, so a
 * switch named "... router hub" is caught even when its entity id is `switch.main_1_local_outlet_3`.
 * Asks whenever the name cannot be read: an unreachable HA is no reason to switch blind.
 */
@Singleton
class RestHomeAssistantControlGuard @Inject constructor(
    private val settingsRepository: SettingsRepository,
    okHttpClient: OkHttpClient,
) : HomeAssistantControlGuard {

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun mustAsk(call: ToolCall): Boolean {
        val entityId = HomeAssistantProtectedNames.guardedTarget(call) ?: return false
        if (HomeAssistantProtectedNames.matches(entityId, friendlyName = null)) return true
        val name = friendlyNameOf(entityId) ?: return true
        return HomeAssistantProtectedNames.matches(entityId, name)
    }

    /** The entity's friendly name, "" when it has none, or null when HA could not be asked. */
    private suspend fun friendlyNameOf(entityId: String): String? = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        val baseUrl = settings.homeAssistantUrl.trim().removeSuffix("/")
        val token = settings.homeAssistantToken.trim()
        if (baseUrl.isBlank() || token.isBlank()) return@withContext null
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/states/$entityId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val obj = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                    ?: return@use null
                ((obj["attributes"] as? JsonObject)?.get("friendly_name") as? JsonPrimitive)
                    ?.contentOrNull.orEmpty()
            }
        }.onFailure { Timber.w(it, "Could not read the friendly name of %s", entityId) }.getOrNull()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HomeAssistantControlGuardModule {
    @Binds
    abstract fun bindHomeAssistantControlGuard(guard: RestHomeAssistantControlGuard): HomeAssistantControlGuard
}
