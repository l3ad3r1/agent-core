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
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.arm.aichat.isModelLoaded
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.product.ProductIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
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
/**
 * Marks the current coroutine as background inference.
 *
 * Work tagged this way runs on its own KV lane, so it cannot evict the
 * conversation's cached prefix — the two prompts share almost nothing, so
 * before lanes existed each background call left the next chat turn cold.
 *
 * This rides in the coroutine context rather than a parameter because the call
 * reaches the engine through the provider-agnostic [LlmProvider] interface,
 * which cloud providers implement too and which has no business knowing about
 * KV lanes. Deliberately not a [LocalModelRole]: a role picks which *model* is
 * loaded, a lane picks which cache a call uses within one model.
 */
object AuxiliaryInference : CoroutineContext.Element {
    // Resolved on access, not in the initialiser: an object that hands itself to
    // its own superclass constructor fails static init, and the failure surfaces
    // far away — runCatching around the call site swallows the Error and the
    // summary just silently returns null.
    override val key: CoroutineContext.Key<*> get() = Key

    object Key : CoroutineContext.Key<AuxiliaryInference>
}

/**
 * Free memory required, on top of the tool caller's own weights, before both
 * models are allowed to stay resident. Covers the second KV cache and leaves the
 * rest of the app room to work.
 */
private const val BOTH_MODELS_HEADROOM_BYTES = 900L * 1024 * 1024

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
     *
     * Only meaningful when the two roles are sharing one slot — see
     * [canHoldBothModels]. When they are not, each role has its own engine and
     * neither evicts the other.
     */
    private var loadedRole: LocalModelRole? = null

    /**
     * How the tool caller's engine is built.
     *
     * A seam rather than a constructor parameter: this class is Hilt-injected,
     * so an extra parameter would have to be bound in every app's module purely
     * for tests. Overridden in tests to stand in for an engine that cannot be
     * built off-device — the real one loads the native library.
     */
    internal var toolCallerEngineFactory: () -> InferenceEngine = {
        AiChat.getInferenceEngine(context, InferenceEngine.Slot.TOOL_CALLER)
    }

    /**
     * The engine driving the tool caller's own slot; the injected [engine]
     * drives the chat slot. Null until this device actually uses the tool
     * caller, so one that never does never pays for a second slot.
     */
    private var residentToolCallerEngine: InferenceEngine? = null

    /**
     * The tool caller's engine, built on first use.
     *
     * Deliberately not a `lazy`: whether the slot exists yet is something the
     * cleanup path has to ask, and a null check reads more plainly than
     * `isInitialized()` on a delegate.
     */
    internal fun toolCallerEngine(): InferenceEngine =
        residentToolCallerEngine ?: toolCallerEngineFactory().also { residentToolCallerEngine = it }

    /**
     * Whether both models may stay resident at once.
     *
     * Two slots means two sets of weights and two KV caches. That is the whole
     * point — a tool turn stops evicting the conversation and rebuilding its
     * context — but it is only affordable where there is headroom, so a
     * low-memory device keeps the old behaviour of swapping one model in and
     * out of a single slot. Weights are mmap'd, so the kernel can still reclaim
     * them; the reserve here is for the KV caches and the app itself.
     */
    private fun canHoldBothModels(): Boolean {
        val mem = getMemoryInfo()
        if (mem.lowMemory) return false
        val needed = ToolCallerCatalog.DEFAULT.sizeBytes + BOTH_MODELS_HEADROOM_BYTES
        return mem.availMem >= needed
    }

    /** The engine that serves [role], which may or may not be its own slot. */
    private fun engineFor(role: LocalModelRole): InferenceEngine =
        if (role == LocalModelRole.TOOL_CALLER && canHoldBothModels()) toolCallerEngine() else engine

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

    /**
     * Initializes the engine chosen by the caller while holding [modelMutex].
     * The residency decision must not be re-evaluated during one request: RAM
     * can change between checks, yielding a model loaded in one slot and a
     * prompt sent to another.
     */
    private suspend fun initializeLocked(role: LocalModelRole, target: InferenceEngine) {
        if (target !== engine) {
            // This request moved the tool caller from the shared slot to its
            // dedicated slot. The old shared model cannot be the requested
            // role at the same time.
            if (loadedRole == LocalModelRole.TOOL_CALLER && engine.state.value.isModelLoaded) {
                engine.cleanUp()
                loadedRole = null
            }
            // This role has its own slot, so nothing is evicted and `loadedRole`
            // — which tracks the shared slot — does not apply.
            val settled = target.state.first {
                it !is State.Uninitialized && it !is State.Initializing
            }
            if (settled.isModelLoaded) return
            if (settled is State.Error) target.cleanUp()
            loadToolCallerLocked(target)
            return
        }

        // The residency decision moved from a dedicated tool slot back to the
        // shared slot. Release the old dedicated model deliberately rather
        // than leaving two models resident on a low-memory device.
        if (role == LocalModelRole.TOOL_CALLER) {
            residentToolCallerEngine
                ?.takeIf { it.state.value.isModelLoaded }
                ?.cleanUp()
        }

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
            LocalModelRole.TOOL_CALLER -> loadToolCallerLocked(target)
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
    private suspend fun loadToolCallerLocked(target: InferenceEngine) {
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

        // [target] was selected once for this request under [modelMutex].
        target.loadModel(modelFile.absolutePath)
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
            val target = engineFor(role)
            // With its own slot a role is ready when *its* engine has a model;
            // loadedRole only describes the shared slot.
            val ready = if (target !== engine) {
                target.state.value.isModelLoaded
            } else {
                engine.state.value.isModelLoaded && loadedRole == role
            }
            if (!ready) initializeLocked(role, target)
            // Always reset native chat state: the provider supplies a bounded transcript
            // on every call, including internal calls that have no explicit system message.
            val lane = if (currentCoroutineContext()[AuxiliaryInference.Key] != null) {
                InferenceEngine.Lane.AUXILIARY
            } else {
                InferenceEngine.Lane.CHAT
            }
            target.setSystemPrompt(
                systemPrompt.ifBlank {
                    "You are ${productIdentity.displayName}, a helpful on-device assistant."
                },
                lane,
            )
            target.sendUserPrompt(userPrompt, lane = lane).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun setLocalModelUri(uri: String) = updateModelSelection("change the custom model") {
        settingsRepository.setLocalModelUri(uri)
    }

    suspend fun setSelectedModelId(id: String) = updateModelSelection("select that model") {
        settingsRepository.setSelectedModelId(id)
    }

    /**
     * Both models live in this folder, so moving it invalidates the tool
     * caller's resident model as well as the chat one — unlike the settings
     * above, which only pick a chat model.
     */
    suspend fun setModelDownloadDir(dir: String) =
        updateModelSelection("change the model folder", alsoToolCaller = true) {
            settingsRepository.setModelDownloadDir(dir)
        }

    private suspend fun updateModelSelection(
        action: String,
        alsoToolCaller: Boolean = false,
        persist: suspend () -> Unit,
    ) = modelMutex.withLock {
        try {
            // Native unload + persistence — keep it off the caller's thread
            // (SettingsViewModel starts these on viewModelScope = Main).
            withContext(Dispatchers.IO) {
                engine.cleanUp()
                loadedRole = null
                // The tool caller has its own slot now, so unloading the chat
                // engine no longer touches it — it would go on serving a model
                // loaded from a folder the user just moved. Null-safe on
                // purpose: a device that never used the tool caller must not
                // build an engine, and load a native library, just to unload it.
                if (alsoToolCaller) residentToolCallerEngine?.cleanUp()
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
