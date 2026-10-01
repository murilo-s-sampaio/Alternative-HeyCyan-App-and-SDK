package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.localmodels.InstalledModelUiItem
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelCatalogUiItem
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelDownloadUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelGenerationUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelOptionField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTextField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelToggleField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsAction
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsConfigureUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsSection
import com.fersaiyan.cyanbridge.shared.localmodels.RemoteInferenceUiState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSUserDomainMask
import platform.Foundation.downloadTaskWithURL
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDestructive
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import kotlin.time.TimeSource

/**
 * On-device models for the "Local" AI provider (Android: LocalModelsConfigureActivity +
 * LocalModelStorageRepository). Kotlin keeps the files, catalog and settings; the Swift
 * [LocalModelBridge] runs them with llama.cpp (.gguf) or LiteRT-LM (.litertlm).
 */
@OptIn(ExperimentalForeignApi::class)
object IosLocalModels {
    private class CatalogModel(
        val id: String,
        val title: String,
        val details: String,
        val url: String,
        val fileName: String,
    )

    private val catalog = listOf(
        CatalogModel(
            id = "smollm2-135m-q8",
            title = "SmolLM2 135M Instruct · llama.cpp",
            details = "145 MB GGUF. The smallest test model: fast everywhere, English only, weak answers.",
            url = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q8_0.gguf",
            fileName = "SmolLM2-135M-Instruct-Q8_0.gguf",
        ),
        CatalogModel(
            id = "qwen25-05b-q4",
            title = "Qwen2.5 0.5B Instruct · llama.cpp",
            details = "491 MB GGUF (Q4_K_M). Small multilingual chat model; understands Portuguese.",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        ),
        CatalogModel(
            id = "qwen3-06b-litert",
            title = "Qwen3 0.6B · LiteRT-LM",
            details = "497 MB .litertlm (int4). Tests Google's LiteRT-LM runtime. May print its reasoning first.",
            url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
            fileName = "qwen3_0_6b_mixed_int4.litertlm",
        ),
    )

    private const val KEY_SELECTED = "selected_model"
    private const val KEY_USE_GPU = "use_gpu"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val CONTEXT_TOKENS = 2048
    private const val REPLY_TOKENS = 512
    private const val DEFAULT_SYSTEM_PROMPT =
        "You are a helpful assistant on smart glasses. Answer briefly, in the user's language."
    private const val TAG = "IosLocalModels"

    private val preferences = createPlatformPreferences("cyanbridge_local_models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runMutex = Mutex()
    private var loadedPath: String? = null
    private var loadedOnGpu = false
    private var downloadTask: NSURLSessionDownloadTask? = null
    private var downloadJob: Job? = null
    private var pickerDelegate: ModelPickerDelegate? = null

    var isOpen by mutableStateOf(false)
        private set
    var uiState by mutableStateOf(LocalModelsConfigureUiState())
        private set

    private val bridge: LocalModelBridge? get() = LocalModelRegistry.bridge

    // ── Storage ──

    private val modelsDirectory: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
        val path = "$base/local-models"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        // Models are large and re-downloadable: keep them out of iCloud backups.
        NSURL.fileURLWithPath(path).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
        path
    }

    private fun isModelFile(name: String) =
        name.endsWith(".gguf", ignoreCase = true) || name.endsWith(".litertlm", ignoreCase = true)

