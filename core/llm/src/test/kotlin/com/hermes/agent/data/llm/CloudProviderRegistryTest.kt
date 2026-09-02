package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderRegistryTest {

    @Test
    fun `openai provider preset is registered with the direct API base url`() {
        val openai = requireNotNull(CloudProviderRegistry.definition("openai"))
        assertEquals("OpenAI", openai.name)
        assertEquals("https://api.openai.com/v1", openai.defaultBaseUrl)
        assertEquals("gpt-4.1-mini", openai.defaultModel)
        assertTrue(openai.supportsVision)
    }

    @Test
    fun `provider ids are unique`() {
        val ids = CloudProviderRegistry.providers.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `available saved best model stays preselected`() {
        val groq = requireNotNull(CloudProviderRegistry.definition("groq"))

        val ordered = CloudProviderRegistry.orderModels(
            definition = groq,
            available = listOf("llama-3.1-8b-instant", "openai/gpt-oss-120b"),
            savedModel = "openai/gpt-oss-120b",
        )

        assertEquals("openai/gpt-oss-120b", ordered.first())
    }

    @Test
    fun `curated default replaces unavailable saved model and non-chat models are hidden`() {
        val groq = requireNotNull(CloudProviderRegistry.definition("groq"))

        val ordered = CloudProviderRegistry.orderModels(
            definition = groq,
            available = listOf("whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-120b"),
            savedModel = "removed-model",
        )

        assertEquals("openai/gpt-oss-120b", ordered.first())
        assertFalse(ordered.contains("whisper-large-v3"))
    }
}
