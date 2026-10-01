import Foundation
import LiteRTLM

/// .litertlm models through Google LiteRT-LM (Android: LiteRtLocalInferenceEngine).
final class LiteRtTextModel: LocalTextModel, @unchecked Sendable {
    let runtime = LocalModelRuntime.liteRtLm
    let supportsImages: Bool

    private let engine: Engine
    private let lock = NSLock()
    private var conversation: Conversation?

    private init(engine: Engine, supportsImages: Bool) {
        self.engine = engine
        self.supportsImages = supportsImages
    }

    /// Vision models (Gemma 4, Gemma 3n...) also get an image encoder; if that does not fit in
    /// memory the model is reloaded text-only.
    static func load(path: String, useGpu: Bool, contextTokens: Int) async throws -> LiteRtTextModel {
        let backend: Backend = useGpu ? .gpu : .cpu()
        let hasVision = Capabilities(modelPath: path)?.inputModalities.vision ?? false
        // The image encoder runs on the CPU: with a GPU vision executor on iOS, LiteRT-LM fails
        // to create conversations. Photos are occasional, so the CPU cost is acceptable.
        if hasVision, let engine = try? await makeEngine(path, backend, vision: .cpu(), contextTokens),
           await canConverse(engine) {
            return LiteRtTextModel(engine: engine, supportsImages: true)
        }
        do {
            let engine = try await makeEngine(path, backend, vision: nil, contextTokens)
            return LiteRtTextModel(engine: engine, supportsImages: false)
        } catch {
            throw LocalModelError.loadFailed(error.localizedDescription)
        }
    }

    /** Some backend combinations initialize but cannot open a conversation; detect that at load. */
    private static func canConverse(_ engine: Engine) async -> Bool {
        (try? await engine.createConversation(with: ConversationConfig())) != nil
    }

    private static func makeEngine(_ path: String, _ backend: Backend, vision: Backend?, _ contextTokens: Int) async throws -> Engine {
        let config = try EngineConfig(
            modelPath: path,
            backend: backend,
            visionBackend: vision,
            maxNumTokens: contextTokens,
            cacheDir: NSTemporaryDirectory()
        )
        let engine = Engine(engineConfig: config)
        try await engine.initialize()
        return engine
    }

    func cancel() {
        lock.lock()
        let active = conversation
        lock.unlock()
        try? active?.cancel()
    }

    func generate(
        messages: [LocalChatMessage],
        systemPrompt: String,
        maxTokens: Int,
        imagePath: String?,
        onToken: @escaping (String) -> Void
    ) async throws -> String {
        guard let last = messages.last else { return "" }
        if imagePath != nil && !supportsImages {
            throw LocalModelError.generationFailed("this model has no image support (or its image encoder did not fit in memory)")
        }
        // A fresh conversation per request, seeded with the earlier turns.
        let history = messages.dropLast().filter { $0.role != "system" }.map { turn in
            Message(turn.content, role: turn.role == "assistant" ? .model : .user)
        }
        let conversation: Conversation
        do {
            conversation = try await engine.createConversation(
                with: ConversationConfig(
                    systemMessage: systemPrompt.isEmpty ? nil : Message(systemPrompt, role: .system),
                    initialMessages: Array(history),
                    samplerConfig: try SamplerConfig(topK: 40, topP: 0.95, temperature: 0.7)
                )
            )
        } catch {
            throw LocalModelError.generationFailed(error.localizedDescription)
        }
        lock.lock()
        self.conversation = conversation
        lock.unlock()
        defer {
            lock.lock()
            self.conversation = nil
            lock.unlock()
        }

        var reply = ""
        do {
            for try await chunk in conversation.sendMessageStream(userMessage(last.content, imagePath), maxOutputTokens: maxTokens) {
                let text = chunk.toString
                guard !text.isEmpty else { continue }
                reply += text
                onToken(text)
            }
        } catch {
            if reply.isEmpty { throw LocalModelError.generationFailed(error.localizedDescription) }
        }
        return reply
    }

    private func userMessage(_ text: String, _ imagePath: String?) -> Message {
        guard let imagePath else { return Message(text) }
        return Message(of: .imageFile(imagePath), .text(text))
    }
}
