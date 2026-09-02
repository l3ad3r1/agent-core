package com.hermes.agent.data.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ApiErrorClassifierTest {

    private fun http(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test fun `deterministic failures surface instead of marching every provider`() {
        assertEquals(FailoverAction.SURFACE,
            ApiErrorClassifier.classify(RuntimeException("HTTP 401: invalid api key")))
        assertEquals(FailoverAction.SURFACE,
            ApiErrorClassifier.classify(IOException("certificate verify failed: self signed certificate")))
        assertEquals(FailoverAction.SURFACE,
            ApiErrorClassifier.classify(RuntimeException("HTTP 400: temperature must be between 0 and 2", http(400))))
    }

    @Test fun `model-quirk 400s fall through to another route`() {
        assertEquals(FailoverAction.FALLBACK,
            ApiErrorClassifier.classify(RuntimeException("HTTP 400: reasoning_content must be passed back", http(400))))
        assertEquals(FailoverAction.FALLBACK,
            ApiErrorClassifier.classify(RuntimeException("HTTP 400: prompt is too long: 210000 tokens", http(400))))
    }

    @Test fun `rate limit and model-not-found fall back to the next provider`() {
        assertEquals(FailoverAction.FALLBACK, ApiErrorClassifier.classify(http(429)))
        assertEquals(FailoverAction.FALLBACK, ApiErrorClassifier.classify(http(402)))
        assertEquals(FailoverAction.FALLBACK, ApiErrorClassifier.classify(http(404)))
        assertEquals(FailoverAction.FALLBACK,
            ApiErrorClassifier.classify(RuntimeException("blocked: violates our usage policies")))
    }

    @Test fun `http 5xx retries the same provider first`() {
        assertEquals(FailoverAction.RETRY_SAME, ApiErrorClassifier.classify(http(503)))
        assertEquals(FailoverAction.RETRY_SAME, ApiErrorClassifier.classify(http(500)))
    }

    @Test fun `transport failure falls back (CloudLlmProvider already retried the socket)`() {
        assertEquals(FailoverAction.FALLBACK, ApiErrorClassifier.classify(IOException("connection reset")))
    }
}
