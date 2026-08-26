package com.hermes.agent.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hermes.agent.data.llm.LocalModelInstaller
import com.hermes.agent.data.llm.ModelCatalog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@HiltWorker
class LocalModelDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext failure("The selected model was not supplied. Choose it again.")
        val model = ModelCatalog.MODELS.firstOrNull { it.id == modelId }
            ?: return@withContext failure("The selected model is no longer available. Choose another model.")
        val destinationPath = inputData.getString(KEY_DESTINATION_DIR)
            ?: return@withContext failure("The model folder was not supplied. Choose it again.")

        try {
            showProgress(model.displayName, 0)
            val destinationDir = validatedDestination(destinationPath)
            val stagingDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("External download storage is unavailable.")
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                error("Cannot create the temporary model download folder.")
            }

            val staging = File(stagingDir, ".${model.fileName}.part")
            if (staging.length() > model.sizeBytes && !staging.delete()) {
                error("Cannot reset an invalid partial download. Clear app storage and try again.")
            }
            val remainingBytes = model.sizeBytes - staging.length()
            if (stagingDir.usableSpace < remainingBytes + STAGING_HEADROOM_BYTES) {
                error(
                    "Not enough temporary storage. Free at least " +
                        "${(remainingBytes + STAGING_HEADROOM_BYTES) / MIB} MB and try again.",
                )
            }
            val destinationRequired = if (isOnPrimaryExternalStorage(destinationDir)) {
                remainingBytes + INSTALL_HEADROOM_BYTES
            } else {
                model.sizeBytes + INSTALL_HEADROOM_BYTES
            }
            if (destinationDir.usableSpace < destinationRequired) {
                error(
                    "Not enough free space in ${destinationDir.absolutePath}. Free at least " +
                        "${destinationRequired / MIB} MB and try again.",
                )
            }

            // A full-size staging file means an earlier run downloaded everything
            // but could not promote it. Hash it before trusting it: the promotion
            // step only checks the size, and it writes a sidecar asserting this
            // digest, so an unverified file would be installed as "verified".
            if (staging.length() == model.sizeBytes &&
                model.sha256.isNotBlank() &&
                !matchesSha256(staging, model.sha256)
            ) {
                Timber.tag(TAG).w("staged model failed checksum; downloading again")
                staging.delete()
            }

            if (staging.length() < model.sizeBytes) {
                try {
                    download(
                        url = model.url,
                        displayName = model.displayName,
                        staging = staging,
                        expectedSize = model.sizeBytes,
                        expectedSha256 = model.sha256,
                    )
                } catch (_: RestartDownloadException) {
                    // The partial was unusable and has been deleted; start clean.
                    download(
                        url = model.url,
                        displayName = model.displayName,
                        staging = staging,
                        expectedSize = model.sizeBytes,
                        expectedSha256 = model.sha256,
                    )
                }
            }
            if (staging.length() != model.sizeBytes) {
                error(
                    "The downloaded model is incomplete " +
                        "(${staging.length()} of ${model.sizeBytes} bytes). Try again.",
                )
            }
            if (!LocalModelInstaller.moveIntoPlace(
                    staging = staging,
                    destDir = destinationDir,
                    fileName = model.fileName,
                    expectedSize = model.sizeBytes,
                    expectedSha256 = model.sha256,
                )
            ) {
                error(
                    "Downloaded, but couldn't save into ${destinationDir.absolutePath}. " +
                        "Check the folder and storage access.",
                )
            }
            setProgress(workDataOf(KEY_PROGRESS to 100))
            Result.success(workDataOf(KEY_PROGRESS to 100))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "model download attempt ${runAttemptCount + 1} failed")
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else failure("The model download lost its connection. Check the network and try again.")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "model download failed")
            failure(e.message ?: "The model download failed. Try again.")
        }
    }

    private fun validatedDestination(path: String): File {
        val destination = File(path).canonicalFile
        if (!destination.isAbsolute || destination.parentFile == null) {
            error("Choose a model folder instead of the filesystem root.")
        }
        if ((!destination.exists() && !destination.mkdirs()) || !destination.isDirectory) {
            error("Cannot create the model folder ${destination.absolutePath}.")
        }
        if (!destination.canWrite()) error("The model folder is not writable. Choose another folder.")
        return destination
    }

    private fun isOnPrimaryExternalStorage(destination: File): Boolean {
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        return destination == primary || destination.absolutePath.startsWith(primary.absolutePath + File.separator)
    }

    private suspend fun download(
        url: String,
        displayName: String,
        staging: File,
        expectedSize: Long,
        expectedSha256: String,
    ) {
        val offset = staging.length()
        val request = Request.Builder()
            .url(url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        var digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) {
            // Re-hash existing prefix from disk before streaming continuation
            FileInputStream(staging).buffered().use { fis ->
                val preBuffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (fis.read(preBuffer).also { read = it } != -1) {
                    digest.update(preBuffer, 0, read)
                }
            }
        }

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 416 means the partial on disk is longer than the object the
                // server is willing to serve — a stale part file from an earlier
                // catalog revision. Resuming can never succeed, so drop it and
                // let the caller restart from zero instead of failing forever.
                if (response.code == HTTP_RANGE_NOT_SATISFIABLE && offset > 0) {
                    staging.delete()
                    throw RestartDownloadException()
                }
                if (response.code >= 500) throw IOException("Server returned ${response.code}")
                error("The model server rejected the download (${response.code}). Try another model.")
            }
            val append = offset > 0 && response.code == 206
            if (!append && offset > 0) {
                // The server ignored the Range header and is sending the whole
                // file, which truncates the staging file below. The digest still
                // holds the discarded prefix, so it has to start over too —
                // otherwise every resumed download failed checksum verification.
                digest = MessageDigest.getInstance("SHA-256")
            }
            if (append) {
                val contentRange = response.header("Content-Range").orEmpty()
                if (!contentRange.startsWith("bytes $offset-")) {
                    error("The model server returned an invalid resume range. Try again.")
                }
            }
            val body = response.body ?: error("The model server returned an empty download.")
            val startingBytes = if (append) offset else 0L
            var written = startingBytes
            var lastPercent = ((written * 100) / expectedSize).toInt()
            body.byteStream().use { input ->
                FileOutputStream(staging, append).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > expectedSize) {
                            error("The model server returned more data than expected. Try another model.")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val percent = ((written * 100) / expectedSize).toInt().coerceIn(0, 100)
                        if (percent > lastPercent) {
                            lastPercent = percent
                            setProgress(workDataOf(KEY_PROGRESS to percent))
                            showProgress(displayName, percent)
                        }
                    }
                }
            }
        }

        if (expectedSha256.isNotBlank()) {
            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!computedSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                staging.delete()
                error("Checksum verification failed for $displayName. The downloaded artifact does not match expected SHA-256.")
            }
        }
    }

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))

    /** True when [file] hashes to [expectedSha256]; false on a mismatch or a read error. */
    private fun matchesSha256(file: File, expectedSha256: String): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
            .equals(expectedSha256.trim(), ignoreCase = true)
    } catch (e: IOException) {
        Timber.tag(TAG).w(e, "could not hash the staged model")
        false
    }

    /**
     * Publishes download progress as a foreground notification.
     *
     * A refused foreground service must not abort the download. The OS turns
     * this down for reasons that have nothing to do with the transfer — a
     * background start on Android 12+, or notifications being denied — and
     * WorkManager runs the job as ordinary background work either way.
     */
    private suspend fun showProgress(modelName: String, percent: Int) {
        try {
            setForeground(createForegroundInfo(modelName, percent))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "foreground progress notification refused; download continues")
        }
    }

    private fun createForegroundInfo(modelName: String, percent: Int): ForegroundInfo {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent()
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $modelName")
            .setContentText("$percent% complete")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** The partial on disk cannot be resumed; the caller restarts from zero. */
    private class RestartDownloadException : IOException("Partial download discarded")

    companion object {
        const val UNIQUE_NAME = "hermes.local_model_download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DESTINATION_DIR = "destination_dir"
        const val KEY_PROGRESS = "progress_percent"
        const val KEY_ERROR = "error"

        private const val TAG = "ModelDownload"
        private const val CHANNEL_ID = "hermes_model_downloads"
        private const val NOTIFICATION_ID = 9201
        private const val MAX_RETRIES = 2
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 64 * 1024
        private const val MIB = 1024L * 1024L
        private const val STAGING_HEADROOM_BYTES = 64L * MIB
        private const val INSTALL_HEADROOM_BYTES = 256L * MIB
    }
}
