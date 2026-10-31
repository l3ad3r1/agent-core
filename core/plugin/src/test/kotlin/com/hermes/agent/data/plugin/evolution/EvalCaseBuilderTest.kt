package com.hermes.agent.data.plugin.evolution

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalCaseBuilderTest {

    /** Refuses anything with "sk-" (a credential) and redacts emails, like the app's sanitizer. */
    private val sanitizer = EvidenceSanitizer { text ->
        if ("sk-" in text) null else text.replace(Regex("\\S+@\\S+"), "[email]")
    }
    private val builder = EvalCaseBuilder(sanitizer, app = "Jeeves")

    private fun msg(role: String, content: String, at: Long, convo: String = "c1") =
        ChatRecord(role = role, content = content, conversationId = convo, timestamp = at)

    private fun snapshot(messages: List<ChatRecord> = emptyList(), calls: List<ToolCallRecord> = emptyList()) =
        UsageSnapshot(toolCalls = calls, messages = messages, skillRefinements = emptyMap(), builtInTools = emptySet())

    @Test
    fun `a failed tool call becomes a case built on the request it served`() {
        val cases = builder.build(snapshot(
            messages = listOf(msg("user", "What's the weather in Thiruvalla today?", 1_000)),
            calls = listOf(ToolCallRecord("web_fetch", success = false, detail = "HTTP 403 Forbidden\nstack…", timestamp = 2_000)),
        ))

        val case = cases.single()
        assertEquals(EvalCase.CATEGORY_TOOL_FAILURE, case.category)
        assertEquals("web_fetch", case.tool)
        assertEquals("What's the weather in Thiruvalla today?", case.taskInput)
        assertTrue(case.expectedBehavior, case.expectedBehavior.contains("http # forbidden"))
        assertFalse("only the first error line", case.expectedBehavior.contains("stack"))
    }

    @Test
    fun `a user decline or a guard doing its job is not a case`() {
        val ask = msg("user", "Uninstall the canvas-design skill I got from the Hub", 1_000)
        val stopped = listOf(
            "user declined to run 'shell'",
            "Security violation: path '/sdcard/x' escapes the workspace",
            "failed to fetch http://[ip]/ 10.0.0.2 is a private or local network address",
            "CLEARTEXT communication to 10.0.0.2 not permitted by network security policy",
        )
        val cases = builder.build(snapshot(
            messages = listOf(ask),
            calls = stopped.map { ToolCallRecord("shell", false, it, 2_000) } +
                ToolCallRecord("web_fetch", false, "HTTP 403 Forbidden", 2_000),
        ))
        assertEquals("only the genuine failure", listOf("web_fetch"), cases.map { it.tool })
    }

    @Test
    fun `a tool failure with no recent request is skipped`() {
        val cases = builder.build(snapshot(
            messages = listOf(msg("user", "What's the weather in Thiruvalla today?", 0)),
            calls = listOf(ToolCallRecord("web_fetch", false, "HTTP 403", EvalCaseBuilder.ASK_WINDOW_MS + 1)),
        ))
        assertTrue(cases.isEmpty())
    }

    @Test
    fun `a refusal becomes a capability-gap case`() {
        val cases = builder.build(snapshot(messages = listOf(
            msg("user", "Book a table for two at Paragon tonight", 1),
            msg("assistant", "I can't make restaurant bookings.", 2),
        )))
        val case = cases.single()
        assertEquals(EvalCase.CATEGORY_CAPABILITY_GAP, case.category)
        assertEquals("Book a table for two at Paragon tonight", case.taskInput)
        assertTrue(case.expectedBehavior.contains("I can't make restaurant bookings."))
    }

    @Test
    fun `a user correcting the answer becomes a correction case on the original request`() {
        val cases = builder.build(snapshot(messages = listOf(
            msg("user", "Set an alarm for 6 tomorrow morning", 1),
            msg("assistant", "Alarm set for 6 PM.", 2),
            msg("user", "No, I said 6 in the morning", 3),
        )))
        val case = cases.single()
        assertEquals(EvalCase.CATEGORY_USER_CORRECTION, case.category)
        assertEquals("Set an alarm for 6 tomorrow morning", case.taskInput)
        assertTrue(case.expectedBehavior.contains("No, I said 6 in the morning"))
    }

    @Test
    fun `a normal follow-up is not a correction`() {
        val cases = builder.build(snapshot(messages = listOf(
            msg("user", "Set an alarm for 6 tomorrow morning", 1),
            msg("assistant", "Alarm set for 6 AM.", 2),
            msg("user", "Nothing else, thanks", 3),
        )))
        assertTrue(cases.isEmpty())
    }

    @Test
    fun `a request the sanitizer refuses drops the whole case`() {
        val cases = builder.build(snapshot(messages = listOf(
            msg("user", "Use my key sk-abc123 to call the API", 1),
            msg("assistant", "I can't do that.", 2),
        )))
        assertTrue(cases.isEmpty())
    }

    @Test
    fun `personal data is redacted in the request and the quoted evidence`() {
        val cases = builder.build(snapshot(messages = listOf(
            msg("user", "Email the report to ren@example.com please", 1),
            msg("assistant", "I'm unable to email ren@example.com from here.", 2),
        )))
        val case = cases.single()
        assertFalse(case.taskInput.contains("@example.com"))
        assertFalse(case.expectedBehavior.contains("@example.com"))
        assertTrue(case.taskInput.contains("[email]"))
    }

    @Test
    fun `refused evidence is left out of the rubric but the case is kept`() {
        val cases = builder.build(snapshot(messages = listOf(
            msg("user", "Book a table for two at Paragon tonight", 1),
            msg("assistant", "I can't, the key sk-xyz is not set.", 2),
        )))
        val case = cases.single()
        assertFalse(case.expectedBehavior.contains("sk-"))
        assertFalse(case.expectedBehavior.contains("Last time it replied"))
    }

    @Test
    fun `the same moment seen twice is one case`() {
        val turn = listOf(
            msg("user", "Book a table for two at Paragon tonight", 1),
            msg("assistant", "I can't make restaurant bookings.", 2),
        )
        val cases = builder.build(snapshot(messages = turn + turn.map { it.copy(timestamp = it.timestamp + 10) }))
        assertEquals(1, cases.size)
    }

    @Test
    fun `serialized cases carry the field names the upstream loader reads`() {
        val case = builder.build(snapshot(messages = listOf(
            msg("user", "Book a table for two at Paragon tonight", 1),
            msg("assistant", "I can't make restaurant bookings.", 2),
        ))).single()

        val keys = Json.parseToJsonElement(Json.encodeToString(EvalCase.serializer(), case)).jsonObject.keys

        assertTrue(keys.toString(), keys.containsAll(listOf("task_input", "expected_behavior", "difficulty", "category", "source")))
        assertTrue(keys.containsAll(listOf("id", "app", "created_at")))
    }
}
