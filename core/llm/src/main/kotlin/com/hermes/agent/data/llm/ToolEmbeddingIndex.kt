package com.hermes.agent.data.llm

import com.hermes.agent.data.memory.EmbeddingService
import com.hermes.agent.domain.tool.ToolDescriptor
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import com.hermes.agent.util.runCatchingCancellable

/**
 * Ranks tools by meaning, so "turn on the torch" finds the flashlight tool even though
 * no word of the request appears in its description. [scoreToolsForTurn] only matches
 * shared words, which sent paraphrased requests to the cloud.
 *
 * Tool vectors are computed once per process in the background (about 60 tools, a few
 * seconds). Until they exist, or when EmbeddingGemma is not the active embedder, [rank]
 * returns null and the caller carries on with word matching alone — this never delays a turn.
 */
@Singleton
class ToolEmbeddingIndex @Inject constructor(
    private val embeddings: EmbeddingService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vectors = ConcurrentHashMap<String, FloatArray>()
    private val warmLock = Any()
    private var warming = false

    /** [tools] with their similarity to [turn], best first; null when not usable yet. */
    suspend fun rank(turn: String, tools: List<ToolDescriptor>): List<Pair<ToolDescriptor, Double>>? {
        if (!embeddings.isGemmaBacked || turn.isBlank() || tools.isEmpty()) return null
        if (tools.any { !vectors.containsKey(key(it)) }) {
            warmUp(tools)
            return null
        }
        val query = runCatchingCancellable { embeddings.embedQuery(turn) }.getOrNull()
            ?.takeIf { v -> v.any { it != 0f } } ?: return null
        return tools
            .map { it to dot(query, vectors.getValue(key(it))) }
            .sortedByDescending { it.second }
    }

    /** Embeds any of [tools] not yet indexed, off the calling thread. */
    fun warmUp(tools: List<ToolDescriptor>) {
        if (!embeddings.isGemmaBacked) return
        synchronized(warmLock) {
            if (warming) return
            warming = true
        }
        scope.launch {
            try {
                val started = System.currentTimeMillis()
                var added = 0
                for (tool in tools) {
                    if (vectors.containsKey(key(tool))) continue
                    val v = runCatching { embeddings.embed(text(tool)) }.getOrNull()
                    // An all-zero vector is the embedder's "failed" marker; indexing it would
                    // pin a useless score for the whole process.
                    if (v != null && v.any { it != 0f }) { vectors[key(tool)] = v; added++ }
                }
                Timber.tag("ToolIndex").i("indexed %d tool(s) in %d ms", added, System.currentTimeMillis() - started)
            } finally {
                synchronized(warmLock) { warming = false }
            }
        }
    }

    private fun key(tool: ToolDescriptor) = tool.name + "\u0000" + tool.description
    private fun text(tool: ToolDescriptor) = tool.name.replace('_', ' ') + ": " + tool.description

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }
}
