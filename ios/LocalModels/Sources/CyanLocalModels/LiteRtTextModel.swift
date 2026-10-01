import Foundation
import LiteRTLM

/// .litertlm models through Google LiteRT-LM (Android: LiteRtLocalInferenceEngine).
final class LiteRtTextModel: LocalTextModel, @unchecked Sendable {
    let runtime = LocalModelRuntime.liteRtLm

    private let engine: Engine
    private let lock = NSLock()
    private var conversation: Conversation?

    private init(engine: Engine) {
        self.engine = engine
    }

    static func load(path: String, useGpu: Bool, contextTokens: Int) async throws -> LiteRtTextModel {
        do {
            let config = try EngineConfig(
                modelPath: path,
                backend: useGpu ? .gpu : .cpu(),
                maxNumTokens: contextTokens,
                cacheDir: NSTemporaryDirectory()
            )
            let engine = Engine(engineConfig: config)
            try await engine.initialize()
            return LiteRtTextModel(engine: engine)
        } catch {
            throw LocalModelError.loadFailed(error.localizedDescription)
        }
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
        onToken: @escaping (String) -> Void
    ) async throws -> String {
        guard let last = messages.last else { return "" }
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
            for try await chunk in conversation.sendMessageStream(Message(last.content), maxOutputTokens: maxTokens) {
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
}
