package com.fersaiyan.cyanbridge.shared.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun interface LocalModelResultCallback {
    fun onResult(success: Boolean, message: String)
}

fun interface LocalModelTokenCallback {
    fun onToken(text: String)
}

/**
 * On-device LLM runtimes implemented in Swift (CyanLocalModels: llama.cpp for .gguf,
 * LiteRT-LM for .litertlm). Kotlin owns storage, catalog and UI; Swift only runs models.
 */
interface LocalModelBridge {
    /** False on the simulator, where both runtimes run on the CPU. */
    fun gpuAvailable(): Boolean

    /** Loads [path], replacing any loaded model. The message is an error on failure. */
    fun load(path: String, useGpu: Boolean, contextTokens: Int, completion: LocalModelResultCallback)

    /**
     * [messagesJson] is a JSON array of {"role","content"}. [imagePath] (empty for none) attaches
     * an image to the last message. Tokens stream through [onToken]; the completion carries the
     * full reply or the error.
     */
    fun generate(
        messagesJson: String,
        systemPrompt: String,
        maxTokens: Int,
        imagePath: String,
        onToken: LocalModelTokenCallback,
        completion: LocalModelResultCallback,
    )

    fun cancel()

    fun unload()
}

object LocalModelRegistry {
    var bridge: LocalModelBridge? = null
}

internal suspend fun LocalModelBridge.awaitLoad(path: String, useGpu: Boolean, contextTokens: Int): Result<Unit> =
    suspendCancellableCoroutine { continuation ->
        load(path, useGpu, contextTokens) { success, message ->
            if (continuation.isActive) {
                continuation.resume(if (success) Result.success(Unit) else Result.failure(IllegalStateException(message)))
            }
        }
    }

internal suspend fun LocalModelBridge.awaitGenerate(
    messagesJson: String,
    systemPrompt: String,
    maxTokens: Int,
    imagePath: String = "",
    onToken: (String) -> Unit,
): Result<String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    generate(messagesJson, systemPrompt, maxTokens, imagePath, { onToken(it) }) { success, message ->
        if (continuation.isActive) {
            continuation.resume(if (success) Result.success(message) else Result.failure(IllegalStateException(message)))
        }
    }
}
