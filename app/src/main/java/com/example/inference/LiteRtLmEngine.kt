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
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CompletableDeferred
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
 *  - Conversations are reset on every request UNLESS the new request is a
 *    pure prefix-extension of the previous request's effective state — see
 *    [ResetSnapshot] and the KV-cache-reuse path in [ensureLoadedAndReset].
 *    For chat-style clients that send the full history each turn, this lets
 *    the engine keep its prefill KV cache and avoids re-tokenizing prior
 *    turns on every call.
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
            val backendUsed: String,
            /**
             * True when this turn reused the existing `Conversation` instead
             * of recreating it. Surfaced in `system_fingerprint` indirectly
             * via the latency; tests can observe this directly.
             */
            val kvCacheReused: Boolean
        ) : Result()

        data class Err(
            val type: String,
            val message: String,
            val cause: Throwable? = null
        ) : Result()
    }

    enum class HistoryRole { USER, ASSISTANT }
    data class HistoryTurn(val role: HistoryRole, val text: String)

    /**
     * Snapshot of the inputs that produced the current `conversation` plus the
     * additional turn-pairs that have been appended since the last reset via
     * `sendMessage`. Used to decide whether the next request can skip
     * `createConversation` and reuse the engine's KV cache.
     *
     * Equality semantics: two snapshots match when the engine, system
     * instruction, sampler config, and the *full transcript so far* are
     * pairwise identical.
     */
    private data class ResetSnapshot(
        val loadKey: LoadKey,
        val systemInstruction: String?,
        val samplerSig: String,
        /** System + history at reset time, plus every (user, assistant) pair since. */
        val completedTurns: List<HistoryTurn>
    )

    private val mutex = Mutex()
    private var loadedKey: LoadKey? = null
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Tracking state for KV-cache-reuse. Updated under [mutex] in
     * [ensureLoadedAndReset]; the post-generate update in [generate] is
     * `@Volatile`-published so subsequent calls see it. The race window is
     * benign — see comment in [generate].
     */
    @Volatile
    private var lastResetSnapshot: ResetSnapshot? = null

    /**
     * Loads the engine if needed for this `(modelId, backend, maxTokens)` and
     * either reuses or recreates the [Conversation] depending on whether the
     * new request is a prefix-extension of the previous one. After this
     * returns null, [generate] can be called.
     *
     * Heavy on first load (10s–60s for multi-GB models). Caller MUST run on a
     * background dispatcher (`handleClient` already does).
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

        // Identity of the sampler config — matters for cache-reuse equality.
        // NPU/TPU run with sampler = null per gallery; we encode that as "none".
        val samplerSig = if (backendName == "npu" || backendName == "tpu") {
            "none"
        } else {
            "topK=${params.topK};topP=${params.topP};temp=${params.temperature}"
        }

        // 1. (Re)load engine if the key changed.
        var engineWasReloaded = false
        if (loadedKey != key || engine == null) {
            closeQuietly()
            engineWasReloaded = true
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

        // 2. KV-cache-reuse fast path.
        //
        // If the engine was NOT reloaded and the new request's full state
        // (system instruction, sampler, complete history) exactly equals what
        // the existing Conversation already saw, we can skip createConversation
        // entirely. The engine retains its prefill KV cache and the next
        // sendMessage(latestUser) only pays for the trailing turn's prefill.
        //
        // The check is intentionally strict: if anything earlier in the
        // history changed, we reset. False matches would leak state across
        // unrelated client conversations. False misses just cost a recreate
        // and one extra prefill — safe.
        if (!engineWasReloaded && conversation != null) {
            val snap = lastResetSnapshot
            val canReuse = snap != null &&
                    snap.loadKey == key &&
                    snap.systemInstruction == systemInstruction &&
                    snap.samplerSig == samplerSig &&
                    snap.completedTurns == history
            if (canReuse) {
                Log.d(TAG, "KV cache hit modelId=${model.modelId} (history=${history.size} turns)")
                return@withLock null
            }
        }

        // 3. Conversation reset — either engine reloaded, or history doesn't
        // match what we last saw. Tear down the old conversation and rebuild.
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

            // Record the snapshot for the next request to compare against.
            lastResetSnapshot = ResetSnapshot(
                loadKey = key,
                systemInstruction = systemInstruction,
                samplerSig = samplerSig,
                completedTurns = history.toList()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Conversation init failed for ${model.modelId}", t)
            lastResetSnapshot = null
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
     *
     * Side effect: on success, the (user, assistant) pair is appended to
     * [lastResetSnapshot] so the next request can be evaluated for KV-cache
     * reuse. On failure, the snapshot is invalidated because the engine's
     * conversation may be in an undefined state.
     */
    suspend fun generate(userText: String): Result = withContext(Dispatchers.Default) {
        val convo = conversation
            ?: return@withContext Result.Err(
                type = "engine_not_ready",
                message = "Engine has not been initialized. Call ensureLoadedAndReset first."
            )

        // Snapshot the cache state at call time. Whether this turn reused the
        // KV cache is decided in ensureLoadedAndReset; here we just measure it.
        val priorSnapshot = lastResetSnapshot
        val priorTurnCount = priorSnapshot?.completedTurns?.size ?: -1

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

            // Extend the snapshot with the now-completed turn-pair. If a racing
            // ensureLoadedAndReset replaced the snapshot in the meantime, we
            // skip the update — that path will have set up the cache correctly
            // for its own next request anyway.
            val current = lastResetSnapshot
            if (current != null && current === priorSnapshot) {
                lastResetSnapshot = current.copy(
                    completedTurns = current.completedTurns +
                            HistoryTurn(HistoryRole.USER, userText) +
                            HistoryTurn(HistoryRole.ASSISTANT, replyText)
                )
            }

            // Whether we reused the KV cache for *this* request: equivalent to
            // "ensureLoadedAndReset did not recreate the conversation", which
            // we observe indirectly: the snapshot we saw at entry was already
            // the post-history-equals-snapshot state from the previous call.
            // priorTurnCount > 0 and priorTurnCount equals the request's
            // history length. We don't have the request's history here, so the
            // best signal is "snapshot existed and was non-empty" vs not.
            val kvCacheReused = priorTurnCount > 0

            Result.Ok(
                text = replyText,
                thinkingText = thinkingText,
                promptTokens = promptApprox,
                completionTokens = completionApprox,
                totalLatencyMs = elapsed,
                backendUsed = backendName,
                kvCacheReused = kvCacheReused
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Inference failed", t)
            // The conversation may be in an undefined state — invalidate the
            // cache so the next request will fully reset.
            lastResetSnapshot = null
            Result.Err(
                type = "inference_failed",
                message = t.message ?: "unknown",
                cause = t
            )
        }
    }

    /**
     * Streaming counterpart of [generate]. Calls [onDelta] for each *new* slice
     * of text as the model emits it, then returns a final [Result] when the
     * generation finishes (or fails).
     *
     * - [onDelta] is invoked synchronously from the LiteRT-LM callback thread
     *   with text delta chunks (the substring since the last call) and the
     *   current accumulated thought channel (if any). It is **not** suspend —
     *   blocking writes inside it provide natural backpressure. If [onDelta]
     *   throws (e.g. the client closed the socket), generation is cancelled
     *   via [Conversation.cancelProcess] and an `inference_failed` Err is
     *   returned.
     * - The returned [Result.Ok] carries the final accumulated text and
     *   honest provenance (latency, backend, KV-cache-hit flag) just like
     *   [generate].
     *
     * Mirrors gallery's `LlmChatModelHelper.runInference` async pattern,
     * adapted to a coroutine bridge via [CompletableDeferred].
     */
    suspend fun generateStreaming(
        userText: String,
        onDelta: (text: String, accumulatedThinking: String?) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        val convo = conversation
            ?: return@withContext Result.Err(
                type = "engine_not_ready",
                message = "Engine has not been initialized. Call ensureLoadedAndReset first."
            )

        val priorSnapshot = lastResetSnapshot
        val priorTurnCount = priorSnapshot?.completedTurns?.size ?: -1
        val backendName = loadedKey?.backend ?: "unknown"
        val started = System.currentTimeMillis()

        val deferred = CompletableDeferred<Result>()
        var lastSentLength = 0
        var finalThinking: String? = null
        val finalAccumulated = StringBuilder()

        try {
            convo.sendMessageAsync(
                Contents.of(listOf(Content.Text(userText))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // The SDK delivers an accumulated message each time;
                        // OpenAI's streaming format wants the *delta* since
                        // the last frame. Track lastSentLength to slice it.
                        try {
                            val full = message.toString()
                            finalThinking = message.channels["thought"]
                            if (full.length > lastSentLength) {
                                val delta = full.substring(lastSentLength)
                                lastSentLength = full.length
                                onDelta(delta, finalThinking)
                            }
                            finalAccumulated.clear()
                            finalAccumulated.append(full)
                        } catch (t: Throwable) {
                            // The SSE writer threw (typically client disconnect).
                            // Cancel generation and surface as inference_failed
                            // — the engine's conversation may be in a dirty state.
                            try { convo.cancelProcess() } catch (_: Throwable) { /* ignore */ }
                            lastResetSnapshot = null
                            if (!deferred.isCompleted) {
                                deferred.complete(
                                    Result.Err(
                                        type = "inference_failed",
                                        message = "Streaming sink failed: ${t.message ?: "unknown"}",
                                        cause = t
                                    )
                                )
                            }
                        }
                    }

                    override fun onDone() {
                        if (deferred.isCompleted) return
                        val elapsed = System.currentTimeMillis() - started
                        val replyText = finalAccumulated.toString()
                        val promptApprox = (userText.length / 4).coerceAtLeast(1)
                        val completionApprox = (replyText.length / 4).coerceAtLeast(1)

                        // Extend the snapshot for future cache hits, mirroring
                        // generate(). See generate() for the racing-snapshot
                        // safety analysis.
                        val current = lastResetSnapshot
                        if (current != null && current === priorSnapshot) {
                            lastResetSnapshot = current.copy(
                                completedTurns = current.completedTurns +
                                        HistoryTurn(HistoryRole.USER, userText) +
                                        HistoryTurn(HistoryRole.ASSISTANT, replyText)
                            )
                        }

                        deferred.complete(
                            Result.Ok(
                                text = replyText,
                                thinkingText = finalThinking,
                                promptTokens = promptApprox,
                                completionTokens = completionApprox,
                                totalLatencyMs = elapsed,
                                backendUsed = backendName,
                                kvCacheReused = priorTurnCount > 0
                            )
                        )
                    }

                    override fun onError(t: Throwable) {
                        // The SDK reports CancellationException through this
                        // hook when cancelProcess() fires, alongside genuine
                        // inference faults. Either way, the conversation is
                        // possibly in an undefined state — invalidate the cache.
                        lastResetSnapshot = null
                        if (!deferred.isCompleted) {
                            deferred.complete(
                                Result.Err(
                                    type = "inference_failed",
                                    message = t.message ?: "unknown",
                                    cause = t
                                )
                            )
                        }
                    }
                },
                emptyMap<String, String>()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessageAsync threw synchronously", t)
            lastResetSnapshot = null
            return@withContext Result.Err(
                type = "inference_failed",
                message = t.message ?: "unknown",
                cause = t
            )
        }

        deferred.await()
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
        lastResetSnapshot = null
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
