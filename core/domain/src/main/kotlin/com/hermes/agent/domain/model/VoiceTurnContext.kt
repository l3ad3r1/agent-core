package com.hermes.agent.domain.model

import kotlinx.serialization.Serializable

/**
 * Context carrier for voice turns (Talk mode and wake-word interactions).
 *
 * Threaded into LLM request / prompt assembly when a turn originates from Talk mode
 * or when the user interrupted the previous spoken response (barge-in).
 *
 * OpenClaw docs/nodes/talk.md:
 * "When the user talks while the assistant is speaking, playback stops and the interruption
 * timestamp is noted for the next prompt."
 */
@Serializable
data class VoiceTurnContext(
    val interruptedAt: String? = null,
    val mode: String = "talk",
    val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
    val preferBluetooth: Boolean = true,
) {
    companion object {
        const val DEFAULT_SILENCE_TIMEOUT_MS = 8000L

        /**
         * Emits the system context string block to include in the request.
         * Only includes interruption timestamp when set.
         */
        fun formatContextBlock(context: VoiceTurnContext?): String? {
            if (context == null || context.interruptedAt.isNullOrBlank()) return null
            return "[VOICE CONTEXT: { interrupted_at: \"${context.interruptedAt}\", mode: \"${context.mode}\" }]"
        }
    }
}
