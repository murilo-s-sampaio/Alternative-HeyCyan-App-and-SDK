package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.ai.ChatAiService
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.ai.ChatResponse
import com.fersaiyan.cyanbridge.shared.ai.ImageAiService
import com.fersaiyan.cyanbridge.shared.ai.VoiceAiService
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTextField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelToggleField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsAction
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsConfigureUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsSection
import com.fersaiyan.cyanbridge.shared.localmodels.RemoteInferenceUiState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.preferredLanguages
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Speech.SFSpeechURLRecognitionRequest
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The "Local" AI provider on iOS (Android: LocalModelsConfigureActivity's remote
 * server card + RemoteOpenAiPrefs). On-device runtimes are Android-only, so iOS
 * points chat, image questions and plugins at an OpenAI-compatible server
 * (Ollama, LM Studio, llama.cpp, vLLM, OpenRouter...). The API key lives in the Keychain.
 */
object IosRemoteModelSettings {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEYCHAIN_API_KEY = "remote_openai_api_key"

    private val preferences = createPlatformPreferences("cyanbridge_remote_openai")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var isOpen by mutableStateOf(false)
        private set
    var uiState by mutableStateOf(LocalModelsConfigureUiState())
        private set

    val baseUrl: String get() = preferences.getString(KEY_BASE_URL, "").trim()
    val model: String get() = preferences.getString(KEY_MODEL, "").trim()
    val apiKey: String
        get() = IosSecurityRegistry.bridge?.keychainGet(KEYCHAIN_API_KEY)?.toKotlinBytes()?.decodeToString().orEmpty()

    /** True when chats should go to the configured server instead of the CyanBridge relay. */
    val isActive: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false) && baseUrl.isNotBlank() && model.isNotBlank()

    fun open() {
        uiState = savedState(status = "")
        isOpen = true
    }

    fun handle(action: LocalModelsAction) {
        when (action) {
            LocalModelsAction.Back, LocalModelsAction.DiscardChangesAndBack -> isOpen = false
            is LocalModelsAction.ToggleSection -> if (action.section == LocalModelsSection.REMOTE_SERVER) {
                uiState = uiState.copy(remoteServerExpanded = !uiState.remoteServerExpanded)
            }
            is LocalModelsAction.UpdateText -> editRemote {
                when (action.field) {
                    LocalModelTextField.REMOTE_BASE_URL -> it.copy(baseUrl = action.value)
                    LocalModelTextField.REMOTE_MODEL_NAME -> it.copy(modelName = action.value)
                    LocalModelTextField.REMOTE_API_KEY -> it.copy(apiKey = action.value)
                    else -> it
                }
            }
            is LocalModelsAction.SetToggle -> if (action.field == LocalModelToggleField.REMOTE_SERVER_ENABLED) {
                editRemote { it.copy(enabled = action.enabled) }
            }
            LocalModelsAction.TestRemoteServer -> {
                val draft = uiState.remoteServer
                setStatus("Testing ${draft.baseUrl.trim()}…")
                scope.launch { setStatus(IosRemoteOpenAiClient.healthCheck(draft.baseUrl, draft.apiKey)) }
            }
            LocalModelsAction.SaveRemoteServer -> save()
            else -> Unit
        }
    }

    private fun save() {
        val draft = uiState.remoteServer
        val problem = IosRemoteOpenAiClient.validate(draft.baseUrl, draft.apiKey)
            ?: if (draft.enabled && draft.modelName.isBlank()) "Enter the model name (for example llama3.2)." else null
        if (problem != null) {
            setStatus(problem)
            return
        }
        preferences.putBoolean(KEY_ENABLED, draft.enabled)
        preferences.putString(KEY_BASE_URL, draft.baseUrl.trim())
        preferences.putString(KEY_MODEL, draft.modelName.trim())
        IosSecurityRegistry.bridge?.let { bridge ->
            if (draft.apiKey.isBlank()) {
                bridge.keychainDelete(KEYCHAIN_API_KEY)
            } else {
                bridge.keychainSet(KEYCHAIN_API_KEY, draft.apiKey.encodeToByteArray().toNSData())
            }
        }
        PlatformLogger.i(TAG, "Remote model server saved (enabled=${draft.enabled}, model=${draft.modelName.trim()})")
        uiState = savedState(
            status = if (draft.enabled) "Saved. Chats, image questions and plugins now use this server." else "Saved (disabled).",
        )
    }

    private fun editRemote(change: (RemoteInferenceUiState) -> RemoteInferenceUiState) {
        uiState = uiState.copy(remoteServer = change(uiState.remoteServer), hasUnsavedChanges = true)
    }

    private fun setStatus(status: String) {
        uiState = uiState.copy(remoteServer = uiState.remoteServer.copy(status = status))
    }

    private fun savedState(status: String): LocalModelsConfigureUiState {
        val active = isActive
        return LocalModelsConfigureUiState(
            engineStatus = if (active) "Using $model" else "Using the CyanBridge relay",
            selectedModelStatus = if (active) {
                "Server: $baseUrl. Voice is transcribed on this iPhone."
            } else {
                "On-device models are not available on iOS yet. Turn on a remote OpenAI-compatible server " +
                    "below (Ollama, LM Studio, llama.cpp, OpenRouter...) to use your own model."
            },
            remoteServerExpanded = true,
            remoteServer = RemoteInferenceUiState(
                enabled = preferences.getBoolean(KEY_ENABLED, false),
                baseUrl = baseUrl,
                modelName = model,
                apiKey = apiKey,
                status = status,
            ),
        )
    }

    private const val TAG = "IosRemoteModel"
}

