package com.hermes.agent.data.llm

import retrofit2.HttpException

/**
 * What the routed provider chain should do about an API failure.
 *
 *  - [RETRY_SAME]  transient (5xx, timeout, connection blip) — one more try on
 *                  the same provider before demoting to the next one.
 *  - [FALLBACK]    this provider/key can't serve the request (rate limit,
 *                  billing, model-not-found, a safety refusal) — try the next.
 *  - [SURFACE]     deterministic and unrecoverable (bad API key, TLS cert
 *                  failure, malformed request) — stop and show the user; no
 *                  other provider will do better.
 */
internal enum class FailoverAction { RETRY_SAME, FALLBACK, SURFACE }

/**
 * Structural port of `agent/error_classifier.py` — the ~8 recovery-relevant
 * verdicts, not the 1500-line provider-pattern encyclopedia. Consulted by
 * [RoutedProviderChain] for every API failure.
 *
 * ponytail: pattern lists are the short, high-signal subset. Grow one only
 * when a real provider error is being mis-routed.
 */
internal object ApiErrorClassifier {

    private val AUTH_PERMANENT = listOf(
        "invalid api key", "invalid_api_key", "incorrect api key",
        "token revoked", "token has been revoked", "account is deactivated",
        "api key not valid",
    )
    private val SSL_CERT = listOf(
        "certificate verify failed", "certificate_verify_failed",
        "unable to get local issuer certificate", "self-signed certificate",
        "self signed certificate", "certificate has expired",
        "unable to verify the first certificate",
    )
    private val SAFETY_REFUSAL = listOf(
        "content_filter", "violates our usage policies", "usage policies",
        "flagged by our safety", "prompt was flagged", "responsibleaipolicyviolation",
        "safety system",
    )
    /** 400s that another routed provider can still serve — a model quirk, not a bad request. */
    private val RECOVERABLE_400 = listOf(
        "model_unavailable", "currently unavailable", "model not found", "does not exist",
        "unsupported model", "no endpoints found", "no endpoints available",
        "reasoning_content", "thinking mode must be passed back",
        "reduce the length", "context", "too many tokens", "maximum context",
        "prompt is too long", "input is too long", "maximum allowed input length",
    )

    fun classify(t: Throwable): FailoverAction {
        val chain = generateSequence<Throwable>(t) { it.cause }.toList()
        val msg = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        val status = chain.filterIsInstance<HttpException>().firstOrNull()?.code()

        if (SSL_CERT.any(msg::contains)) return FailoverAction.SURFACE
        if (AUTH_PERMANENT.any(msg::contains)) return FailoverAction.SURFACE
        // A different model may not trip the same safety filter — worth a fallback.
        if (SAFETY_REFUSAL.any(msg::contains)) return FailoverAction.FALLBACK

        return when (status) {
            // A genuine bad request fails identically everywhere — surface it.
            // A model-quirk 400 (thinking history, model-not-found, oversized
            // context) may still be served by another route — fall over.
            400 -> if (RECOVERABLE_400.any(msg::contains)) FailoverAction.FALLBACK
                   else FailoverAction.SURFACE
            401, 403 -> FailoverAction.FALLBACK   // transient auth / wrong-key-in-pool
            402, 429 -> FailoverAction.FALLBACK   // billing / rate limit
            404 -> FailoverAction.FALLBACK        // model not found on this route
            // CloudLlmProvider already retries transport-level IOExceptions
            // twice before rethrowing, so a transport failure that reaches here
            // means the endpoint is actually down — move on. Only HTTP 5xx /
            // conflict codes (which it does NOT retry) get one more try here.
            408, 409, 425 -> FailoverAction.RETRY_SAME
            in 500..599 -> FailoverAction.RETRY_SAME
            else -> FailoverAction.FALLBACK
        }
    }
}
