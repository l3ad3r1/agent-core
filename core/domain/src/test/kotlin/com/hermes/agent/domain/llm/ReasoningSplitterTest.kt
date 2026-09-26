package com.hermes.agent.domain.llm

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ReasoningSplitterTest {

    @Test
    fun `plain text has no reasoning and is left alone`() {
        val s = ReasoningSplitter.split("Just an answer.")
        assertEquals("Just an answer.", s.answer)
        assertFalse(s.hasReasoning)
    }

    @Test
    fun `inline think block is lifted out of the answer`() {
        val s = ReasoningSplitter.split("<think>Let me work this out.\nStep two.</think>\n\nThe answer is 4.")
        assertEquals("The answer is 4.", s.answer)
        assertEquals("Let me work this out.\nStep two.", s.reasoning)
    }

    @Test
    fun `tag names are matched without regard to case and both spellings work`() {
        val s = ReasoningSplitter.split("<THINK>a</THINK>x<thinking>b</thinking>y")
        assertEquals("xy", s.answer)
        assertEquals("a\n\nb", s.reasoning)
    }

    @Test
    fun `a structured reasoning field comes first and joins inline thoughts`() {
        val s = ReasoningSplitter.split("<think>inline</think>Done.", structuredReasoning = "  from the field ")
        assertEquals("Done.", s.answer)
        assertEquals("from the field\n\ninline", s.reasoning)
    }

    @Test
    fun `a reply cut off mid thought has no answer yet`() {
        val s = ReasoningSplitter.split("Sure.<think>Hmm, first I should")
        assertEquals("Sure.", s.answer)
        assertEquals("Hmm, first I should", s.reasoning)
        assertTrue(ReasoningSplitter.split("<think>only thinking").answer.isEmpty())
    }

    @Test
    fun `a template that only writes the closing tag`() {
        val s = ReasoningSplitter.split("I considered the options.</think>Go with B.")
        assertEquals("Go with B.", s.answer)
        assertEquals("I considered the options.", s.reasoning)
    }

    @Test
    fun `an empty think block leaves no reasoning`() {
        val s = ReasoningSplitter.split("<think>\n</think>Hello")
        assertEquals("Hello", s.answer)
        assertFalse(s.hasReasoning)
    }
}
