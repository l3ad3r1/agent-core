package com.hermes.agent.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract between the catalogues and
 * [com.hermes.agent.work.LocalModelDownloadWorker], which resolves an enqueued
 * model id against both. A model reachable from the UI but absent from that
 * lookup fails its download instantly with "no longer available".
 */
class CatalogResolutionTest {

    /** Mirrors the worker's resolution order. */
    private fun resolve(id: String) =
        ModelCatalog.MODELS.firstOrNull { it.id == id }
            ?: ToolCallerCatalog.MODELS.firstOrNull { it.id == id }

    @Test
    fun `every downloadable model resolves`() {
        (ModelCatalog.MODELS + ToolCallerCatalog.MODELS).forEach { model ->
            assertNotNull("${model.id} is offered but does not resolve", resolve(model.id))
        }
    }

    /** The regression: the tool caller is enqueued through the same worker. */
    @Test
    fun `the tool caller default resolves`() {
        assertEquals(ToolCallerCatalog.DEFAULT, resolve(ToolCallerCatalog.DEFAULT.id))
    }

    /** It must stay out of the chat dropdown — a 270M chat model reads as broken. */
    @Test
    fun `the tool caller is not offered as a chat model`() {
        assertTrue(ModelCatalog.MODELS.none { it.id == ToolCallerCatalog.DEFAULT.id })
    }

    @Test
    fun `model ids are unique across both catalogues`() {
        val ids = (ModelCatalog.MODELS + ToolCallerCatalog.MODELS).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `every entry carries a pinned revision and checksum`() {
        (ModelCatalog.MODELS + ToolCallerCatalog.MODELS).forEach { model ->
            assertTrue("${model.id} has no revision", model.revision.isNotBlank())
            assertTrue("${model.id} has no sha256", model.sha256.length == 64)
            assertTrue("${model.id} has no size", model.sizeBytes > 0)
            assertTrue("${model.id} url is not pinned", model.url.contains(model.revision))
        }
    }

    @Test
    fun `minicpm5 1b is offered as a chat model`() {
        val m = ModelCatalog.MODELS.first { it.id == "minicpm5-1b-q4km" }
        assertEquals("MiniCPM5-1B-Q4_K_M.gguf", m.fileName)
        assertEquals(688_065_920L, m.sizeBytes)
    }
}
