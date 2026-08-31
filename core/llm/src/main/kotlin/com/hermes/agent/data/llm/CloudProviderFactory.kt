package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.data.remote.OpenAiApi
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.product.ProductIdentity
import com.hermes.agent.util.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class CloudProviderFactory @Inject constructor(
    private val api: OpenAiApi,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
    private val productIdentity: ProductIdentity,
    private val credentialPool: CredentialPoolManager,
) : ProfileCloudProviderFactory {
    /**
     * The pool must be passed through here, not just to the `@Inject`-constructed
     * singleton. [com.hermes.agent.data.llm.HybridLlmRouter] builds every
     * profile-backed cloud provider through this factory, so a provider created
     * without a pool is the one serving ordinary chat: key rotation and the 429
     * cooldown would never run on the path that actually carries traffic.
     */
    override fun create(profile: CloudProviderProfile): CloudLlmProvider =
        CloudLlmProvider(api, settings, dispatchers, json, profile, productIdentity, credentialPool)
}

interface ProfileCloudProviderFactory {
    fun create(profile: CloudProviderProfile): CloudLlmProvider
}
