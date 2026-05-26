package com.example.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * scaffold for Android AICore (Gemini Nano) inference engine.
 *
 * Calls MUST NOT infer that generate() will succeed from a true return of [isAvailable] —
 * system service state, weight provisioning, and Tensor SoC support are separate runtime gates
 * the real implementation will layer on top.
 *
 * To implement the real AICore SDK generation:
 * 1. Add the AICore SDK dependency to app/build.gradle.kts.
 * 2. Probe candidate classes can be configured to target canonical FQCNs.
 * 3. Replace generate() and generateStreaming() bodies with SDK's Session.generate() APIs.
 * 4. Add the appropriate <uses-feature android:name="android.software.aicore" android:required="false" /> in AndroidManifest.xml.
 */
object AiCoreEngine {

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

    private val PROBE_CLASSES = listOf(
        "com.google.android.gms.aicore.AICore",
        "com.google.android.gms.aicore.AiFeature",
        "com.google.android.gms.aicore.AiService",
        "com.google.ai.client.generativeai.AICoreClient"
    )

    private var cachedProbedClass: Class<*>? = null
    private var hasCheckedProbe = false

    /**
     * Probes for AICore SDK class names to verify if dependencies are compiled on classpath.
     */
    fun probeAiCoreClass(): Class<*>? {
        if (hasCheckedProbe) return cachedProbedClass
        for (className in PROBE_CLASSES) {
            try {
                val clazz = Class.forName(className)
                cachedProbedClass = clazz
                hasCheckedProbe = true
                return clazz
            } catch (_: ClassNotFoundException) {
            }
        }
        hasCheckedProbe = true
        return null
    }

    /**
     * Returns true if AICore SDK is available on the runtime classpath.
     * Callers must not assume the engine can successfully generate from this check alone.
     */
    fun isAvailable(context: Context): Boolean {
        return probeAiCoreClass() != null
    }

    suspend fun ensureLoadedAndReset(
        context: Context,
        modelId: String,
        params: GenerationParams,
        systemInstruction: String?,
        history: List<HistoryTurn>
    ): Result.Err? = withContext(Dispatchers.Default) {
        val clazz = probeAiCoreClass()
        if (clazz == null) {
            Result.Err.LoadError("AICore SDK not detected on runtime classpath. Please add the required dependencies to app/build.gradle.kts.")
        } else {
            Result.Err.LoadError("AICore SDK class found (${clazz.name}), but AICore Engine is not fully implemented yet.")
        }
    }

    suspend fun generate(userText: String): Result = withContext(Dispatchers.Default) {
        val clazz = probeAiCoreClass()
        if (clazz == null) {
            Result.Err.LoadError("AICore SDK not detected on runtime classpath. Please add the required dependencies to app/build.gradle.kts.")
        } else {
            Result.Err.ExecutionError("AICore SDK class found (${clazz.name}), but AICore Engine inference generation is not implemented yet.")
        }
    }

    suspend fun generateStreaming(
        userText: String,
        onDelta: (String) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        val clazz = probeAiCoreClass()
        if (clazz == null) {
            Result.Err.LoadError("AICore SDK not detected on runtime classpath. Please add the required dependencies to app/build.gradle.kts.")
        } else {
            Result.Err.ExecutionError("AICore SDK class found (${clazz.name}), but AICore Engine streaming inference generation is not implemented yet.")
        }
    }

    fun cancel() {
        // no-op scaffold
    }

    suspend fun close() = withContext(Dispatchers.Default) {
        // no-op scaffold
    }
}