/** Minimal OpenAI chat-completions client (Android: RemoteOpenAiClient). */
internal object IosRemoteOpenAiClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = PlatformHttpClient()

    /** Returns a user-facing problem with the URL/key pair, or null when it is usable. */
    fun validate(baseUrl: String, apiKey: String): String? {
        val clean = baseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) return "Enter the server base URL (for example http://192.168.1.20:11434)."
        val scheme = clean.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return "The base URL must start with http:// or https://"
        val host = clean.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isBlank()) return "The base URL must include a host."
        if ('?' in clean || '#' in clean) return "The base URL must not include a query string or fragment."
        if (apiKey.isNotBlank() && scheme == "http" && !isPrivateHost(host)) {
            return "Refusing to send an API key over plain http to a public server. Use https://"
        }
        return null
    }

    fun chatCompletionsUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        val path = clean.substringAfter("://").substringAfter('/', "")
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") -> "$clean/chat/completions"
            path.isBlank() -> "$clean/v1/chat/completions"
            else -> "$clean/chat/completions"
        }
    }

    fun modelsUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        val path = clean.substringAfter("://").substringAfter('/', "")
        return when {
            clean.endsWith("/v1") -> "$clean/models"
            path.isBlank() -> "$clean/v1/models"
            else -> "$clean/models"
        }
    }

    suspend fun healthCheck(baseUrl: String, apiKey: String): String {
        validate(baseUrl, apiKey)?.let { return it }
        return runCatching {
            val response = httpClient.get(modelsUrl(baseUrl), authHeaders(apiKey))
            if (!response.isSuccessful) return "HTTP ${response.statusCode}: ${response.body.take(200)}"
            val models = runCatching {
                (json.parseToJsonElement(response.body).jsonObject["data"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            }.getOrNull().orEmpty()
            if (models.isEmpty()) "OK (server reachable)" else "OK (${models.size} models: ${models.take(5).joinToString()})"
        }.getOrElse { "Unreachable: ${it.message}" }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun chat(messages: List<ChatMessage>, image: ByteArray? = null, imageMimeType: String = "image/jpeg"): String {
        val baseUrl = IosRemoteModelSettings.baseUrl
        val apiKey = IosRemoteModelSettings.apiKey
        validate(baseUrl, apiKey)?.let { error(it) }
        val lastUser = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        val payload = buildJsonObject {
            put("model", IosRemoteModelSettings.model)
            put("stream", false)
            putJsonArray("messages") {
                messages.forEachIndexed { index, message ->
                    addJsonObject {
                        put("role", message.role.lowercase().ifBlank { "user" })
                        if (image != null && index == lastUser) {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", message.content)
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:$imageMimeType;base64,${Base64.encode(image)}")
                                    }
                                }
                            }
                        } else {
                            put("content", message.content)
                        }
                    }
                }
            }
        }
        val url = chatCompletionsUrl(baseUrl)
        PlatformLogger.i(TAG, "chat -> $url model=${IosRemoteModelSettings.model} image=${image != null}")
        val response = httpClient.post(
            url,
            payload.toString(),
            authHeaders(apiKey) + ("Content-Type" to "application/json; charset=UTF-8"),
        )
        check(response.isSuccessful) { "Server returned ${response.statusCode}: ${response.body.take(200)}" }
        val content = runCatching {
            val choice = (json.parseToJsonElement(response.body).jsonObject["choices"] as JsonArray).first().jsonObject
            choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return content?.trim()?.takeIf { it.isNotEmpty() } ?: error("The server returned an empty reply")
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || !h.contains('.')) return true
        val octets = h.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 100 && octets[1] in 64..127)
    }

    private const val TAG = "IosRemoteOpenAi"
}

