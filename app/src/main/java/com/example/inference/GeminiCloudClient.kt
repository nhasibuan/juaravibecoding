package com.example.inference

import android.content.Context
import android.util.Log
import com.example.server.ModelRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object GeminiCloudClient : InferenceEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun isAvailable(context: Context, modelId: String): Boolean {
        return true
    }

    override suspend fun generate(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams
    ): InferenceResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apiKey = params.apiKey.ifEmpty { com.example.BuildConfig.GEMINI_API_KEY.orEmpty() }
        
        if (apiKey.isEmpty() || apiKey.startsWith("YOUR_GEMINI_API_KEY")) {
            return@withContext InferenceResult.Error.Unauthorized(
                "Gemini Cloud API Key is empty or unset. Please configure it in the application settings."
            )
        }

        val payload = buildGeminiPayload(prompt, params)
        val realModelId = ModelRouter.mapToRealCloudModelId(modelId)
        
        // Secure design: Key goes into 'x-goog-api-key' header instead of URL queries
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$realModelId:generateContent"

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("x-goog-api-key", apiKey)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBodyStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errMsg = "Cloud API failed with HTTP $code: $responseBodyStr"
                    Log.e("GeminiCloudClient", errMsg)
                    return@withContext InferenceResult.Error.ExecutionError(errMsg)
                }

                val json = JSONObject(responseBodyStr)
                val textCandidate = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val latency = System.currentTimeMillis() - startTime
                val tokens = textCandidate.length / 4 + 5

                InferenceResult.Success(
                    text = textCandidate,
                    tokensGenerated = tokens,
                    latencyMs = latency,
                    modelUsed = modelId,
                    backend = "Google Cloud Server ($realModelId)"
                )
            }
        } catch (e: Exception) {
            val errMsg = "Cloud dynamic inference error: ${e.message}"
            Log.e("GeminiCloudClient", errMsg, e)
            com.example.server.LogUtility.logError("GeminiCloudClient", e)
            InferenceResult.Error.ExecutionError(errMsg)
        }
    }

    override suspend fun generateStreaming(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams,
        onChunk: suspend (String) -> Unit
    ): InferenceResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apiKey = params.apiKey.ifEmpty { com.example.BuildConfig.GEMINI_API_KEY.orEmpty() }

        if (apiKey.isEmpty() || apiKey.startsWith("YOUR_GEMINI_API_KEY")) {
            return@withContext InferenceResult.Error.Unauthorized(
                "Gemini Cloud API Key is missing. Check your settings."
            )
        }

        val payload = buildGeminiPayload(prompt, params)
        val realModelId = ModelRouter.mapToRealCloudModelId(modelId)
        
        // Pass alt=sse to retrieve fully spec-compliant server sent events (SSE) stream objects
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$realModelId:streamGenerateContent?alt=sse"

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("x-goog-api-key", apiKey)
            .build()

        val fullTextBuilder = StringBuilder()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (!response.isSuccessful) {
                    val responseBodyStr = response.body?.string() ?: "Empty error body"
                    val errMsg = "Cloud API streaming failed with HTTP $code: $responseBodyStr"
                    Log.e("GeminiCloudClient", errMsg)
                    return@withContext InferenceResult.Error.ExecutionError(errMsg)
                }

                val streamReader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@withContext InferenceResult.Error.ExecutionError("Null network stream response body")))
                var inputLine: String?
                
                while (streamReader.readLine().also { inputLine = it } != null) {
                    val line = inputLine!!.trim()
                    if (line.startsWith("data:")) {
                        val jsonStr = line.substring(5).trim()
                        if (jsonStr == "[DONE]") {
                            break
                        }
                        try {
                            val parsedChunk = JSONObject(jsonStr)
                            val candidatesArray = parsedChunk.optJSONArray("candidates")
                            if (candidatesArray != null && candidatesArray.length() > 0) {
                                val candidate = candidatesArray.getJSONObject(0)
                                val contentObj = candidate.optJSONObject("content")
                                if (contentObj != null) {
                                    val partsArray = contentObj.optJSONArray("parts")
                                    if (partsArray != null && partsArray.length() > 0) {
                                        val textPart = partsArray.getJSONObject(0).optString("text")
                                        if (textPart.isNotEmpty()) {
                                            fullTextBuilder.append(textPart)
                                            onChunk(textPart)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Suppress parse failures of intermediate lines
                        }
                    }
                }

                val latency = System.currentTimeMillis() - startTime
                val finalResponse = fullTextBuilder.toString()
                val tokens = finalResponse.length / 4 + 7

                InferenceResult.Success(
                    text = finalResponse,
                    tokensGenerated = tokens,
                    latencyMs = latency,
                    modelUsed = modelId,
                    backend = "Google Cloud Server Stream ($realModelId)"
                )
            }
        } catch (e: Exception) {
            val errMsg = "Cloud dynamic streaming inference error: ${e.message}"
            Log.e("GeminiCloudClient", errMsg, e)
            com.example.server.LogUtility.logError("GeminiCloudClient", e)
            InferenceResult.Error.ExecutionError(errMsg)
        }
    }

    private fun buildGeminiPayload(prompt: String, params: InferenceParams): JSONObject {
        val payload = JSONObject()
        val contentsArray = JSONArray()

        if (prompt.trim().startsWith("[") && prompt.trim().endsWith("]")) {
            try {
                val messages = JSONArray(prompt)
                for (i in 0 until messages.length()) {
                    val msgItem = messages.getJSONObject(i)
                    val role = msgItem.optString("role", "user")
                    val content = msgItem.optString("content", "")

                    val gRole = if (role.equals("assistant", ignoreCase = true)) "model" else "user"
                    contentsArray.put(JSONObject().apply {
                        put("role", gRole)
                        put("parts", JSONArray().put(JSONObject().put("text", content)))
                    })
                }
            } catch (e: Exception) {
                contentsArray.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                })
            }
        } else {
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            })
        }

        payload.put("contents", contentsArray)

        payload.put("generationConfig", JSONObject().apply {
            put("temperature", params.temperature)
            put("topP", params.topP)
            put("topK", params.topK)
        })

        return payload
    }
}
