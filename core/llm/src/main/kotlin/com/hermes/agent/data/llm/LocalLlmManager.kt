package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.arm.aichat.isModelLoaded
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.product.ProductIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Which of the two on-device models a caller wants.
 *
 * They cannot both be resident. `InferenceEngineImpl` is a process-wide
 * singleton over global native state — its JNI entry points (`load`, `unload`,
 * `processUserPrompt`) take no model handle, and every call is serialised onto
 * one thread — so llama.cpp holds exactly one model for the whole app. Asking
 * for a role that is not the loaded one evicts the other model and loads this
 * one, which costs a reload, so the roles are worth keeping few and coarse.
 */
enum class LocalModelRole {
    /** Conversation. The model the user picks in Settings. */
    CHAT,

    /** Tool calls only. The small model from [ToolCallerCatalog]. */
    TOOL_CALLER,
}

@Singleton
class LocalLlmManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadCoordinator: LocalModelDownloadCoordinator,
    private val engine: InferenceEngine,
    private val productIdentity: ProductIdentity,
) {
    private val modelMutex = Mutex()

    /**
     * The role whose weights are currently in the engine, or null when nothing
     * is loaded. Guarded by [modelMutex] along with every engine transition.
     */
    private var loadedRole: LocalModelRole? = null

    private suspend fun activeModel(): DownloadableModel =
        ModelCatalog.byId(settingsRepository.current().selectedModelId)

    private suspend fun destinationDir(): File {
        val custom = settingsRepository.current().modelDownloadDir
        return if (custom.isNotBlank()) File(custom)
        else File(Environment.getExternalStorageDirectory(), ModelCatalog.DEFAULT_DIR_NAME)
    }

    private suspend fun currentModelFile(): File = File(destinationDir(), activeModel().fileName)

    private suspend fun toolCallerFile(): File =
        File(destinationDir(), ToolCallerCatalog.DEFAULT.fileName)

    /**
     * Whether this build is debuggable.
     *
     * Gates logging of raw model output. [com.hermes.agent.data.log.FileLogTree]
     * is planted on every build type and writes every priority to a log file the
     * user can export, so a debug line carrying a model reply — which is derived
     * from whatever they typed — would persist in release builds too.
     */
    val isDebuggable: Boolean
        get() = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Reads current device memory info.
     */
    private fun getMemoryInfo(): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memoryInfo)
        return memoryInfo
    }

    /**
     * Evaluates RAM preflight for a downloadable catalog model.
     */
    fun evaluatePreflight(
        model: DownloadableModel,
        requestedContextTokens: Int = 2048,
    ): PreflightDecision {
        val memInfo = getMemoryInfo()
        return LocalModelPreflight.evaluate(
            modelBytes = model.sizeBytes,
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            lowMemory = memInfo.lowMemory,
            requestedContextTokens = requestedContextTokens,
        )
    }

    /**
     * Evaluates RAM preflight for a custom model from URI.
     */
    fun evaluateCustomModelPreflight(
        uri: Uri,
        requestedContextTokens: Int = 2048,
    ): PreflightDecision {
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L
        val memInfo = getMemoryInfo()
        return LocalModelPreflight.evaluate(
            modelBytes = size,
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            lowMemory = memInfo.lowMemory,
            requestedContextTokens = requestedContextTokens,
        )
    }

    /**
     * Whether the selected model is present. This does storage-provider (SAF
     * binder) and filesystem IO, so it MUST run off the main thread — every
     * caller reaches it from `viewModelScope` (Main).
     *
     * Pinned models check file presence and the fast `<fileName>.verified` sidecar
     * to avoid re-hashing multi-GB files on ANR-sensitive paths.
     */
    suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        if (settings.localModelUri.isNotBlank()) {
            return@withContext runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(settings.localModelUri), "r")
                    ?.use { it.statSize != 0L } == true
            }.getOrDefault(false)
        }
        val model = activeModel()
        val file = currentModelFile()
        if (!file.isFile || file.length() != model.sizeBytes) {
            return@withContext false
        }
        // If sidecar exists, verify it matches
        if (LocalModelInstaller.sidecarFile(file).isFile) {
            LocalModelInstaller.isSidecarValid(file, model.sha256, model.sizeBytes)
        } else {
            // Legacy download before sidecar support — accept size match and write sidecar
            LocalModelInstaller.writeSidecar(file, model.sha256)
            true
        }
    }

    /**
     * Whether the tool-calling model is present.
     *
     * Same size-then-sidecar check as [isModelDownloaded]'s catalog path, minus
     * the custom-URI branch: this model is never user-supplied.
     */
    suspend fun isToolCallerDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val model = ToolCallerCatalog.DEFAULT
        val file = toolCallerFile()
        if (!file.isFile || file.length() != model.sizeBytes) {
            return@withContext false
        }
        if (LocalModelInstaller.sidecarFile(file).isFile) {
            LocalModelInstaller.isSidecarValid(file, model.sha256, model.sizeBytes)
        } else {
            LocalModelInstaller.writeSidecar(file, model.sha256)
            true
        }
    }

    val isDownloading: StateFlow<Boolean> = downloadCoordinator.isDownloading
    val downloadProgress: StateFlow<Float> = downloadCoordinator.progress
    val downloadError: StateFlow<String> = downloadCoordinator.error

    suspend fun startDownload() {
        if (isDownloading.value || isModelDownloaded()) return
        if (!hasStorageAccess(context)) {
            downloadCoordinator.reportError(
                "Storage access is required to save the model. Grant it above and try again.",
            )
            return
        }
        downloadCoordinator.enqueue(activeModel(), destinationDir())
    }

    /**
     * Fetches the tool-calling model.
     *
     * Shares the coordinator — and therefore the single [isDownloading] /
     * [downloadProgress] slot — with the chat model, so the two downloads cannot
     * be shown separately and the second is refused while the first runs. That
     * is acceptable while this is one 291 MB fetch behind a settings toggle; a
     * second progress slot is the fix if the UI ever offers both at once.
     */
    suspend fun startToolCallerDownload() {
        if (isDownloading.value || isToolCallerDownloaded()) return
        if (!hasStorageAccess(context)) {
            downloadCoordinator.reportError(
                "Storage access is required to save the model. Grant it above and try again.",
            )
            return
        }
        downloadCoordinator.enqueue(ToolCallerCatalog.DEFAULT, destinationDir())
    }

    fun clearDownloadError() = downloadCoordinator.clearError()

    fun cancelDownload() = downloadCoordinator.cancelDownload()

    private suspend fun initializeLocked(role: LocalModelRole) {
        val settledState = engine.state.first {
            it !is State.Uninitialized && it !is State.Initializing
        }
        if (settledState.isModelLoaded && loadedRole == null) {
            // Resident before this manager recorded a role. It can only be the
            // chat model — nothing else ever loaded one — and adopting it keeps
            // a warm engine from being evicted and reloaded for the role it is
            // already serving.
            loadedRole = LocalModelRole.CHAT
        }
        when {
            settledState.isModelLoaded && loadedRole == role -> return
            settledState.isModelLoaded -> {
                // Only one model fits in the process — see [LocalModelRole] —
                // so serving this role evicts the other. Logged because a turn
                // that alternates roles pays a full reload each way, and that
                // thrash is invisible otherwise.
                Timber.i("Swapping on-device model: %s -> %s", loadedRole, role)
                engine.cleanUp()
                loadedRole = null
            }
            settledState is State.Error -> {
                engine.cleanUp()
                loadedRole = null
            }
            settledState !is State.Initialized -> throw IllegalStateException(
                "Local model is busy (${settledState.javaClass.simpleName}). Try again.",
            )
        }

        when (role) {
            LocalModelRole.TOOL_CALLER -> loadToolCallerLocked()
            LocalModelRole.CHAT -> loadChatModelLocked()
        }
        loadedRole = role
    }

    /**
     * Loads the tool-calling model.
     *
     * Catalog-only: no custom-URI branch, because this model is fixed and
     * validated against a pinned digest rather than chosen by the user.
     */
    private suspend fun loadToolCallerLocked() {
        val model = ToolCallerCatalog.DEFAULT
        if (!isToolCallerDownloaded()) {
            throw IllegalStateException(
                "The on-device tool caller is not downloaded. Download it in settings.",
            )
        }
        val modelFile = toolCallerFile()

        when (val validation = LocalModelValidator.validate(modelFile, expectedSizeBytes = model.sizeBytes)) {
            is ModelValidation.Rejected -> {
                throw IllegalStateException("Tool caller validation rejected: ${validation.reason}")
            }
            is ModelValidation.Valid -> {
                Timber.i("Validated tool-caller GGUF model: %s", validation.summary)
            }
        }

        val preflight = evaluatePreflight(model)
        if (!preflight.allowed || preflight.level == PreflightLevel.BLOCKED) {
            throw IllegalStateException("Tool caller load blocked by preflight: ${preflight.detail}")
        }
        if (preflight.level == PreflightLevel.WARNING) {
            Timber.w("Tool caller preflight warning: %s", preflight.detail)
        }

        engine.loadModel(modelFile.absolutePath)
    }

    private suspend fun loadChatModelLocked() {
        if (!isModelDownloaded()) {
            throw IllegalStateException("Model not downloaded yet. Please download it in settings.")
        }

        val customUri = settingsRepository.current().localModelUri
        if (customUri.isNotBlank()) {
            val uri = Uri.parse(customUri)

            // Phase 1: GGUF validation for custom model
            when (val validation = LocalModelValidator.validate(context, uri)) {
                is ModelValidation.Rejected -> {
                    throw IllegalStateException("Custom model validation rejected: ${validation.reason}")
                }
                is ModelValidation.Valid -> {
                    Timber.i("Validated custom GGUF model: %s", validation.summary)
                }
            }

            // Phase 1: RAM Preflight check
            val preflight = evaluateCustomModelPreflight(uri)
            if (!preflight.allowed || preflight.level == PreflightLevel.BLOCKED) {
                throw IllegalStateException("Model load blocked by preflight: ${preflight.detail}")
            }
            if (preflight.level == PreflightLevel.WARNING) {
                Timber.w("Model preflight warning: %s", preflight.detail)
            }

            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                engine.loadModel("/proc/self/fd/${descriptor.fd}")
            } ?: throw IllegalStateException("Cannot open the custom model file. Choose it again.")
        } else {
            val model = activeModel()
            val modelFile = currentModelFile()

            // Phase 1: GGUF validation for catalog model
            when (val validation = LocalModelValidator.validate(modelFile, expectedSizeBytes = model.sizeBytes)) {
                is ModelValidation.Rejected -> {
                    throw IllegalStateException("Model validation rejected: ${validation.reason}")
                }
                is ModelValidation.Valid -> {
                    Timber.i("Validated catalog GGUF model: %s", validation.summary)
                }
            }

            // Phase 1: RAM Preflight check
            val preflight = evaluatePreflight(model)
            if (!preflight.allowed || preflight.level == PreflightLevel.BLOCKED) {
                throw IllegalStateException("Model load blocked by preflight: ${preflight.detail}")
            }
            if (preflight.level == PreflightLevel.WARNING) {
                Timber.w("Model preflight warning: %s", preflight.detail)
            }

            engine.loadModel(modelFile.absolutePath)
        }
    }

    fun generateResponse(systemPrompt: String, userPrompt: String): Flow<String> =
        generateResponse(LocalModelRole.CHAT, systemPrompt, userPrompt)

    fun generateResponse(
        role: LocalModelRole,
        systemPrompt: String,
        userPrompt: String,
    ): Flow<String> = flow {
        modelMutex.withLock {
            // Checking the role as well as the loaded flag: a model may well be
            // resident and still be the wrong one.
            if (!engine.state.value.isModelLoaded || loadedRole != role) initializeLocked(role)
            // Always reset native chat state: the provider supplies a bounded transcript
            // on every call, including internal calls that have no explicit system message.
            engine.setSystemPrompt(
                systemPrompt.ifBlank {
                    "You are ${productIdentity.displayName}, a helpful on-device assistant."
                },
            )
            engine.sendUserPrompt(userPrompt).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun setLocalModelUri(uri: String) = updateModelSelection("change the custom model") {
        settingsRepository.setLocalModelUri(uri)
    }

    suspend fun setSelectedModelId(id: String) = updateModelSelection("select that model") {
        settingsRepository.setSelectedModelId(id)
    }

    suspend fun setModelDownloadDir(dir: String) = updateModelSelection("change the model folder") {
        settingsRepository.setModelDownloadDir(dir)
    }

    private suspend fun updateModelSelection(
        action: String,
        persist: suspend () -> Unit,
    ) = modelMutex.withLock {
        try {
            // Native unload + persistence — keep it off the caller's thread
            // (SettingsViewModel starts these on viewModelScope = Main).
            withContext(Dispatchers.IO) {
                engine.cleanUp()
                loadedRole = null
                persist()
            }
        } catch (error: Exception) {
            Timber.e(error, "Could not %s", action)
            downloadCoordinator.reportError(
                "Couldn't $action. Stop any active response and try again. " +
                    (error.message ?: "The local model could not be unloaded."),
            )
        }
    }

    companion object {
        fun hasStorageAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
            }
    }
}
