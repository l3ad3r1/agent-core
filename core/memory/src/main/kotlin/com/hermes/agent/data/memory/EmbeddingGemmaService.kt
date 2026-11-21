package com.hermes.agent.data.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * [EmbeddingService] backed by Google's EmbeddingGemma 2 (text path, q4 ONNX,
 * ~270M params). The graph's `sentence_embedding` output is already mean-pooled and
 * L2-normalised at 768 dims; we keep the leading [DIMENSION] (Matryoshka
 * truncation, near-lossless at 256) and re-normalise.
 *
 * Files live in "AI Models/embeddings/embeddinggemma-2" on shared storage:
 * `model_q4.onnx` + `model_q4.onnx_data` (must sit side by side) and `tokenizer.json`.
 *
 * Until those exist this delegates to [MiniLmEmbeddingService] (itself falling back
 * to hash vectors), so retrieval keeps working before the download. Vectors from
 * different embedders are unrelated, so the choice is fixed at first use and the
 * index is rebuilt on the next start once the model has arrived.
 *
 * Gemma is asymmetric: documents and queries take different prefixes, so use
 * [embedQuery] for search text and [embed] for stored text.
 */
@Singleton
class EmbeddingGemmaService @Inject constructor(
    private val fallback: MiniLmEmbeddingService,
) : EmbeddingService {

    override val dimension: Int
        get() = if (useModel()) DIMENSION else fallback.dimension

    override val isGemmaBacked: Boolean get() = useModel()

    private val mutex = Mutex()

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: GemmaBpeTokenizer? = null
    @Volatile private var modelBacked: Boolean? = null

    private fun dir() = File(Environment.getExternalStorageDirectory(), DIR)

    /** True once the model, its weights and the tokenizer are all on disk. */
    fun isReady(): Boolean = dir().let {
        File(it, MODEL).exists() && File(it, "$MODEL$DATA_SUFFIX").exists() && File(it, TOKENIZER).exists()
    }

    private fun useModel(): Boolean =
        modelBacked ?: synchronized(this) { modelBacked ?: isReady().also { modelBacked = it } }

    override suspend fun embed(text: String): FloatArray = run(DOCUMENT_PREFIX + text, text)

    override suspend fun embedQuery(text: String): FloatArray = run(QUERY_PREFIX + text, text)

    private suspend fun run(prefixed: String, raw: String): FloatArray = withContext(Dispatchers.Default) {
        if (!useModel()) {
            return@withContext if (prefixed.startsWith(QUERY_PREFIX)) fallback.embedQuery(raw) else fallback.embed(raw)
        }
        try {
            ensureLoaded()
            val enc = tokenizer!!.encode(prefixed)
            val t0 = System.nanoTime()
            mutex.withLock { runSession(enc) }.also {
                Timber.tag("GemmaEmbed").d("embedded %d tokens -> dim %d in %d ms", enc.size, it.size, (System.nanoTime() - t0) / 1_000_000)
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A neutral vector scores nothing and leaves retrieval to keywords.
            Timber.tag("GemmaEmbed").w(t, "EmbeddingGemma failed; using a neutral vector")
            FloatArray(DIMENSION)
        }
    }

    private suspend fun ensureLoaded() {
        if (session != null && tokenizer != null) return
        mutex.withLock {
            if (session != null && tokenizer != null) return
            val d = dir()
            val t0 = System.nanoTime()
            tokenizer = File(d, TOKENIZER).inputStream().use { GemmaBpeTokenizer.fromTokenizerJson(it, MAX_TOKENS) }
            session = OrtEnvironment.getEnvironment()
                .createSession(File(d, MODEL).absolutePath, OrtSession.SessionOptions())
            Timber.tag("GemmaEmbed").i("EmbeddingGemma 2 loaded in %d ms", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun runSession(enc: GemmaBpeTokenizer.Encoding): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, enc.size.toLong())
        val tensors = ArrayList<OnnxTensor>()
        try {
            fun long(data: LongArray) = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape).also { tensors.add(it) }
            // The graph also takes image/video/audio features; a text-only call passes none.
            fun noFeatures() = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, FEATURE_DIM)).also { tensors.add(it) }
            val inputs = mapOf(
                "input_ids" to long(enc.ids),
                "attention_mask" to long(enc.attentionMask),
                "image_features" to noFeatures(),
                "video_features" to noFeatures(),
                "audio_features" to noFeatures(),
            )
            val result = session!!.run(inputs, setOf("sentence_embedding"))
            try {
                @Suppress("UNCHECKED_CAST")
                val full = (result[0].value as Array<FloatArray>)[0]
                return truncateAndNormalize(full, DIMENSION)
            } finally {
                result.close()
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    companion object {
        const val DIMENSION = 256
        const val DIR = "AI Models/embeddings/embeddinggemma-2"
        const val MODEL = "model_q4.onnx"
        const val DATA_SUFFIX = "_data"
        const val TOKENIZER = "tokenizer.json"
        const val QUERY_PREFIX = "task: search result | query: "
        const val DOCUMENT_PREFIX = "title: none | text: "
        private const val MAX_TOKENS = 512
        private const val FEATURE_DIM = 512L

        /** Matryoshka truncation: keep the leading [dim] values, then re-normalise. */
        fun truncateAndNormalize(v: FloatArray, dim: Int): FloatArray {
            val n = minOf(dim, v.size)
            var sum = 0.0
            for (i in 0 until n) sum += (v[i] * v[i]).toDouble()
            val inv = sqrt(sum).toFloat().coerceAtLeast(1e-6f)
            return FloatArray(n) { v[it] / inv }
        }
    }
}
