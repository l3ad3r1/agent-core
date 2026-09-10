package com.hermes.agent.data.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorNamespacesTest {

    @Test
    fun `namespace filter ranks only requested corpus before applying limit`() = runTest {
        val store = InMemoryVectorStore()
        val vector = floatArrayOf(1f, 0f)
        // Memory entries are deliberately inserted first and would otherwise
        // consume the global top-K result.
        store.upsert(VectorEntry("memory-1", vector, "saved memory"))
        store.upsert(VectorEntry(VectorNamespaces.ragId("chunk-1"), vector, "document"))

        val hits = store.search(vector, limit = 1) { VectorNamespaces.isRagId(it.id) }

        assertEquals(listOf(VectorNamespaces.ragId("chunk-1")), hits.map { it.entry.id })
    }
}
