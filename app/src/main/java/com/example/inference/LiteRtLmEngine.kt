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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object LiteRtLmEngine {

    data class GenerationParams(
        val maxOutputTokens: Int,
        val temperature: Float,
        val topK: Int,
        val topP: Float
    )

    enum class HistoryRole { USER, ASSISTANT }
    data class HistoryTurn(val role: HistoryRole, val text: String)

    sealed class Result {
        data class Ok(
            val text: String,
            val thought: String?,
            val backendUsed: String,
            val latencyMs: Long,
            val kvCacheReused: Boolean = false
        ) : Result()

        sealed class Err : Result() {
            abstract val type: String
            abstract val message: String

            data class LoadError(override val message: String) : Err() {
                override val type = "load_error"
            }
            data class IncompleteWeights(override val message: String) : Err() {
                override val type = "incomplete_weights"
            }
            data class ExecutionError(override val message: String) : Err() {
                override val type = "execution_error"
            }
        }
    }

    private data class LoadedKey(
        val modelId: String,
        val backendName: String,
        val maxTokens: Int
    )

    private data class SamplerSig(
        val topK: Int,
        val topP: Double,
        val temperature: Double
    )

    private data class ResetSnapshot(
        val loadKey: LoadedKey,
        val systemInstruction: String?,
        val samplerSig: SamplerSig?,
        val completedTurns: List<HistoryTurn>
    )

    private val engineMutex = Mutex()

    @Volatile
    private var activeSnapshot: ResetSnapshot? = null

    @Volatile
    var isKvCacheReused: Boolean = false
        private set

    private var activeKey: LoadedKey? = null
    val activeBackendName: String
        get() = activeKey?.backendName ?: "unknown"

    private var activeEngine: Engine? = null
    private var activeConversation: Conversation? = null

    private fun pickBackend(context: Context, model: LocalModelInfo, npuOptIn: Boolean): Pair<Backend, String> {
        val rawAcc = model.accelerators.lowercase()
        return if (npuOptIn && rawAcc.contains("npu")) {
            val libDir = context.applicationInfo.nativeLibraryDir
            Pair(Backend.NPU(nativeLibraryDir = libDir), "npu")
        } else if (rawAcc.contains("gpu")) {
            Pair(Backend.GPU(), "gpu")
        } else {
            Pair(Backend.CPU(), "cpu")
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun ensureLoadedAndReset(
        context: Context,
        model: LocalModelInfo,
        params: GenerationParams,
        systemInstruction: String?,
        history: List<HistoryTurn>,
        npuOptIn: Boolean = false
    ): Result.Err? = withContext(Dispatchers.Default) {
        val (backend, backendName) = pickBackend(context, model, npuOptIn)
        val targetFile = model.getResolvedTargetFile(context)

        // Use standard file existence check
        val exists = try { targetFile.exists() && targetFile.length() > 0 } catch (tf: Throwable) { false }
        if (!exists) {
            return@withContext Result.Err.IncompleteWeights("Model file does not exist or is empty at: ${targetFile.absolutePath}")
        }

        val requiredKey = LoadedKey(model.modelId, backendName, params.maxOutputTokens)

        val samplerSig = if (backendName == "npu" || backendName == "tpu") {
            null
        } else {
            SamplerSig(
                topK = params.topK,
                topP = params.topP.toDouble(),
                temperature = params.temperature.toDouble()
            )
        }

        engineMutex.withLock {
            val currentSnapshot = activeSnapshot
            if (currentSnapshot != null &&
                activeEngine != null &&
                activeConversation != null &&
                activeKey == requiredKey &&
                currentSnapshot.loadKey == requiredKey &&
                currentSnapshot.systemInstruction == systemInstruction &&
                currentSnapshot.samplerSig == samplerSig &&
                currentSnapshot.completedTurns == history
            ) {
                Log.i("LiteRtLmEngine", "KV cache hit modelId=${model.modelId} (history=${history.size} turns)")
                isKvCacheReused = true
                return@withLock null
            }

            isKvCacheReused = false

            try {
                if (activeKey != requiredKey || activeEngine == null) {
                    closeInternal()

                    var supportsSpeculative = false
                    try {
                        Capabilities(targetFile.absolutePath).use { caps ->
                            supportsSpeculative = caps.hasSpeculativeDecodingSupport()
                        }
                    } catch (t: Throwable) {
                        Log.w("LiteRtLmEngine", "Speculative decoding probe failed, assuming unsupported", t)
                    }

                    ExperimentalFlags.enableSpeculativeDecoding = supportsSpeculative

                    val engineConfig = EngineConfig(
                        modelPath = targetFile.absolutePath,
                        backend = backend,
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = params.maxOutputTokens,
                        cacheDir = null
                    )

                    val engine = Engine(engineConfig)
                    engine.initialize()

                    ExperimentalFlags.enableSpeculativeDecoding = false

                    activeEngine = engine
                    activeKey = requiredKey
                }

                val engine = activeEngine ?: return@withLock Result.Err.LoadError("Engine failed to initialize.")

                val sampler = if (backendName == "npu" || backendName == "tpu") {
                    null
                } else {
                    SamplerConfig(
                        topK = params.topK,
                        topP = params.topP.toDouble(),
                        temperature = params.temperature.toDouble()
                    )
                }

                val systemContents = systemInstruction?.let { Contents.of(listOf(Content.Text(it))) }

                val initialMessages = history.map { turn ->
                    when (turn.role) {
                        HistoryRole.USER -> Message.user(turn.text)
                        HistoryRole.ASSISTANT -> Message.model(turn.text)
                    }
                }

                val conversation = engine.createConversation(ConversationConfig(
                    samplerConfig = sampler,
                    systemInstruction = systemContents,
                    tools = emptyList(),
                    initialMessages = initialMessages
                ))

                activeConversation = conversation
                activeSnapshot = ResetSnapshot(
                    loadKey = requiredKey,
                    systemInstruction = systemInstruction,
                    samplerSig = samplerSig,
                    completedTurns = history
                )
                null
            } catch (t: Throwable) {
                Log.e("LiteRtLmEngine", "Failed to load engine or initialize conversation", t)
                Result.Err.LoadError("Failed to load native engine for ${model.modelId}: ${t.localizedMessage}")
            }
        }
    }

    suspend fun generate(userText: String): Result = withContext(Dispatchers.Default) {
        val conversation = activeConversation ?: return@withContext Result.Err.ExecutionError("Conversation is not initialized.")
        val backendUsed = activeKey?.backendName ?: "unknown"
        val cacheReused = isKvCacheReused

        val startTime = System.currentTimeMillis()
        try {
            val reply: Message = conversation.sendMessage(
                Contents.of(listOf(Content.Text(userText)))
            )
            val replyText = reply.toString()
            val thinkingText = reply.channels["thought"]
            val latency = System.currentTimeMillis() - startTime

            val resultOk = Result.Ok(
                text = replyText,
                thought = thinkingText,
                backendUsed = backendUsed,
                latencyMs = latency,
                kvCacheReused = cacheReused
            )

            val priorSnapshot = activeSnapshot
            if (priorSnapshot != null) {
                val updatedTurns = priorSnapshot.completedTurns + listOf(
                    HistoryTurn(HistoryRole.USER, userText),
                    HistoryTurn(HistoryRole.ASSISTANT, replyText)
                )
                val newSnapshot = priorSnapshot.copy(completedTurns = updatedTurns)
                if (activeSnapshot == priorSnapshot) {
                    activeSnapshot = newSnapshot
                }
            }

            resultOk
        } catch (t: Throwable) {
            Log.e("LiteRtLmEngine", "Execution failed", t)
            activeSnapshot = null
            Result.Err.ExecutionError("Native execution failed: ${t.localizedMessage}")
        }
    }

    fun cancel() {
        try {
            activeConversation?.cancelProcess()
        } catch (t: Throwable) {
            Log.w("LiteRtLmEngine", "Failed to cancel process", t)
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun generateStreaming(
        userText: String,
        onDelta: (String) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        val conversation = activeConversation ?: return@withContext Result.Err.ExecutionError("Conversation is not initialized.")
        val backendUsed = activeKey?.backendName ?: "unknown"
        val cacheReused = isKvCacheReused

        val startTime = System.currentTimeMillis()
        var lastSentLength = 0
        var fullText = ""
        var thinkingText: String? = null

        try {
            val flow = conversation.sendMessageAsync(Contents.of(listOf(Content.Text(userText))))
            
            flow.collect { partialMessage ->
                val text = partialMessage.toString()
                fullText = text
                thinkingText = partialMessage.channels["thought"]
                if (text.length > lastSentLength) {
                    val delta = text.substring(lastSentLength)
                    lastSentLength = text.length
                    onDelta(delta)
                }
            }

            val latency = System.currentTimeMillis() - startTime
            val resultOk = Result.Ok(
                text = fullText,
                thought = thinkingText,
                backendUsed = backendUsed,
                latencyMs = latency,
                kvCacheReused = cacheReused
            )

            val priorSnapshot = activeSnapshot
            if (priorSnapshot != null) {
                val updatedTurns = priorSnapshot.completedTurns + listOf(
                    HistoryTurn(HistoryRole.USER, userText),
                    HistoryTurn(HistoryRole.ASSISTANT, fullText)
                )
                val newSnapshot = priorSnapshot.copy(completedTurns = updatedTurns)
                if (activeSnapshot == priorSnapshot) {
                    activeSnapshot = newSnapshot
                }
            }

            resultOk
        } catch (t: Throwable) {
            Log.e("LiteRtLmEngine", "Streaming execution failed", t)
            activeSnapshot = null
            Result.Err.ExecutionError("Native execution failed: ${t.localizedMessage}")
        }
    }

    private fun closeInternal() {
        try {
            activeConversation?.close()
        } catch (_: Throwable) {}
        try {
            activeEngine?.close()
        } catch (_: Throwable) {}
        activeConversation = null
        activeEngine = null
        activeKey = null
        activeSnapshot = null
    }

    suspend fun close() = withContext(Dispatchers.Default) {
        closeInternal()
    }
}
