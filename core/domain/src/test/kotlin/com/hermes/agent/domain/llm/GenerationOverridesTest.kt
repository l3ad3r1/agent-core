package com.hermes.agent.domain.llm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationOverridesTest {

    @Test
    fun `nothing set gives no element`() {
        assertNull(GenerationOverrides.of(null, null))
    }

    @Test
    fun `values are clamped to what providers accept`() {
        val o = GenerationOverrides.of(temperature = 5.0, maxTokens = 1)!!
        assertEquals(2.0, o.temperature!!, 0.0)
        assertEquals(GenerationOverrides.MIN_MAX_TOKENS, o.maxTokens)
    }

    @Test
    fun `json fields carry only what is set`() {
        assertEquals("\"temperature\":0.3,", GenerationOverrides(temperature = 0.3).jsonFields())
        assertEquals("\"temperature\":1.0,\"max_tokens\":800,", GenerationOverrides(1.0, 800).jsonFields())
        assertEquals("", GenerationOverrides().jsonFields())
    }

    @Test
    fun `a flow collected inside the run sees the overrides even on another dispatcher`() = runTest {
        // Providers read the element from inside flow {} that is moved with flowOn(io).
        val seen = withContext(GenerationOverrides(temperature = 0.2)) {
            flow { emit(currentCoroutineContext()[GenerationOverrides]?.temperature) }
                .flowOn(Dispatchers.Default)
                .first()
        }
        assertEquals(0.2, seen!!, 0.0)
    }
}
