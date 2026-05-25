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
            val latencyMs: Long
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

    private var activeKey: LoadedKey? = null
    private var activeEngine: Engine? = null
    private var activeConversation: Conversation? = null

    private fun pickBackend(context: Context, model: LocalModelInfo): Pair<Backend, String> {
        val rawAcc = model.accelerators.lowercase()
        return if (rawAcc.contains("gpu")) {
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
        history: List<HistoryTurn>
    ): Result.Err? = withContext(Dispatchers.Default) {
        val (backend, backendName) = pickBackend(context, model)
        val targetFile = model.getResolvedTargetFile(context)

        // Use standard file existence check
        val exists = try { targetFile.exists() && targetFile.length() > 0 } catch (tf: Throwable) { false }
        if (!exists) {
            return@withContext Result.Err.IncompleteWeights("Model file does not exist or is empty at: ${targetFile.absolutePath}")
        }

        val requiredKey = LoadedKey(model.modelId, backendName, params.maxOutputTokens)

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

            val engine = activeEngine ?: return@withContext Result.Err.LoadError("Engine failed to initialize.")

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
            null
        } catch (t: Throwable) {
            Log.e("LiteRtLmEngine", "Failed to load engine or initialize conversation", t)
            Result.Err.LoadError("Failed to load native engine for ${model.modelId}: ${t.localizedMessage}")
        }
    }

    suspend fun generate(userText: String): Result = withContext(Dispatchers.Default) {
        val conversation = activeConversation ?: return@withContext Result.Err.ExecutionError("Conversation is not initialized.")
        val backendUsed = activeKey?.backendName ?: "unknown"

        val startTime = System.currentTimeMillis()
        try {
            val reply: Message = conversation.sendMessage(
                Contents.of(listOf(Content.Text(userText)))
            )
            val replyText = reply.toString()
            val thinkingText = reply.channels["thought"]
            val latency = System.currentTimeMillis() - startTime

            Result.Ok(
                text = replyText,
                thought = thinkingText,
                backendUsed = backendUsed,
                latencyMs = latency
            )
        } catch (t: Throwable) {
            Log.e("LiteRtLmEngine", "Execution failed", t)
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
    }

    suspend fun close() = withContext(Dispatchers.Default) {
        closeInternal()
    }
}
