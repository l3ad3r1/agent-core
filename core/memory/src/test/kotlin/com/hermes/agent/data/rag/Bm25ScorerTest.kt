package com.hermes.agent.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25ScorerTest {

    @Test
    fun `adding an id twice keeps one entry, and removing it clears it`() {
        val bm25 = Bm25Scorer()
        // Two cold-start hydrations used to index every chunk twice.
        bm25.addDocument("c1", "tailscale node keys")
        bm25.addDocument("c1", "tailscale node keys")
        assertEquals(1, bm25.search("tailscale").size)

        bm25.removeDocument("c1")
        assertTrue(bm25.search("tailscale").isEmpty())
    }
}
