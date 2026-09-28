package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import com.hermes.agent.domain.llm.LlmStreamChunk
import com.hermes.agent.domain.llm.LlmToolResponse
import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The provider background services get when they ask for a plain [LlmProvider]:
 * whichever cloud provider the router would pick right now.
 *
 * Bound in place of the bare [CloudLlmProvider], which only reads the legacy
 * primary-cloud settings. With the model configured as a provider profile (the
 * PC relay, for one) that provider reports itself unavailable, and the user
 * model, conversation learning and session compression all skipped silently
 * while chat worked. Cloud only, like the other self-modifying paths: these
 * write durable state.
 */
@Singleton
class RoutedCloudLlmProvider @Inject constructor(
    private val router: LlmRouter,
) : LlmProvider {

    override val name: String = "routed-cloud"
    override val isOnDevice: Boolean = false
    override val model: String = "routed"

    private suspend fun ready(messages: List<LlmMessage>): LlmProvider =
        when (val decision = router.route(messages, RoutingContext(cloudOnly = true))) {
            is RoutingDecision.Ready -> decision.provider
            is RoutingDecision.Unavailable -> error("No cloud model available: ${decision.reason}")
        }

    override suspend fun complete(messages: List<LlmMessage>): LlmResponse =
        ready(messages).complete(messages)

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flow {
        emitAll(ready(messages).stream(messages))
    }

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse = ready(messages).completeWithTools(messages, tools)

    override fun streamWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): Flow<LlmStreamChunk> = flow {
        emitAll(ready(messages).streamWithTools(messages, tools))
    }

    override suspend fun isAvailable(): Boolean =
        router.route(emptyList(), RoutingContext(cloudOnly = true)) is RoutingDecision.Ready
}
