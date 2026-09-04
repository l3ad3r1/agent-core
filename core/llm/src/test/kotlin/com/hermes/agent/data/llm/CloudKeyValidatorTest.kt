package com.hermes.agent.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyValidatorTest {

    /** The case that motivated this: a Chutes key saved against SambaNova. */
    @Test
    fun `flags a chutes key pasted into sambanova`() {
        assertEquals(
            "chutes",
            CloudKeyValidator.mismatchedProvider("sambanova", "cpk_c8abcdefGwCW"),
        )
    }

    @Test
    fun `warning names both providers`() {
        val warning = CloudKeyValidator.mismatchWarning("sambanova", "SambaNova", "cpk_c8abcdefGwCW")
        assertTrue(warning, warning!!.contains("Chutes.ai"))
        assertTrue(warning, warning.contains("SambaNova"))
    }

    @Test
    fun `stays quiet when the key matches its provider`() {
        assertNull(CloudKeyValidator.mismatchedProvider("chutes", "cpk_c8abcdefGwCW"))
        assertNull(CloudKeyValidator.mismatchedProvider("groq", "gsk_abc123"))
    }

    /** Most providers issue opaque strings — guessing there would fire on good keys. */
    @Test
    fun `stays quiet for keys with no recognised prefix`() {
        assertNull(CloudKeyValidator.mismatchedProvider("sambanova", "0f1e2d3c-4b5a-6978"))
        assertNull(CloudKeyValidator.mismatchedProvider("mistral", "abcdefghijklmnop"))
    }

    /** Plain sk- is shared by OpenAI, DeepSeek and many proxies. */
    @Test
    fun `does not treat a bare sk- key as any particular provider`() {
        assertNull(CloudKeyValidator.mismatchedProvider("deepseek", "sk-abc123def456"))
        assertNull(CloudKeyValidator.mismatchedProvider("openai", "sk-abc123def456"))
    }

    /** sk-or- must win over any shorter sk- match. */
    @Test
    fun `recognises the openrouter prefix ahead of bare sk-`() {
        assertEquals(
            "openrouter",
            CloudKeyValidator.mismatchedProvider("openai", "sk-or-v1-abc123"),
        )
    }

    /** Custom endpoints proxy other vendors by design. */
    @Test
    fun `never flags a custom provider`() {
        assertNull(CloudKeyValidator.mismatchedProvider("custom_1", "cpk_c8abcdefGwCW"))
    }

    @Test
    fun `ignores blank keys and surrounding whitespace`() {
        assertNull(CloudKeyValidator.mismatchedProvider("sambanova", ""))
        assertNull(CloudKeyValidator.mismatchedProvider("sambanova", "   "))
        assertEquals(
            "chutes",
            CloudKeyValidator.mismatchedProvider("sambanova", "  cpk_c8abcdefGwCW  "),
        )
    }
}
