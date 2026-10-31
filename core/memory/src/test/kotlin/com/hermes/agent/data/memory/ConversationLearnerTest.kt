package com.hermes.agent.data.memory

import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** The learner turns a model reply into stored facts about the user, so a reply that is not facts must store nothing. */
class ConversationLearnerTest {

    private lateinit var provider: LlmProvider
    private lateinit var memory: MemoryRepository
    private lateinit var learner: ConversationLearner
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        provider = mockk(relaxed = true)
        memory = mockk(relaxed = true)
        val dispatchers = mockk<DispatcherProvider>()
        every { dispatchers.io } returns dispatcher
        learner = ConversationLearner(provider, memory, dispatchers)
        coEvery { provider.isAvailable() } returns true
        coEvery { memory.searchMemories(any(), any()) } returns emptyList()
    }

    private fun reply(text: String) {
        coEvery { provider.complete(any()) } returns LlmResponse(text, 10, "m")
    }

    @Test
    fun `stores real facts one per line`() = runTest(dispatcher) {
        reply("- User's name is Rinu\n- User lives in Hyderabad")

        learner.extractAndLearn("hi I'm Rinu from Hyderabad", "Hello Rinu")

        coVerify(exactly = 1) { memory.addMemory("User's name is Rinu") }
        coVerify(exactly = 1) { memory.addMemory("User lives in Hyderabad") }
    }

    @Test
    fun `a refusal is not saved as a fact`() = runTest(dispatcher) {
        reply("I'm sorry, but I can't extract personal information from this conversation.")

        learner.extractAndLearn("hello", "hi")

        coVerify(exactly = 0) { memory.addMemory(any()) }
    }

    @Test
    fun `a relay error is not saved as a fact`() = runTest(dispatcher) {
        reply("[relay error] upstream returned HTTP 503 service unavailable")

        learner.extractAndLearn("hello", "hi")

        coVerify(exactly = 0) { memory.addMemory(any()) }
    }

    @Test
    fun `NONE stores nothing`() = runTest(dispatcher) {
        reply("NONE")

        learner.extractAndLearn("what time is it", "3pm")

        coVerify(exactly = 0) { memory.addMemory(any()) }
    }

    @Test
    fun `a refusal line mixed in with facts is dropped and the facts kept`() = runTest(dispatcher) {
        reply("- User's name is Rinu\n- I cannot determine anything else about the user")

        learner.extractAndLearn("I'm Rinu", "Hi")

        coVerify(exactly = 1) { memory.addMemory("User's name is Rinu") }
        coVerify(exactly = 1) { memory.addMemory(any()) }
    }
}
