package com.example.inference

import android.content.Context
import android.util.Log
import com.example.data.LocalModelInfo
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real on-device LiteRT-LM inference, mirroring the canonical initialization
 * pattern used by google-ai-edge/gallery's LlmChatModelHelper.
 *
 * Singleton: holds at most one loaded Engine + Conversation pair at a time,
 * keyed by (modelId, backend, maxTokens). Loading a multi-GB model twice
 * would OOM mid-tier devices, so we tear down the previous engine whenever
 * the key changes.
 *
 * Reference (canonical pattern, verified Nov 2026):
 * https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt
 *
 * Differences from gallery:
 *  - Synchronous request/response (uses Conversation.sendMessage), not streaming.
 *    Our gateway returns a single OpenAI-shaped JSON, not SSE.
 *  - The conversation is reset on every request to keep gateway semantics stateless;
 *    OpenAI clients always send the full message history themselves.
 *  - Multimodal inputs (image/audio) are not yet plumbed through; vision/audio
 *    backends are passed as null. Tracked as a follow-up.
 */
object LiteRtLmEngine {

    private const val TAG = "LiteRtLmEngine"

    /** Identity of the currently loaded engine — reload only when this changes. */
    data class LoadKey(val modelId: String, val backend: String, val maxTokens: Int)

    data class GenerationParams(
        val maxOutputTokens: Int = 1024,
        val temperature: Float = 1.0f,
        val topK: Int = 64,
        val topP: Float = 0.95f
    )

    sealed class Result {
        data class Ok(
            val text: String,
            val thinkingText: String?,
            val promptTokens: Int,
            val completionTokens: Int,
            val totalLatencyMs: Long,
            val backendUsed: String
        ) : Result()

        data class Err(
            val type: String,
            val message: String,
            val cause: Throwable? = null
        ) : Result()
    }

    enum class HistoryRole { USER, ASSISTANT }
    data class HistoryTurn(val role: HistoryRole, val text: String)

