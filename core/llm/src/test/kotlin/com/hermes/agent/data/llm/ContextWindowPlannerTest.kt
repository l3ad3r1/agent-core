package com.hermes.agent.data.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextWindowPlannerTest {

    private fun split(sizes: List<Int>, budget: Int = 6_000, max: Int = 20) =
        ContextWindowPlanner.split(sizes.indices.toList(), { sizes[it] }, budget, max)

    @Test
    fun `short turns keep the familiar twenty-message window`() {
        val s = split(List(30) { 50 })
        assertEquals(20, s.recent.size)
        assertEquals((10 until 30).toList(), s.recent)
        assertEquals((0 until 10).toList(), s.older)
    }

    @Test
    fun `big turns push older ones into the brief`() {
        // Three pasted documents of 2,500 tokens: only two fit beside each other in 6,000.
        val s = split(List(10) { 100 } + listOf(2_500, 2_500, 2_500))
        assertEquals(listOf(11, 12), s.recent)
        assertEquals(11, s.older.size)
    }

    @Test
    fun `the newest turn is kept even when it alone is over budget`() {
        val s = split(listOf(100, 100, 50_000))
        assertEquals(listOf(2), s.recent)
        assertEquals(listOf(0, 1), s.older)
    }

    @Test
    fun `an empty conversation splits into nothing`() {
        val s = split(emptyList())
        assertEquals(emptyList<Int>(), s.recent)
        assertEquals(emptyList<Int>(), s.older)
    }

    @Test
    fun `token estimate prefers the larger of stored count and length`() {
        assertEquals(250, ContextWindowPlanner.estimateTokens(storedTokens = 10, text = "x".repeat(1_000)))
        assertEquals(400, ContextWindowPlanner.estimateTokens(storedTokens = 400, text = "short"))
        assertEquals(1, ContextWindowPlanner.estimateTokens(storedTokens = 0, text = ""))
    }
}