    private fun installedFiles(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(modelsDirectory, null)
            ?.mapNotNull { it as? String }
            ?.filter(::isModelFile)
            ?.sorted()
            .orEmpty()

    private fun pathOf(name: String) = "$modelsDirectory/$name"

    private fun sizeOf(name: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(pathOf(name), null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue ?: 0L

    private val selectedName: String?
        get() = preferences.getString(KEY_SELECTED, "").takeIf { it.isNotBlank() && it in installedFiles() }

    private val useGpu: Boolean
        get() = preferences.getBoolean(KEY_USE_GPU, true) && bridge?.gpuAvailable() == true

    private val systemPrompt: String
        get() = preferences.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)

    /** True when chats should run on the selected on-device model. */
    val isActive: Boolean get() = bridge != null && selectedName != null

    // ── Inference ──

    suspend fun chat(messages: List<ChatMessage>, onToken: (String) -> Unit = {}): String = runMutex.withLock {
        val runtime = bridge ?: error("On-device runtimes are not available in this build")
        val name = selectedName ?: error("No on-device model selected")
        val path = pathOf(name)
        if (loadedPath != path || loadedOnGpu != useGpu) {
            refreshUi("Loading $name…")
            runtime.awaitLoad(path, useGpu, CONTEXT_TOKENS).getOrElse {
                loadedPath = null
                refreshUi()
                throw it
            }
            loadedPath = path
            loadedOnGpu = useGpu
            PlatformLogger.i(TAG, "Loaded $name (gpu=$useGpu)")
        }
        val messagesJson = buildJsonArray {
            messages.filter { it.content.isNotBlank() }.forEach { message ->
                addJsonObject {
                    put("role", message.role.lowercase())
                    put("content", message.content)
                }
            }
        }.toString()
        val reply = runtime.awaitGenerate(messagesJson, systemPrompt, REPLY_TOKENS, onToken).getOrThrow()
        refreshUi()
        stripReasoning(reply).ifBlank { error("The model returned an empty reply") }
    }

    /** Qwen3-style models think inside <think> tags before answering. */
    private fun stripReasoning(text: String): String =
        text.replace(Regex("<think>[\\s\\S]*?</think>"), "").substringBefore("<think>").trim()

    /**
     * Launch-time check used with CYANBRIDGE_LOCAL_MODEL_SELFTEST (1 or a model file name):
     * selects that model, or the first installed one when none is chosen, asks one question
     * and logs the outcome.
     */
    fun runSelfTest(modelName: String) {
        scope.launch {
            if (modelName in installedFiles()) preferences.putString(KEY_SELECTED, modelName)
            if (selectedName == null) installedFiles().firstOrNull()?.let { preferences.putString(KEY_SELECTED, it) }
            val name = selectedName ?: return@launch PlatformLogger.w(TAG, "Self-test: no model in $modelsDirectory")
            PlatformLogger.i(TAG, "Self-test: $name (gpu=$useGpu)")
            val started = TimeSource.Monotonic.markNow()
            runCatching { chat(listOf(ChatMessage("user", "What is the capital of France? Answer in one sentence."))) }
                .onSuccess { PlatformLogger.i(TAG, "Self-test reply in ${started.elapsedNow()}: $it") }
                .onFailure { PlatformLogger.e(TAG, "Self-test failed: ${it.message}") }
        }
    }

    // ── Screen ──

    fun open() {
        uiState = buildState(remote = IosRemoteModelSettings.savedDraft())
        isOpen = true
    }

    fun handle(action: LocalModelsAction) {
        when (action) {
            LocalModelsAction.Back, LocalModelsAction.DiscardChangesAndBack -> isOpen = false
            LocalModelsAction.Refresh -> refreshUi()
            LocalModelsAction.ImportModel -> importModel()
            is LocalModelsAction.SelectInstalledModel -> {
                preferences.putString(KEY_SELECTED, action.id)
                refreshUi()
            }
            LocalModelsAction.ShowSelectedModelInfo -> showSelectedModelInfo()
            LocalModelsAction.UnloadSelectedModel -> unload("Model unloaded. It loads again on the next question.")
            LocalModelsAction.RemoveSelectedModel -> selectedName?.let(::confirmDelete)
            is LocalModelsAction.DownloadCatalogModel -> download(action.id)
            is LocalModelsAction.ShowCatalogModelInfo -> catalog.firstOrNull { it.id == action.id }?.let {
                IosMediaPlatform.showCopyAlert(it.title, "${it.details}\n\n${it.url}", "Copy link", it.url)
            }
            LocalModelsAction.CancelDownload -> downloadTask?.cancel()
            LocalModelsAction.RunWarmup -> runWarmup()
            LocalModelsAction.SaveGenerationSettings -> {
                preferences.putString(KEY_SYSTEM_PROMPT, uiState.generation.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT })
                uiState = uiState.copy(hasUnsavedChanges = false, warmupResult = "Settings saved.")
            }
            is LocalModelsAction.ToggleSection -> uiState = when (action.section) {
                LocalModelsSection.CATALOG -> uiState.copy(catalogExpanded = !uiState.catalogExpanded)
                LocalModelsSection.REMOTE_SERVER -> uiState.copy(remoteServerExpanded = !uiState.remoteServerExpanded)
                else -> uiState
            }
            is LocalModelsAction.SelectOption -> if (action.field == LocalModelOptionField.COMPUTE_BACKEND) {
                preferences.putBoolean(KEY_USE_GPU, action.index == 0 && bridge?.gpuAvailable() == true)
                refreshUi()
            }
            is LocalModelsAction.UpdateText -> when (action.field) {
                LocalModelTextField.SYSTEM_PROMPT -> uiState = uiState.copy(
                    generation = uiState.generation.copy(systemPrompt = action.value),
                    hasUnsavedChanges = true,
                )
                LocalModelTextField.REMOTE_BASE_URL -> editRemote { it.copy(baseUrl = action.value) }
                LocalModelTextField.REMOTE_MODEL_NAME -> editRemote { it.copy(modelName = action.value) }
                LocalModelTextField.REMOTE_API_KEY -> editRemote { it.copy(apiKey = action.value) }
                else -> Unit
            }
            is LocalModelsAction.SetToggle -> if (action.field == LocalModelToggleField.REMOTE_SERVER_ENABLED) {
                editRemote { it.copy(enabled = action.enabled) }
            }
            LocalModelsAction.TestRemoteServer -> {
                val draft = uiState.remoteServer
                setRemoteStatus("Testing ${draft.baseUrl.trim()}…")
                scope.launch { setRemoteStatus(IosRemoteOpenAiClient.healthCheck(draft.baseUrl, draft.apiKey)) }
            }
            LocalModelsAction.SaveRemoteServer -> {
                val status = IosRemoteModelSettings.save(uiState.remoteServer)
                uiState = buildState(remote = uiState.remoteServer.copy(status = status))
                    .copy(hasUnsavedChanges = false)
            }
            else -> Unit
        }
    }

    private fun editRemote(change: (RemoteInferenceUiState) -> RemoteInferenceUiState) {
        uiState = uiState.copy(remoteServer = change(uiState.remoteServer), hasUnsavedChanges = true)
    }

    private fun setRemoteStatus(status: String) {
        uiState = uiState.copy(remoteServer = uiState.remoteServer.copy(status = status))
    }

    private fun refreshUi(warmup: String? = null) {
        scope.launch {
            val current = uiState
            uiState = buildState(remote = current.remoteServer).copy(
                catalogExpanded = current.catalogExpanded,
                remoteServerExpanded = current.remoteServerExpanded,
                download = current.download,
                hasUnsavedChanges = current.hasUnsavedChanges,
                warmupResult = warmup ?: current.warmupResult,
                generation = current.generation.copy(
                    computeBackendOptions = backendOptions(),
                    computeBackendIndex = if (useGpu) 0 else backendOptions().lastIndex,
                    computeBackendNote = backendNote(),
                ),
            )
        }
    }

    private fun backendOptions(): List<String> =
        if (bridge?.gpuAvailable() == true) listOf("GPU (Metal)", "CPU") else listOf("CPU")

    private fun backendNote(): String =
        if (bridge?.gpuAvailable() == true) "GPU is faster; switch to CPU if a model fails to load."
        else "The simulator runs models on the CPU only."

    private fun buildState(remote: RemoteInferenceUiState): LocalModelsConfigureUiState {
        val files = installedFiles()
        val selected = selectedName
        val ramGb = NSProcessInfo.processInfo.physicalMemory.toDouble() / (1024.0 * 1024 * 1024)
        val loaded = selected != null && loadedPath == pathOf(selected)
        return LocalModelsConfigureUiState(
            engineStatus = if (bridge != null) "Runtimes: llama.cpp (.gguf) + LiteRT-LM (.litertlm)"
            else "On-device runtimes are not available in this build",
            deviceSummary = "${UIDevice.currentDevice.model} · ${(ramGb * 10).toInt() / 10.0} GB RAM",
            selectedModelStatus = when {
                selected == null -> "Status: no model selected — chats use the relay or remote server."
                IosRemoteModelSettings.isActive -> "Status: $selected is selected, but the remote server is on and takes priority."
                loaded -> "Status: $selected loaded on ${if (loadedOnGpu) "GPU" else "CPU"}. Chats run on this iPhone."
                else -> "Status: $selected selected. It loads on the first question."
            },
            emptyStateMessage = "No model yet. Download a test model below or import a .gguf / .litertlm file from Files.",
            installedModels = files.map { InstalledModelUiItem(it, "$it (${formatSize(sizeOf(it))})") },
            selectedInstalledModelId = selected,
            catalog = catalog.map { model ->
                val installed = model.fileName in files
                LocalModelCatalogUiItem(
                    id = model.id,
                    title = model.title,
                    details = model.details,
                    status = if (installed) "Installed" else "Not downloaded",
                    downloadLabel = if (installed) "Installed" else "Download",
                    canDownload = !installed && downloadTask == null,
                )
            },
            catalogExpanded = files.isEmpty(),
            remoteServerExpanded = remote.enabled,
            generation = LocalModelGenerationUiState(
                computeBackendOptions = backendOptions(),
                computeBackendIndex = if (useGpu) 0 else backendOptions().lastIndex,
                computeBackendNote = backendNote(),
                systemPrompt = systemPrompt,
            ),
            remoteServer = remote,
        )
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1_000_000_000) "${(bytes / 100_000_000) / 10.0} GB" else "${bytes / 1_000_000} MB"

    // ── Actions ──

    private fun unload(message: String) {
        bridge?.unload()
        loadedPath = null
        refreshUi(message)
    }

    private fun runWarmup() {
        if (selectedName == null) return
        uiState = uiState.copy(warmupResult = "Running a test prompt…")
        scope.launch {
            val started = TimeSource.Monotonic.markNow()
            var pieces = 0
            val result = runCatching {
                chat(listOf(ChatMessage("user", "Say hello and tell me what you are in one short sentence."))) { pieces++ }
            }
            val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
            val message = result.fold(
                onSuccess = { "Reply in ${(seconds * 10).toInt() / 10.0}s (~${if (seconds > 0) (pieces / seconds).toInt() else 0} tokens/s):\n$it" },
                onFailure = { "Test failed: ${it.message}" },
            )
            PlatformLogger.i(TAG, message.lineSequence().first())
            uiState = uiState.copy(warmupResult = message)
            refreshUi()
        }
    }

    private fun showSelectedModelInfo() {
        val name = selectedName ?: return
        val presenter = IosMediaPlatform.topViewController() ?: return
        val runtime = if (name.endsWith(".gguf", ignoreCase = true)) "llama.cpp" else "LiteRT-LM"
        val alert = UIAlertController.alertControllerWithTitle(
            name,
            "Runtime: $runtime\nSize: ${formatSize(sizeOf(name))}\nContext: $CONTEXT_TOKENS tokens",
            UIAlertControllerStyleAlert,
        )
        alert.addAction(UIAlertAction.actionWithTitle("Delete model", UIAlertActionStyleDestructive) { _ -> confirmDelete(name) })
        alert.addAction(UIAlertAction.actionWithTitle("OK", UIAlertActionStyleCancel, handler = null))
        presenter.presentViewController(alert, animated = true, completion = null)
    }

    private fun confirmDelete(name: String) {
        scope.launch {
            if (!IosMediaPlatform.confirm("Delete $name?", "The file is removed from this iPhone.", "Delete", "Cancel")) return@launch
            if (loadedPath == pathOf(name)) unload("")
            NSFileManager.defaultManager.removeItemAtPath(pathOf(name), null)
            if (preferences.getString(KEY_SELECTED, "") == name) preferences.putString(KEY_SELECTED, "")
            refreshUi("Deleted $name.")
        }
    }

    private fun setDownload(state: LocalModelDownloadUiState) {
        scope.launch { uiState = uiState.copy(download = state) }
    }

    private fun download(id: String) {
        val model = catalog.firstOrNull { it.id == id } ?: return
        if (downloadTask != null) return
        val url = NSURL.URLWithString(model.url) ?: return
        val destination = pathOf(model.fileName)
        setDownload(LocalModelDownloadUiState(isInFlight = true, message = "Downloading ${model.title}…", progressPercent = 0))
        val task = NSURLSession.sharedSession.downloadTaskWithURL(url) { location, response, error ->
            // The temporary file disappears when this handler returns, so move it here.
            val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            val message = when {
                error != null -> "Download stopped: ${error.localizedDescription}"
                location == null || status !in 200..299 -> "Download failed (HTTP $status)."
                else -> {
                    NSFileManager.defaultManager.removeItemAtPath(destination, null)
                    val moved = NSFileManager.defaultManager.moveItemAtURL(location, NSURL.fileURLWithPath(destination), null)
                    if (moved) "${model.title} installed." else "Could not save the model file."
                }
            }
            scope.launch {
                downloadJob?.cancel()
                downloadTask = null
                PlatformLogger.i(TAG, message)
                if (message.endsWith("installed.") && selectedName == null) preferences.putString(KEY_SELECTED, model.fileName)
                uiState = uiState.copy(download = LocalModelDownloadUiState(message = message))
                refreshUi()
            }
        }
        downloadTask = task
        task.resume()
        refreshUi()
        downloadJob = scope.launch {
            while (true) {
                delay(500)
                val expected = task.countOfBytesExpectedToReceive
                val received = task.countOfBytesReceived
                val percent = if (expected > 0) (received * 100 / expected).toInt() else null
                uiState = uiState.copy(
                    download = LocalModelDownloadUiState(
                        isInFlight = true,
                        message = "Downloading ${model.title}: ${formatSize(received)}" +
                            if (expected > 0) " of ${formatSize(expected)}" else "",
                        progressPercent = percent,
                    ),
                )
            }
        }
    }

    private fun importModel() {
        val presenter = IosMediaPlatform.topViewController() ?: return
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
        val delegate = ModelPickerDelegate { url ->
            pickerDelegate = null
            val source = url ?: return@ModelPickerDelegate
            val name = source.lastPathComponent ?: return@ModelPickerDelegate
            if (!isModelFile(name)) {
                NSFileManager.defaultManager.removeItemAtURL(source, null)
                refreshUi("$name is not a model. Pick a .gguf (llama.cpp) or .litertlm (LiteRT-LM) file.")
                return@ModelPickerDelegate
            }
            NSFileManager.defaultManager.removeItemAtPath(pathOf(name), null)
            val moved = NSFileManager.defaultManager.moveItemAtURL(source, NSURL.fileURLWithPath(pathOf(name)), null)
            if (moved) preferences.putString(KEY_SELECTED, name)
            refreshUi(if (moved) "Imported $name." else "Could not import $name.")
        }
        pickerDelegate = delegate // UIDocumentPickerViewController holds its delegate weakly.
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }
}

private class ModelPickerDelegate(
    private val onPicked: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onPicked(null)
}