    private val mutex = Mutex()
    private var loadedKey: LoadKey? = null
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Loads the engine if needed for this `(modelId, backend, maxTokens)` and
     * (re)creates a Conversation seeded with the given system instruction and
     * history. After this returns null, [generate] can be called.
     *
     * Heavy: first-time load can take 10s–60s for multi-GB models. Caller
     * MUST run this on a background dispatcher (handleClient already does).
     *
     * @return null on success; an [Result.Err] on failure (caller maps to HTTP).
     */
    @OptIn(ExperimentalApi::class)
    suspend fun ensureLoadedAndReset(
        context: Context,
        model: LocalModelInfo,
        params: GenerationParams,
        systemInstruction: String?,
        history: List<HistoryTurn>,
        /**
         * Opt-in for the NPU backend. Default false keeps the conservative
         * "downgrade NPU to CPU" behavior. When true and the model's
         * accelerators include "npu", the engine attempts `Backend.NPU(...)`;
         * if the device lacks the vendor plug-in, `Engine.initialize()`
         * throws and we surface `engine_load_failed`.
         */
        npuOptIn: Boolean = false
    ): Result.Err? = mutex.withLock {
        val resolvedFile = model.getResolvedTargetFile(context)
        if (!resolvedFile.exists() || resolvedFile.length() <= 0L) {
            return Result.Err(
                type = "model_not_found",
                message = "LiteRT-LM weights for '${model.modelId}' are not on this device. " +
                        "Expected at: ${resolvedFile.absolutePath}"
            )
        }

        val backendName = pickBackend(model.accelerators, npuOptIn)
        val key = LoadKey(model.modelId, backendName, params.maxOutputTokens)

        // 1. (Re)load engine if the key changed.
        if (loadedKey != key || engine == null) {
            closeQuietly()
            try {
                val backend = buildBackend(context, backendName)

                // Capability probe — only used to decide whether to enable
                // speculative decoding for models that support it.
                var supportsSpeculative = false
                try {
                    Capabilities(resolvedFile.absolutePath).use {
                        supportsSpeculative = it.hasSpeculativeDecodingSupport()
                    }
                } catch (t: Throwable) {
                    // Ignore — assume not supported.
                }

                val engineConfig = EngineConfig(
                    modelPath = resolvedFile.absolutePath,
                    backend = backend,
                    visionBackend = null,   // text-only for v1
                    audioBackend = null,    // text-only for v1
                    maxNumTokens = params.maxOutputTokens.coerceAtLeast(256),
                    cacheDir = null         // weights live in app's external dir, not /data/local/tmp
                )

                ExperimentalFlags.enableSpeculativeDecoding = supportsSpeculative
                val newEngine = Engine(engineConfig)
                newEngine.initialize()
                ExperimentalFlags.enableSpeculativeDecoding = false

                engine = newEngine
                loadedKey = key
                Log.d(
                    TAG,
                    "Loaded engine modelId=${model.modelId} backend=$backendName " +
                            "maxTokens=${params.maxOutputTokens} speculative=$supportsSpeculative"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Engine load failed for ${model.modelId}", t)
                closeQuietly()
                return Result.Err(
                    type = "engine_load_failed",
                    message = "LiteRT-LM failed to load '${model.modelId}': ${t.message}",
                    cause = t
                )
            }
        }

        // 2. Reset conversation on every request (stateless gateway).
        try {
            try { conversation?.close() } catch (_: Throwable) { /* ignore */ }

            val systemContents = systemInstruction
                ?.takeIf { it.isNotBlank() }
                ?.let { Contents.of(listOf(Content.Text(it))) }

            val initialMessages: List<Message> = history.map { turn ->
                when (turn.role) {
                    HistoryRole.USER -> Message.user(turn.text)
                    HistoryRole.ASSISTANT -> Message.model(turn.text)
                }
            }

            // Per gallery: SamplerConfig must be null for NPU/TPU backends.
            val sampler = if (backendName == "npu" || backendName == "tpu") {
                null
            } else {
                SamplerConfig(
                    topK = params.topK,
                    topP = params.topP.toDouble(),
                    temperature = params.temperature.toDouble()
                )
            }

            conversation = engine!!.createConversation(
                ConversationConfig(
                    samplerConfig = sampler,
                    systemInstruction = systemContents,
                    tools = emptyList(),
                    initialMessages = initialMessages
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Conversation init failed for ${model.modelId}", t)
            return Result.Err(
                type = "conversation_init_failed",
                message = "Failed to start LiteRT-LM conversation: ${t.message}",
                cause = t
            )
        }

        null
    }

    /**
     * Sends a single user input synchronously and returns the model's reply.
     *
     * Uses [Conversation.sendMessage], the synchronous counterpart of
     * `sendMessageAsync`. Confirmed in gallery TinyGardenViewModel.kt:117.
     */
    suspend fun generate(userText: String): Result = withContext(Dispatchers.Default) {
        val convo = conversation
            ?: return@withContext Result.Err(
                type = "engine_not_ready",
                message = "Engine has not been initialized. Call ensureLoadedAndReset first."
            )

        val started = System.currentTimeMillis()
        try {
            val reply = convo.sendMessage(
                Contents.of(listOf(Content.Text(userText)))
            )
            val replyText = reply.toString()
            val thinkingText = reply.channels["thought"]
            val elapsed = System.currentTimeMillis() - started
            val backendName = loadedKey?.backend ?: "unknown"

            // The public Message API does not surface token counts; approximate
            // with a 4-chars-per-token heuristic so the OpenAI `usage` field is
            // populated. Gallery does not surface usage either.
            val promptApprox = (userText.length / 4).coerceAtLeast(1)
            val completionApprox = (replyText.length / 4).coerceAtLeast(1)

            Result.Ok(
                text = replyText,
                thinkingText = thinkingText,
                promptTokens = promptApprox,
                completionTokens = completionApprox,
                totalLatencyMs = elapsed,
                backendUsed = backendName
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Inference failed", t)
            Result.Err(
                type = "inference_failed",
                message = t.message ?: "unknown",
                cause = t
            )
        }
    }

    /** Cancels an in-flight generation, if any. Safe to call from any thread. */
    fun cancel() {
        try { conversation?.cancelProcess() } catch (_: Throwable) { /* ignore */ }
    }

    /** Releases all native resources. Should be called on server shutdown. */
    suspend fun close() = mutex.withLock { closeQuietly() }

    private fun closeQuietly() {
        try { conversation?.close() } catch (_: Throwable) { /* ignore */ }
        try { engine?.close() } catch (_: Throwable) { /* ignore */ }
        conversation = null
        engine = null
        loadedKey = null
    }

    /**
     * Picks a supported backend from a comma-separated accelerator list
     * (e.g. "gpu,cpu", "cpu", "npu").
     *
     * NPU requires `nativeLibraryDir` plus a vendor plug-in shipped per
     * device. Auto-preferring it would crash on the majority of phones, so
     * the default behavior downgrades any "npu" entry to "cpu" unless the
     * user has explicitly opted in via [ProxySetting.enableNpuBackend].
     */
    private fun pickBackend(acceleratorsCsv: String, npuOptIn: Boolean): String {
        val parts = acceleratorsCsv.split(',').map { it.trim().lowercase() }
        return when {
            npuOptIn && "npu" in parts -> "npu"
            "gpu" in parts -> "gpu"
            "cpu" in parts -> "cpu"
            "npu" in parts -> "cpu"  // listed but user hasn't opted in; safe downgrade
            else -> "cpu"
        }
    }

    private fun buildBackend(context: Context, backendName: String): Backend = when (backendName) {
        "gpu" -> Backend.GPU()
        "npu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
    }
}
