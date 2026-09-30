package com.hermes.agent.data.plugin.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSignalMinerTest {

    private val miner = UsageSignalMiner(TestSanitizer)

    private fun call(tool: String, ok: Boolean, detail: String = "", t: Long = 0) = ToolCallRecord(tool, ok, detail, t)
    private fun msg(role: String, content: String, convo: String = "c1", t: Long) = ChatRecord(role, content, convo, t)
    private fun snapshot(
        calls: List<ToolCallRecord> = emptyList(),
        messages: List<ChatRecord> = emptyList(),
        refinements: Map<String, Int> = emptyMap(),
        builtIns: Set<String> = emptySet(),
    ) = UsageSnapshot(calls, messages, refinements, builtIns)

    @Test
    fun `repeated failures of one tool with one error become one ranked signal`() {
        val calls = List(4) { call("web_search", false, "HTTP 429 from https://api.example.com/q?id=$it", it.toLong()) } +
            call("web_search", true) + call("calculator", false, "division by zero")

        val signals = miner.mine(snapshot(calls))

        val failure = signals.single { it.kind == SignalKind.TOOL_FAILURE }
        assertEquals("web_search", failure.tool)
        assertEquals(4, failure.count)
        assertTrue(failure.summary, failure.summary.contains("4 of 5"))
        // A single failure is noise, not a signal.
        assertTrue(signals.none { it.tool == "calculator" })
    }

    @Test
    fun `signal keys carry no raw user text or error strings`() {
        val calls = List(3) { call("web_fetch", false, "timeout fetching my-private-site.example") }
        val key = miner.mine(snapshot(calls)).single().key
        assertFalse(key, "private" in key)
        assertTrue(key.startsWith("tool_failure:web_fetch:"))
    }

    @Test
    fun `assistant refusals are mined as capability gaps paired with the request`() {
        val messages = listOf(
            msg("user", "Please book a table at Luigi's for 8pm", t = 1),
            msg("assistant", "Sorry, I don't have a tool for restaurant reservations.", t = 2),
            msg("user", "book a table somewhere nice tomorrow", "c2", 3),
            msg("assistant", "I can't make reservations yet.", "c2", 4),
        )
        val gaps = miner.mine(snapshot(messages = messages)).filter { it.kind == SignalKind.CAPABILITY_GAP }
        val gap = gaps.single()
        assertEquals(2, gap.count)
        assertTrue(gap.summary, gap.summary.contains("book table"))
        assertEquals(2, gap.examples.size)
    }

    @Test
    fun `examples are sanitized and secret-looking ones are dropped`() {
        val calls = listOf(
            call("web_fetch", false, "auth failed for SECRET key", 3),
            call("web_fetch", false, "auth failed password hunter2", 2),
            call("web_fetch", false, "auth failed password hunter2", 1),
        )
        val signal = miner.toolFailures(calls).first { it.count >= 2 }
        assertTrue(signal.examples.none { "SECRET" in it || "hunter2" in it })
        assertTrue(signal.examples.any { "[REDACTED]" in it })
    }

    @Test
    fun `frequently repeated request shapes are found across conversations`() {
        val messages = (1..4).map { msg("user", "What's the weather in Paris today?", "c$it", it.toLong()) } +
            msg("user", "tell me a joke", "c9", 9)
        val repeat = miner.mine(snapshot(messages = messages)).single { it.kind == SignalKind.REPEATED_REQUEST }
        assertEquals(4, repeat.count)
        assertEquals("weather paris today", UsageSignalMiner.requestShape("What's the weather in Paris today?"))
    }

    @Test
    fun `skills rewritten repeatedly are reported`() {
        val signals = miner.mine(snapshot(refinements = mapOf("trip-planner" to 4, "once" to 1)))
        val churn = signals.single { it.kind == SignalKind.SKILL_CHURN }
        assertEquals(4, churn.count)
        assertTrue(churn.summary.contains("trip-planner"))
    }

    @Test
    fun `unused built-in tools need enough activity before they count`() {
        val few = List(5) { call("calculator", true) }
        assertNull(miner.unusedTools(snapshot(few, builtIns = setOf("calculator", "web_search"))))

        val many = List(40) { call("calculator", true) }
        val unused = miner.unusedTools(snapshot(many, builtIns = setOf("calculator", "web_search", "alarm")))
        assertNotNull(unused)
        assertEquals(2, unused!!.count)
        assertTrue(unused.summary.contains("alarm") && unused.summary.contains("web_search"))
    }

    @Test
    fun `signals are ranked by score and capped`() {
        val calls = (1..20).flatMap { i -> List(2 + i % 3) { call("tool_$i", false, "err $i") } }
        val signals = miner.mine(snapshot(calls), limit = 5)
        assertEquals(5, signals.size)
        assertEquals(signals.sortedByDescending { it.score }, signals)
    }

    @Test
    fun `empty history yields no signals`() {
        assertTrue(miner.mine(snapshot()).isEmpty())
    }
}
