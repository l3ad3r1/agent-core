package com.hermes.agent.data.memory

/**
 * Stable prefixes for entries that share a physical [VectorStore].
 *
 * The app currently keeps saved-memory and document vectors in one in-memory
 * store. Callers must filter within their own namespace *before* ranking and
 * limiting, otherwise one corpus can consume every top-K result of another.
 */
object VectorNamespaces {
    private const val RAG_PREFIX = "rag:"

    fun ragId(id: String): String = RAG_PREFIX + id

    fun isRagId(id: String): Boolean = id.startsWith(RAG_PREFIX)

    fun rawRagId(id: String): String = id.removePrefix(RAG_PREFIX)
}
