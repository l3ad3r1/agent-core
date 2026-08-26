package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hermes.agent.work.LocalModelDownloadWorker
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class ModelDownloadSnapshot(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val error: String = "",
)

/**
 * Picks the run that the UI should reflect out of everything WorkManager still
 * holds for the unique download name.
 *
 * `enqueueUniqueWork(REPLACE)` does not erase the run it replaces — it cancels
 * it and keeps the record until it is pruned, and the returned list has no
 * guaranteed order. Taking the last element therefore surfaced whichever record
 * happened to come back last, so starting a download could report the *previous*
 * attempt: "The model download was cancelled", with no progress, while the new
 * one was in fact running.
 *
 * Preference order: the run this process enqueued, then any run still going,
 * then a finished run that was not cancelled, and only then anything at all.
 */
internal fun selectActiveWorkInfo(
    workInfos: List<WorkInfo>,
    trackedId: UUID?,
): WorkInfo? =
    workInfos.firstOrNull { it.id == trackedId }
        ?: workInfos.firstOrNull { !it.state.isFinished }
        ?: workInfos.firstOrNull { it.state != WorkInfo.State.CANCELLED }
        ?: workInfos.lastOrNull()

internal fun modelDownloadSnapshot(
    state: WorkInfo.State?,
    progressPercent: Int,
    workerError: String,
): ModelDownloadSnapshot = when (state) {
    WorkInfo.State.ENQUEUED,
    WorkInfo.State.BLOCKED,
    WorkInfo.State.RUNNING,
    -> ModelDownloadSnapshot(
        isDownloading = true,
        progress = progressPercent.coerceIn(0, 100) / 100f,
    )
    WorkInfo.State.SUCCEEDED -> ModelDownloadSnapshot(progress = 1f)
    WorkInfo.State.FAILED -> ModelDownloadSnapshot(
        error = workerError.ifBlank { "The model download failed. Try again." },
    )
    WorkInfo.State.CANCELLED -> ModelDownloadSnapshot(error = "The model download was cancelled.")
    null -> ModelDownloadSnapshot()
}

@Singleton
class LocalModelDownloadCoordinator @Inject constructor(
    private val workManager: WorkManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val immediateError = MutableStateFlow("")
    private val dismissedFailureId = MutableStateFlow<UUID?>(null)

    /** The request this process enqueued, so its own run wins over a replaced one. */
    private val trackedRequestId = MutableStateFlow<UUID?>(null)

    private val latestWorkInfo: StateFlow<WorkInfo?> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(LocalModelDownloadWorker.UNIQUE_NAME),
        trackedRequestId,
    ) { workInfos, trackedId -> selectActiveWorkInfo(workInfos, trackedId) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val snapshot: StateFlow<ModelDownloadSnapshot> = combine(
        latestWorkInfo,
        immediateError,
        dismissedFailureId,
    ) { info, localError, dismissedId ->
        if (localError.isNotBlank()) {
            ModelDownloadSnapshot(error = localError)
        } else if (info?.id == dismissedId) {
            ModelDownloadSnapshot()
        } else {
            modelDownloadSnapshot(
                state = info?.state,
                progressPercent = info?.progress?.getInt(LocalModelDownloadWorker.KEY_PROGRESS, 0) ?: 0,
                workerError = info?.outputData?.getString(LocalModelDownloadWorker.KEY_ERROR).orEmpty(),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, ModelDownloadSnapshot())

    val isDownloading: StateFlow<Boolean> = snapshot
        .map { it.isDownloading }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val progress: StateFlow<Float> = snapshot
        .map { it.progress }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    val error: StateFlow<String> = snapshot
        .map { it.error }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, "")

    fun enqueue(model: DownloadableModel, destinationDir: File) {
        immediateError.value = ""
        dismissedFailureId.value = null
        val request = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    LocalModelDownloadWorker.KEY_MODEL_ID to model.id,
                    LocalModelDownloadWorker.KEY_DESTINATION_DIR to destinationDir.absolutePath,
                ),
            )
            .build()
        trackedRequestId.value = request.id
        workManager.enqueueUniqueWork(
            LocalModelDownloadWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork(LocalModelDownloadWorker.UNIQUE_NAME)
    }

    fun reportError(message: String) {
        immediateError.value = message
    }

    fun clearError() {
        immediateError.value = ""
        val info = latestWorkInfo.value
        if (info?.state == WorkInfo.State.FAILED || info?.state == WorkInfo.State.CANCELLED) {
            dismissedFailureId.value = info.id
        }
    }
}
