package com.hermes.agent.domain.model

/**
 * Source-compatible aliases for clients that historically imported the LLM
 * protocol from domain.model. New code should use domain.llm directly.
 */
typealias ToolCall = com.hermes.agent.domain.llm.ToolCall
typealias LlmMessage = com.hermes.agent.domain.llm.LlmMessage
typealias LlmResponse = com.hermes.agent.domain.llm.LlmResponse
typealias LlmToolResponse = com.hermes.agent.domain.llm.LlmToolResponse
typealias LlmStreamChunk = com.hermes.agent.domain.llm.LlmStreamChunk
typealias LlmFinishReason = com.hermes.agent.domain.llm.LlmFinishReason
