package com.hermes.agent.data.llm

import java.io.IOException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A stream that stopped making progress. An [IOException] so the routed chain treats a stall
 * before any output like any other provider failure and moves on to the next provider.
 */
class StreamStalledException(val idleMillis: Long, val beforeOutput: Boolean) :
    IOException(
        if (beforeOutput) "no response for ${idleMillis / 1_000}s"
        else "the reply stopped for ${idleMillis / 1_000}s",
    )

/**
 * Ends this flow with [StreamStalledException] when it goes quiet: [firstMillis] to wait for the
 * first element, then [betweenMillis] between elements.
 *
 * Without it a stream that stopped sending held the chat until OkHttp's 600 s read timeout
 * (upstream hermes-agent's "stalled turns recover"). Thinking models get a long [firstMillis]
 * from [ReasoningStaleTimeout]; once text is flowing a long gap means the stream is dead.
 */
internal fun <T> Flow<T>.stallGuard(firstMillis: Long, betweenMillis: Long): Flow<T> = channelFlow {
    val upstream = this@stallGuard.produceIn(this)
    var limit = firstMillis
    var first = true
    while (true) {
        val next = withTimeoutOrNull(limit) { upstream.receiveCatching() }
        if (next == null) {
            upstream.cancel()
            throw StreamStalledException(limit, beforeOutput = first)
        }
        if (next.isClosed) {
            next.exceptionOrNull()?.let { if (it !is ClosedReceiveChannelException) throw it }
            return@channelFlow
        }
        send(next.getOrThrow())
        first = false
        limit = betweenMillis
    }
}
