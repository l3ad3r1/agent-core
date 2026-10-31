package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Notices a reply that has fallen into a loop: the same stretch of text over and over, which small
 * on-device models do when they lose the thread (upstream hermes-agent: "repetition loops are cut").
 *
 * A loop is one unit of 1..[MAX_UNIT] characters, not all whitespace, repeated at least
 * [MIN_REPEATS] times back to back at the end of the reply, covering at least [MIN_SPAN] characters.
 * The span floor keeps ordinary repetition (a table rule, a short list of identical items) safe.
 * Checked every [CHECK_EVERY] new characters, so the cost stays small on a phone.
 */
internal class RepetitionDetector {
    private val tail = StringBuilder()
    private var sinceCheck = 0

    /** Adds [text]; true once the reply ends in a loop. */
    fun feed(text: String): Boolean {
        tail.append(text)
        if (tail.length > WINDOW) tail.delete(0, tail.length - WINDOW)
        sinceCheck += text.length
        if (sinceCheck < CHECK_EVERY) return false
        sinceCheck = 0
        return endsInLoop(tail)
    }

    companion object {
        const val MAX_UNIT = 300
        const val MIN_REPEATS = 4
        const val MIN_SPAN = 400
        const val CHECK_EVERY = 64
        private const val WINDOW = 2_400

        fun endsInLoop(s: CharSequence): Boolean {
            val n = s.length
            for (unit in 1..minOf(MAX_UNIT, n / MIN_REPEATS)) {
                val needed = maxOf(MIN_REPEATS * unit, MIN_SPAN)
                if (needed > n) continue
                val start = n - needed
                var repeats = true
                for (i in start + unit until n) {
                    if (s[i] != s[i - unit]) { repeats = false; break }
                }
                if (!repeats) continue
                if ((n - unit until n).all { s[it].isWhitespace() }) continue
                return true
            }
            return false
        }
    }
}

/** Stops the stream with an error once its text falls into a loop; everything before stays delivered. */
internal fun Flow<LlmStreamChunk>.cutRepetitionLoops(providerName: String): Flow<LlmStreamChunk> {
    val detector = RepetitionDetector()
    return transformWhile { chunk ->
        emit(chunk)
        if (chunk is LlmStreamChunk.Delta && detector.feed(chunk.text)) {
            emit(LlmStreamChunk.Error("$providerName started repeating itself, so the reply was stopped there."))
            false
        } else {
            true
        }
    }
}