/** Speech-to-text on the iPhone for recorded audio files (no relay account needed). */
@OptIn(ExperimentalForeignApi::class)
internal object IosOnDeviceTranscriber {
    suspend fun transcribe(audio: ByteArray, mimeType: String): String {
        if (audio.isEmpty() || !requestAuthorization()) return ""
        val extension = when {
            mimeType.contains("wav", ignoreCase = true) -> "wav"
            mimeType.contains("aac", ignoreCase = true) -> "aac"
            else -> "m4a"
        }
        val path = "${NSTemporaryDirectory()}cyanbridge-transcribe.$extension"
        if (!IosMediaPlatform.writeFile(path, audio)) return ""
        val language = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en-US"
        val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = language))
        if (!recognizer.isAvailable()) return ""
        val request = SFSpeechURLRecognitionRequest(uRL = NSURL.fileURLWithPath(path))
        if (recognizer.supportsOnDeviceRecognition) request.requiresOnDeviceRecognition = true
        val text = withTimeoutOrNull(120_000L) {
            suspendCancellableCoroutine { continuation ->
                val task = recognizer.recognitionTaskWithRequest(request) { result, error ->
                    if (!continuation.isActive) return@recognitionTaskWithRequest
                    when {
                        result?.isFinal() == true -> continuation.resume(result.bestTranscription.formattedString)
                        error != null -> {
                            PlatformLogger.w(TAG, "On-device transcription failed: ${error.localizedDescription}")
                            continuation.resume(result?.bestTranscription?.formattedString.orEmpty())
                        }
                    }
                }
                continuation.invokeOnCancellation { task.cancel() }
            }
        }.orEmpty()
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        return text.trim()
    }

    private suspend fun requestAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        SFSpeechRecognizer.requestAuthorization { status ->
            if (continuation.isActive) {
                continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }
    }

    private const val TAG = "IosTranscriber"
}

/** Routes chat to the configured server when the custom provider is on, else to the relay. */
internal class IosRoutedChatAiService(private val relay: ChatAiService) : ChatAiService {
    override suspend fun chat(messages: List<ChatMessage>, model: String?): ChatResponse {
        if (!IosRemoteModelSettings.isActive) return relay.chat(messages, model)
        val reply = runCatching { IosRemoteOpenAiClient.chat(messages) }
            .getOrElse { "Error: ${it.message ?: "remote server failed"}" }
        return ChatResponse(ChatMessage("assistant", reply))
    }
}

internal class IosRoutedImageAiService(private val relay: ImageAiService) : ImageAiService {
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String, mimeType: String): String {
        if (!IosRemoteModelSettings.isActive) return relay.analyzeImage(imageData, prompt, mimeType)
        return runCatching { IosRemoteOpenAiClient.chat(listOf(ChatMessage("user", prompt)), imageData, mimeType) }
            .getOrElse { "Error: ${it.message ?: "remote server failed"}" }
    }
}

/** Custom provider: transcribe on the iPhone. Relay: fall back to the iPhone when it fails. */
internal class IosRoutedVoiceAiService(private val relay: VoiceAiService) : VoiceAiService {
    override suspend fun transcribe(audioData: ByteArray, mimeType: String): String {
        if (IosRemoteModelSettings.isActive) return IosOnDeviceTranscriber.transcribe(audioData, mimeType)
        return relay.transcribe(audioData, mimeType).ifBlank { IosOnDeviceTranscriber.transcribe(audioData, mimeType) }
    }
}
