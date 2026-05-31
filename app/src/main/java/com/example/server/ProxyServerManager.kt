package com.example.server

import android.content.Context
import android.util.Log
import com.example.data.GatewayLog
import com.example.data.GatewayRepository
import com.example.inference.LiteRtLmEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class ServerStatus {
    STOPPED, RUNNING, ERROR
}

object ProxyServerManager {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val status: StateFlow<ServerStatus> = _status

    private val _activePort = MutableStateFlow(8080)
    val activePort: StateFlow<Int> = _activePort

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount

    private var repository: GatewayRepository? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun initialize(repo: GatewayRepository) {
        this.repository = repo
    }

    fun startServer(cpuPort: Int) {
        if (_status.value == ServerStatus.RUNNING) {
            stopServer()
        }

        _activePort.value = cpuPort
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // By creating an unbound ServerSocket and setting reuseAddress=true,
                // we bypass SO_REUSEADDR port locks (TIME_WAIT) on rapid app restarts
                val sSocket = ServerSocket()
                sSocket.reuseAddress = true
                sSocket.bind(java.net.InetSocketAddress(cpuPort))
                serverSocket = sSocket
                _status.value = ServerStatus.RUNNING
                Log.i("ProxyServerManager", "Gateway hosting on port $cpuPort")

                while (isActive) {
                    val clientSocket = try {
                        sSocket.accept()
                    } catch (e: Exception) {
                        break // socket closed
                    }
                    clientScope.launch {
                        try {
                            handleClient(clientSocket)
                        } catch (e: Throwable) {
                            Log.e("ProxyServerManager", "Error handling client session", e)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("ProxyServerManager", "Failed to start socket server on port $cpuPort", t)
                _status.value = ServerStatus.ERROR
                _errorCount.value += 1
            }
        }
    }

    fun stopServer() {
        Log.i("ProxyServerManager", "Stopping server...")
        _status.value = ServerStatus.STOPPED
        val socketToClose = serverSocket
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        
        if (socketToClose != null) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                try {
                    socketToClose.close()
                    Log.i("ProxyServerManager", "ServerSocket closed synchronously.")
                } catch (e: Exception) {
                    Log.e("ProxyServerManager", "Error closing ServerSocket synchronously", e)
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        socketToClose.close()
                        Log.i("ProxyServerManager", "ServerSocket closed successfully on I/O dispatcher.")
                    } catch (e: Exception) {
                        Log.e("ProxyServerManager", "Error closing ServerSocket", e)
                    }
                }
            }
        }
    }

    suspend fun rebootServer(newPort: Int) = withContext(Dispatchers.IO) {
        stopServer()
        try {
            delay(150)
        } catch (e: Exception) {}
        startServer(newPort)
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        var statusCode = 200
        var modelUsed = "unknown"
        var requestText = ""
        var responseText = ""
        val startTime = System.currentTimeMillis()
        var path = ""
        var method = ""

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = socket.getOutputStream().bufferedWriter()

            // Read start line
            val firstLine = reader.readLine() ?: return@withContext
            val parts = firstLine.split(" ")
            if (parts.size < 3) return@withContext
            method = parts[0]
            path = parts[1].split("?")[0]

            val isChat = path == "/v1/chat/completions" || path == "/v1/chat/completions/" || ((path == "/v1" || path == "/v1/") && method == "POST")
            val isModels = path == "/v1/models" || path == "/v1/models/"

            if (!isChat && !isModels) {
                statusCode = 404
                val errJson = OpenAiToGeminiTranslator.wrapStandardError(404, "Endpoint $path not found on Android local proxy gateway.")
                sendHttpResponse(writer, 404, errJson)
                recordLogEntry(method, path, "Path not found", errJson, 404, 0, "n/a", "Not Found Exception")
                return@withContext
            }

            // Read headers
            var contentLength = 0
            var clientKey = ""
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val upper = line!!.uppercase()
                if (upper.startsWith("CONTENT-LENGTH:")) {
                    contentLength = line!!.substring(15).trim().toIntOrNull() ?: 0
                } else if (upper.startsWith("AUTHORIZATION:")) {
                    clientKey = line!!.substring(14).trim().replace("Bearer ", "")
                }
            }

            // Read post body
            val bodyBuilder = java.lang.StringBuilder()
            if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val chunk = reader.read(buffer, read, contentLength - read)
                    if (chunk == -1) break
                    read += chunk
                }
                bodyBuilder.append(buffer)
            }
            val requestBodyStr = bodyBuilder.toString()

            if (isModels) {
                val json = OpenAiToGeminiTranslator.wrapModelsResponse()
                sendHttpResponse(writer, 200, json)
                recordLogEntry("GET", "/v1/models", "", "Retrieved listings", 200, System.currentTimeMillis() - startTime, "registry", null)
                return@withContext
            }

            // Handle OpenAI Chat completions
            val requestJson = JSONObject(requestBodyStr)
            val messages = requestJson.getJSONArray("messages")
            val rawModel = requestJson.getString("model")
            val stream = requestJson.optBoolean("stream", false)
            val temperature = requestJson.optDouble("temperature", 0.7).toFloat()
            val topP = requestJson.optDouble("top_p", 0.9).toFloat()

            modelUsed = ModelRouter.resolveModelId(rawModel)
            requestText = if (messages.length() > 0) {
                messages.getJSONObject(messages.length() - 1).optString("content", "")
            } else ""

            val settings = repository?.getSettings()
            val preferredApiKey = if (clientKey.isNotEmpty() && !clientKey.startsWith("YOUR_GEMINI") && clientKey != "null") {
                clientKey
            } else if (settings?.geminiApiKey?.isNotEmpty() == true) {
                settings.geminiApiKey
            } else {
                com.example.BuildConfig.GEMINI_API_KEY.orEmpty()
            }

            if (ModelRouter.isLocalModel(modelUsed)) {
                // Initialize local LiteRT engine
                val err = LiteRtLmEngine.ensureLoadedAndReset(
                    modelUsed,
                    LiteRtLmEngine.GenerationParams(temperature, topP, 40)
                )
                if (err != null) {
                    val (status, wrapErr) = OpenAiToGeminiTranslator.wrapLocalError(err, modelUsed)
                    statusCode = status
                    sendHttpResponse(writer, status, wrapErr)
                    recordLogEntry(method, path, requestText, wrapErr, status, System.currentTimeMillis() - startTime, modelUsed, "Engine Load Failed")
                    return@withContext
                }

                if (stream) {
                    sendHttpSseStartedHeaders(writer)
                    val result = LiteRtLmEngine.generateStreaming(requestText) { chunkText ->
                        val chunkSse = OpenAiToGeminiTranslator.wrapLocalSuccessChunk(chunkText, modelUsed)
                        writer.write(chunkSse)
                        writer.flush()
                    }
                    val stopSse = OpenAiToGeminiTranslator.wrapLocalSuccessChunk("", modelUsed, "stop")
                    writer.write(stopSse)
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                    
                    if (result is LiteRtLmEngine.Result.Ok) {
                        responseText = result.text
                    }
                } else {
                    val result = LiteRtLmEngine.generate(requestText)
                    if (result is LiteRtLmEngine.Result.Ok) {
                        statusCode = 200
                        responseText = result.text
                        val payload = OpenAiToGeminiTranslator.wrapLocalSuccess(result, modelUsed)
                        sendHttpResponse(writer, 200, payload)
                    } else if (result is LiteRtLmEngine.Result.Err) {
                        val (status, wrapErr) = OpenAiToGeminiTranslator.wrapLocalError(result, modelUsed)
                        statusCode = status
                        sendHttpResponse(writer, status, wrapErr)
                        responseText = wrapErr
                    }
                }
            } else {
                // Rout to Cloud Gemini REST
                if (preferredApiKey.isEmpty() || preferredApiKey.startsWith("YOUR_GEMINI_API_KEY")) {
                    statusCode = 401
                    val errJson = OpenAiToGeminiTranslator.wrapStandardError(401, "Google AI Studio API Key is empty or unset. Please configure it in your Secrets panel inside AI Studio.")
                    sendHttpResponse(writer, 401, errJson)
                    recordLogEntry(method, path, requestText, errJson, 401, System.currentTimeMillis() - startTime, modelUsed, "Missing API Key")
                    return@withContext
                }

                proxyToGeminiCloud(writer, messages, modelUsed, stream, preferredApiKey, temperature, topP) { code, text ->
                    statusCode = code
                    responseText = text
                }
            }

            val latency = System.currentTimeMillis() - startTime
            recordLogEntry(method, path, requestText, responseText, statusCode, latency, modelUsed, null)

        } catch (t: Throwable) {
            Log.e("ProxyServerManager", "Unhandled error in handler thread", t)
            statusCode = 500
            val errString = OpenAiToGeminiTranslator.wrapStandardError(500, "Server Internal Exception: ${t.localizedMessage}")
            try {
                val writer = socket.getOutputStream().bufferedWriter()
                sendHttpResponse(writer, 500, errString)
            } catch (e: Exception) {}
            recordLogEntry("POST", path, "System Error", errString, 500, System.currentTimeMillis() - startTime, "error", t.message)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
        }
    }

    private suspend fun proxyToGeminiCloud(
        writer: java.io.BufferedWriter,
        messages: JSONArray,
        model: String,
        stream: Boolean,
        apiKey: String,
        temp: Float,
        topP: Float,
        onFinished: (Int, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        
        // Translate roles to Gemini model format
        val contentsArray = JSONArray()
        for (i in 0 until messages.length()) {
            val msgItem = messages.getJSONObject(i)
            val role = msgItem.optString("role", "user")
            val content = msgItem.optString("content", "")

            val gRole = if (role.equals("assistant", ignoreCase = true)) "model" else "user"
            
            val contentObj = JSONObject()
            contentObj.put("role", gRole)
            
            val partObj = JSONObject()
            partObj.put("text", content)
            val partsArray = JSONArray().apply { put(partObj) }
            
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
        }

        val geminiPayload = JSONObject()
        geminiPayload.put("contents", contentsArray)

        // Config block
        val config = JSONObject()
        config.put("temperature", temp)
        config.put("topP", topP)
        geminiPayload.put("generationConfig", config)

        val suffix = if (stream) "streamGenerateContent" else "generateContent"
        val requestUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:$suffix?key=$apiKey"

        val body = geminiPayload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(requestUrl).post(body).build()

        try {
            val networkResponse = okHttpClient.newCall(req).execute()
            val code = networkResponse.code

            if (!networkResponse.isSuccessful) {
                val errTxt = networkResponse.body?.string() ?: "Unknown Cloud API error"
                val openaiErr = OpenAiToGeminiTranslator.wrapStandardError(code, "Google Cloud AI API rejected task: $errTxt")
                sendHttpResponse(writer, code, openaiErr)
                onFinished(code, openaiErr)
                return@withContext
            }

            if (stream) {
                sendHttpSseStartedHeaders(writer)
                val streamReader = BufferedReader(InputStreamReader(networkResponse.body?.byteStream() ?: return@withContext))
                val responseAccumulator = java.lang.StringBuilder()
                var inputLine: String?
                
                while (streamReader.readLine().also { inputLine = it } != null) {
                    if (inputLine!!.trim().startsWith("[")) continue
                    if (inputLine!!.trim().startsWith("]")) continue
                    
                    var lineClean = inputLine!!.trim()
                    if (lineClean.endsWith(",")) {
                        lineClean = lineClean.substring(0, lineClean.length - 1)
                    }

                    if (lineClean.startsWith("{")) {
                        try {
                            val parsedChunk = JSONObject(lineClean)
                            val candidate = parsedChunk.getJSONArray("candidates").getJSONObject(0)
                            val textPart = candidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text")
                            
                            if (textPart.isNotEmpty()) {
                                responseAccumulator.append(textPart)
                                val openaiChunk = OpenAiToGeminiTranslator.wrapLocalSuccessChunk(textPart, model)
                                writer.write(openaiChunk)
                                writer.flush()
                            }
                        } catch (e: Exception) {}
                    }
                }

                // finalize
                val endChunk = OpenAiToGeminiTranslator.wrapLocalSuccessChunk("", model, "stop")
                writer.write(endChunk)
                writer.write("data: [DONE]\n\n")
                writer.flush()
                onFinished(200, responseAccumulator.toString())
            } else {
                val resString = networkResponse.body?.string() ?: ""
                val resJson = JSONObject(resString)
                val textCandidate = resJson.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                // wrap into OpenAI response structure
                val wrapped = OpenAiToGeminiTranslator.wrapLocalSuccess(
                    LiteRtLmEngine.Result.Ok(textCandidate, textCandidate.length / 4, 100),
                    model
                )
                sendHttpResponse(writer, 200, wrapped)
                onFinished(200, textCandidate)
            }
        } catch (e: Exception) {
            val errorJson = OpenAiToGeminiTranslator.wrapStandardError(500, "Failed to proxy cloud transaction: ${e.message}")
            sendHttpResponse(writer, 500, errorJson)
            onFinished(500, errorJson)
        }
    }

    private fun sendHttpResponse(writer: java.io.BufferedWriter, code: Int, payload: String) {
        writer.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Content-Length: ${payload.toByteArray().size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(payload)
        writer.flush()
    }

    private fun sendHttpSseStartedHeaders(writer: java.io.BufferedWriter) {
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/event-stream\r\n")
        writer.write("Cache-Control: no-cache\r\n")
        writer.write("Connection: keep-alive\r\n")
        writer.write("\r\n")
        writer.flush()
    }

    private fun recordLogEntry(
        method: String,
        endpoint: String,
        reqSnippet: String,
        resSnippet: String,
        statusCode: Int,
        duration: Long,
        modelUsed: String,
        error: String?
    ) {
        val snippetReq = if (reqSnippet.length > 100) reqSnippet.substring(0, 97) + "..." else reqSnippet
        val snippetRes = if (resSnippet.length > 120) resSnippet.substring(0, 117) + "..." else resSnippet

        clientScope.launch {
            try {
                repository?.insertLog(
                    GatewayLog(
                        method = method,
                        endpoint = endpoint,
                        requestSnippet = snippetReq,
                        responseSnippet = snippetRes,
                        statusCode = statusCode,
                        latencyMs = duration,
                        modelUsed = modelUsed,
                        errorMessage = error
                    )
                )
            } catch (t: Throwable) {
                Log.e("ProxyServerManager", "Failed to write audit trace log to database", t)
            }
        }
    }
}
