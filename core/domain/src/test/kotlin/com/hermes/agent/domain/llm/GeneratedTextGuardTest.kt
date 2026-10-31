package com.hermes.agent.domain.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedTextGuardTest {

    @Test
    fun `refusals, relay errors, error bodies and blanks are not content`() {
        listOf(
            "", "   ", "\n",
            "I'm sorry, but I can't help with that.",
            "\"I cannot summarise this conversation.\"",
            "- I apologize, I am unable to extract facts.",
            "[relay error] agy returned no answer (ERROR): invalid model selection",
            "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>",
            "{\"error\": {\"message\": \"overloaded\"}}",
            "Error: request failed",
        ).forEach { assertFalse("should be rejected: <$it>", GeneratedTextGuard.isUsable(it)) }
    }

    @Test
    fun `ordinary facts and summaries are kept, even ones that mention an apology`() {
        listOf(
            "User's name is Rinu",
            "- Prefers concise answers",
            "The user asked for a refund; the assistant said sorry and opened a ticket.",
            "Lives in Hyderabad and works on Android apps.",
            "NONE",
        ).forEach { assertTrue("should be kept: <$it>", GeneratedTextGuard.isUsable(it)) }
    }
}
