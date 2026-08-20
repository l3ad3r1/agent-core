package com.hermes.agent.data.llm

/** Product-owned labels used by the shared LLM engine. */
data class LlmProductConfig(
    val assistantName: String,
) {
    init {
        require(assistantName.isNotBlank()) { "assistantName must not be blank" }
    }
}
