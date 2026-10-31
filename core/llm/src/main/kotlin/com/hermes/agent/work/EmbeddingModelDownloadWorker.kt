package com.hermes.agent.work

import android.content.Context
import android.os.Environment
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches the EmbeddingGemma 2 text model that `EmbeddingGemmaService` reads.
 *
 * Three files, pinned to one HuggingFace commit and verified by size and SHA-256:
 * the ONNX graph, its external weights (which must sit beside it) and the
 * tokenizer. Without them the service silently uses MiniLM/hash vectors, which is
 * the gap this closes.
 *
 * Kept apart from [LocalModelDownloadWorker]: that one is a single-file GGUF flow
 * with one unique-work slot that REPLACEs, so a three-file model can't share it.
 * Each file is written to `.part` beside its destination, resumed with a Range
 * request, hashed, and only then renamed into place — a half-downloaded file is
 * never visible to the loader.
 *
 * Runs on unmetered, non-low-battery, non-low-storage only (~200 MB).
 */
@HiltWorker
class EmbeddingModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dir = destination()
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.tag(TAG).w("cannot create %s; storage access missing?", dir)
            return@withContext Result.failure()
        }
        try {
            for (f in FILES) {
                val target = File(dir, f.name)
                if (target.length() == f.sizeBytes) continue
                val part = File(dir, "${f.name}.part")
                if (part.length() > f.sizeBytes) part.delete()
                if (part.length() < f.sizeBytes) fetch(f, part)
                if (part.length() != f.sizeBytes || !sha256(part).equals(f.sha256, ignoreCase = true)) {
                    part.delete()
                    Timber.tag(TAG).w("%s failed verification; will retry from scratch", f.name)
                    return@withContext Result.retry()
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("cannot move ${f.name} into place")
            }
            Timber.tag(TAG).i("EmbeddingGemma 2 installed in %s", dir)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "embedding model download attempt ${runAttemptCount + 1} failed")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun fetch(f: ModelFile, part: File) {
        val offset = part.length()
        val request = Request.Builder().url(f.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0) { part.delete(); throw IOException("stale partial discarded") }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${f.name}")
            val append = offset > 0 && response.code == 206
            val body = response.body ?: throw IOException("empty body for ${f.name}")
            var written = if (append) offset else 0L
            body.byteStream().use { input ->
                FileOutputStream(part, append).buffered().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > f.sizeBytes) throw IOException("${f.name} is larger than expected")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class ModelFile(val name: String, val path: String, val sizeBytes: Long, val sha256: String) {
        val url get() = "https://huggingface.co/onnx-community/embeddinggemma-2-ONNX/resolve/$REVISION/$path"
    }

    companion object {
        const val UNIQUE_NAME = "hermes.embeddinggemma_download"
        private const val TAG = "EmbeddingDownload"
        private const val BUFFER = 64 * 1024
        private const val MAX_ATTEMPTS = 5
        private const val REVISION = "daa72c51243991dfcaf9f9137d2c573d8f7790c0"
        // Must match EmbeddingGemmaService.DIR, which lives in :core:memory (not a dependency here).
        private const val DIR = "AI Models/embeddings/embeddinggemma-2"

        private val FILES = listOf(
            ModelFile(
                "tokenizer.json", "tokenizer.json", 32_170_510L,
                "4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4",
            ),
            ModelFile(
                "model_q4.onnx_data", "onnx/model_q4.onnx_data", 174_028_800L,
                "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49",
            ),
            ModelFile(
                "model_q4.onnx", "onnx/model_q4.onnx", 490_742L,
                "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9",
            ),
        )

        private fun destination() = File(Environment.getExternalStorageDirectory(), DIR)

        /** True when every file is on disk at its expected size. */
        fun isInstalled(): Boolean = FILES.all { File(destination(), it.name).length() == it.sizeBytes }

        /** Enqueue the download unless the model is already there or a run is in flight. */
        fun schedule(context: Context) {
            if (isInstalled()) return
            val request = OneTimeWorkRequestBuilder<EmbeddingModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
