package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.tool.ToolDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import timber.log.Timber

/** Ordered model-router result with automatic failover before output begins. */
internal class RoutedProviderChain(
    providers: List<LlmProvider>,
) : LlmProvider {
    private val providers = providers.distinctBy { "${it.name}|${it.model}" }
    private val activeIndex = AtomicInteger(0)

    init {
        require(this.providers.isNotEmpty()) { "Provider chain cannot be empty." }
    }

    private val active: LlmProvider get() = providers[activeIndex.get().coerceIn(providers.indices)]
    override val name: String get() = active.name
    override val isOnDevice: Boolean get() = active.isOnDevice
    override val model: String get() = active.model

    override suspend fun complete(messages: List<LlmMessage>): LlmResponse =
        execute("completion") { it.complete(messages) }

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse = execute("tool completion") { provider ->
        provider.completeWithTools(messages, tools).also {
            // A completion with no text and no tool calls is a dead turn —
            // portal-proxied refusals and flaky decodes look like this. Treat
            // it as a failure so the chain tries a provider that actually
            // answers, instead of surfacing a blank reply. (empty_response_guard)
            if (it.content.isBlank() && it.toolCalls.isEmpty()) {
                throw IOException("${provider.name} returned an empty response")
            }
        }
    }

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> =
        streamExecute { it.stream(messages) }

    override fun streamWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): Flow<LlmStreamChunk> = streamExecute { it.streamWithTools(messages, tools) }

    override suspend fun isAvailable(): Boolean = providers.any { provider ->
        runCatching { provider.isAvailable() }.getOrDefault(false)
    }

    private suspend fun <T> execute(
        operation: String,
        call: suspend (LlmProvider) -> T,
    ): T {
        var lastFailure: Throwable? = null
        var index = activeIndex.get()
        var retriedSame = false
        while (index < providers.size) {
            val provider = providers[index]
            // Reasoning models think for minutes before the first token; the
            // default 30 s cap would fail them over to a weaker model mid-think.
            val attemptTimeout = ReasoningStaleTimeout.floorMillis(provider.model)
                ?.coerceAtLeast(PROVIDER_ATTEMPT_TIMEOUT_MS)
                ?: PROVIDER_ATTEMPT_TIMEOUT_MS
            try {
                val result = withTimeout(attemptTimeout) { call(provider) }
                activeIndex.set(index)
                return result
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                lastFailure = IOException(
                    "${provider.name} timed out after ${attemptTimeout / 1_000}s", cancelled,
                )
                if (!retriedSame) { retriedSame = true; continue }   // one retry on the same provider
                if (index == providers.lastIndex) throw checkNotNull(lastFailure)
                Timber.tag("LlmRouter").w(lastFailure, "%s timed out on %s; trying %s",
                    operation, provider.name, providers[index + 1].name)
            } catch (failure: Throwable) {
                lastFailure = failure
                when (ApiErrorClassifier.classify(failure)) {
                    FailoverAction.SURFACE -> throw failure
                    FailoverAction.RETRY_SAME -> if (!retriedSame) {
                        retriedSame = true
                        Timber.tag("LlmRouter").w(failure, "%s hit a transient error on %s; retrying once",
                            operation, provider.name)
                        continue
                    }
                    FailoverAction.FALLBACK -> Unit
                }
                if (index == providers.lastIndex) throw failure
                Timber.tag("LlmRouter").w(failure, "%s failed on %s; trying %s",
                    operation, provider.name, providers[index + 1].name)
            }
            retriedSame = false
            index++
        }
        throw checkNotNull(lastFailure)
    }

    private fun streamExecute(
        source: (LlmProvider) -> Flow<LlmStreamChunk>,
    ): Flow<LlmStreamChunk> = flow {
        for (index in activeIndex.get() until providers.size) {
            val provider = providers[index]
            var emittedOutput = false
            var retryFailure: Throwable? = null
            try {
                source(provider).collect { chunk ->
                    when (chunk) {
                        is LlmStreamChunk.Delta, is LlmStreamChunk.ToolCallDelta -> {
                            emittedOutput = true
                            activeIndex.set(index)
                            emit(chunk)
                        }
                        is LlmStreamChunk.Error -> {
                            val isFailover = chunk.cause?.isProviderFailoverFailure() == true ||
                                (chunk.cause == null && chunk.message.isNotBlank())
                            if (!emittedOutput && isFailover) {
                                retryFailure = chunk.cause ?: java.io.IOException(chunk.message)
                            } else {
                                emit(chunk)
                            }
                        }
                        LlmStreamChunk.Done -> if (retryFailure == null) emit(chunk)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!emittedOutput && failure.isProviderFailoverFailure()) retryFailure = failure else throw failure
            }

            val failure = retryFailure
            if (failure == null) return@flow
            if (index == providers.lastIndex) {
                emit(LlmStreamChunk.Error(failure.message ?: "All routed providers failed.", failure))
                return@flow
            }
            Timber.tag("LlmRouter").w(
                failure,
                "Stream failed on %s; trying %s",
                provider.name,
                providers[index + 1].name,
            )
        }
    }

    private fun Throwable.isProviderFailoverFailure(): Boolean {
        var cursor: Throwable? = this
        while (cursor != null) {
            if (cursor is IOException) return true
            if (cursor is HttpException && cursor.code() in FAILOVER_HTTP_CODES) return true
            if (cursor.message.isRecoverableProviderBadRequest()) return true
            cursor = cursor.cause
        }
        return false
    }

    private fun String?.isRecoverableProviderBadRequest(): Boolean {
        val message = this?.lowercase().orEmpty()
        if (!message.contains("http 400")) return false
        return RECOVERABLE_MODEL_ERRORS.any(message::contains)
    }

    private companion object {
        const val PROVIDER_ATTEMPT_TIMEOUT_MS = 30_000L
        // A provider-specific billing failure must not terminate a routed
        // request: another configured cloud provider (or the final local
        // fallback) may still be available.  CloudLlmProvider wraps HTTP
        // failures, but isProviderFailoverFailure walks the cause chain.
        val FAILOVER_HTTP_CODES = setOf(401, 402, 403, 404, 408, 409, 425, 429) + (500..599)
        val RECOVERABLE_MODEL_ERRORS = setOf(
            "model_unavailable",
            "currently unavailable",
            "model not found",
            "does not exist",
            "unsupported model",
            "no endpoints found",
            // Some OpenAI-compatible gateways expose thinking models that
            // require a proprietary field when assistant history is replayed.
            // Another routed provider can still process the same tool loop.
            "reasoning_content",
            "thinking mode must be passed back",
        )
    }
}
