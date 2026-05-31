package com.example.server

import android.content.Context
import android.util.Log
import com.example.data.GatewayLog
import com.example.data.GatewayRepository
import com.example.inference.InferenceEngine
import com.example.inference.InferenceParams
import com.example.inference.InferenceResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

class HttpGatewayServer(
    private val context: Context,
    private val repository: GatewayRepository,
    private val port: Int
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val status: StateFlow<ServerStatus> = _status

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount

    fun start() {
        if (_status.value == ServerStatus.RUNNING) {
            stop()
        }

        serverJob = serverScope.launch {
            try {
                _status.value = ServerStatus.STOPPED
                val socket = ServerSocket()
                socket.reuseAddress = true
                val settings = repository.getSettings()
                val socketAddress = if (settings.exposeToLan) {
                    java.net.InetSocketAddress(port)
                } else {
                    java.net.InetSocketAddress("127.0.0.1", port)
                }
                socket.bind(socketAddress)
                serverSocket = socket
                _status.value = ServerStatus.RUNNING
                LogUtility.logMessage("HttpGatewayServer", "Secure server started on port $port")

                while (isActive) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break // server socket closed
                    }
                    clientScope.launch {
                        try {
                            handleClientConnection(clientSocket)
                        } catch (t: Throwable) {
                            LogUtility.logError("HttpGatewayServerClient", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                LogUtility.logError("HttpGatewayServer", t)
                _status.value = ServerStatus.ERROR
                _errorCount.value += 1
            }
        }
    }

    fun stop() {
        LogUtility.logMessage("HttpGatewayServer", "Stopping secure server...")
        _status.value = ServerStatus.STOPPED
        val socketToClose = serverSocket
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        clientScope.coroutineContext.cancelChildren()

        if (socketToClose != null) {
            try {
                socketToClose.close()
                LogUtility.logMessage("HttpGatewayServer", "ServerSocket closed successfully.")
            } catch (e: Exception) {
                Log.e("HttpGatewayServer", "Error closing ServerSocket", e)
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        // Enforce 15 seconds read timeout to defend against slowloris attacks
        try {
            socket.soTimeout = 15000
        } catch (e: Exception) {
            Log.e("HttpGatewayServer", "Error setting socket timeout", e)
        }

        val bis = BufferedInputStream(socket.getInputStream())
        val writer = socket.getOutputStream().bufferedWriter()
        
        // Support Socket HTTP Keep-Alive
        var shouldKeepAlive = true
        var requestCount = 0

        while (shouldKeepAlive && !socket.isClosed && serverSocket != null) {
            requestCount++
            var statusCode = 200
            var modelUsed = "unknown"
            var requestText = ""
            var responseText = ""
            var path = ""
            var method = ""
            val startTime = System.currentTimeMillis()

            try {
                // Read start line
                val firstLine = readByteLine(bis)
                if (firstLine.isEmpty()) break
                
                val parts = firstLine.split(" ")
                if (parts.size < 3) break
                method = parts[0].uppercase().trim()
                path = parts[1].split("?")[0].trim()
                
                // Read headers
                var contentLength = 0
                var authorizationHeader = ""
                var clientKeySetting = ""
                var keepAliveHeader = false
                
                while (true) {
                    val line = readByteLine(bis)
                    if (line.isEmpty()) break
                    val upper = line.uppercase()
                    if (upper.startsWith("CONTENT-LENGTH:")) {
                        contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                    } else if (upper.startsWith("AUTHORIZATION:")) {
                        val authVal = line.substring(14).trim()
                        authorizationHeader = if (authVal.startsWith("Bearer ", ignoreCase = true)) {
                            authVal.substring(7).trim()
                        } else {
                            authVal
                        }
                    } else if (upper.startsWith("X-GEMINI-API-KEY:") || upper.startsWith("X-GEMINI-KEY:")) {
                        val colonIdx = line.indexOf(":")
                        if (colonIdx != -1) {
                            clientKeySetting = line.substring(colonIdx + 1).trim()
                        }
                    } else if (upper.startsWith("CONNECTION:")) {
                        keepAliveHeader = line.substring(11).trim().equals("keep-alive", ignoreCase = true)
                    }
                }

                shouldKeepAlive = keepAliveHeader && requestCount < 100 // Cap to prevent infinite loops

                // CORS/OPTIONS pre-flight handshake
                if (method == "OPTIONS") {
                    sendCorsPreflightResponse(writer)
                    recordLogEntry("OPTIONS", path, "CORS Pre-flight Handshake", "Success 204", 204, System.currentTimeMillis() - startTime, "n/a", null)
                    continue // proceed to next request on keep-alive or close
                }

                // Protect device RAM: enforce size limits (Max 50MB payload)
                val maxPayloadBytes = 50 * 1024 * 1024
                if (contentLength > maxPayloadBytes) {
                    val err = OpenAiToGeminiTranslator.wrapStandardError(413, "Content Too Large: Request body exceeds local limit of 50MB.")
                    sendHttpResponse(writer, 413, err, shouldKeepAlive)
                    recordLogEntry(method, path, "Large payload", err, 413, 0, "n/a", "Payload limit exceeded")
                    break
                }

                // Read request body safely matching Content-Length without truncating multi-byte UTF-8
                val bodyBytes = ByteArray(contentLength)
                var readBytes = 0
                while (readBytes < contentLength) {
                    val count = bis.read(bodyBytes, readBytes, contentLength - readBytes)
                    if (count == -1) break
                    readBytes += count
                }
                val requestBodyStr = String(bodyBytes, 0, readBytes, Charsets.UTF_8)

                // Validate API Endpoint (Only support OpenAI compatibility API endpoints)
                val isChat = path == "/v1/chat/completions" || path == "/v1/chat/completions/" || ((path == "/v1" || path == "/v1/") && method == "POST")
                val isModels = path == "/v1/models" || path == "/v1/models/"

                if (!isChat && !isModels) {
                    statusCode = 404
                    val errJson = OpenAiToGeminiTranslator.wrapStandardError(404, "Endpoint '$path' is not registered on this local AI gateway.")
                    sendHttpResponse(writer, 404, errJson, shouldKeepAlive)
                    recordLogEntry(method, path, "Path not found", errJson, 404, 0, "n/a", "Endpoint Not Found")
                    break
                }

                // Auth Gatekeeper Check
                val settings = repository.getSettings()
                val expectedToken = settings.gatewayAuthToken.orEmpty()
                if (expectedToken.isNotEmpty() && authorizationHeader != expectedToken) {
                    statusCode = 401
                    val errJson = OpenAiToGeminiTranslator.wrapStandardError(401, "Error: Unauthorized local gateway access. Invalid Bearer token.")
                    sendHttpResponse(writer, 401, errJson, shouldKeepAlive)
                    recordLogEntry(method, path, "Auth Validation Fail", errJson, 401, 0, "n/a", "Unauthorized Access Attempt")
                    break
                }

                // Upstream Key lookup (Separated from proxy Authorization tokens checking)
                val preferredApiKey = if (clientKeySetting.isNotEmpty() && !clientKeySetting.startsWith("YOUR") && clientKeySetting != "null") {
                    clientKeySetting
                } else if (!settings.geminiApiKey.isNullOrEmpty()) {
                    settings.geminiApiKey
                } else {
                    com.example.BuildConfig.GEMINI_API_KEY.orEmpty()
                }

                if (isModels) {
                    val json = OpenAiToGeminiTranslator.wrapModelsResponse()
                    sendHttpResponse(writer, 200, json, shouldKeepAlive)
                    recordLogEntry("GET", "/v1/models", "", "Retrieved listings", 200, System.currentTimeMillis() - startTime, "registry", null)
                    continue
                }

                // Handle Chat Completions POST
                val requestJson = JSONObject(requestBodyStr)
                val messages = requestJson.getJSONArray("messages")
                val rawModel = requestJson.getString("model")
                val stream = requestJson.optBoolean("stream", false)
                val temperature = requestJson.optDouble("temperature", 0.7).toFloat()
                val topP = requestJson.optDouble("top_p", 0.9).toFloat()

                val resolvedModelId = ModelRouter.resolveModelId(rawModel)
                modelUsed = resolvedModelId
                
                requestText = if (messages.length() > 0) {
                    messages.getJSONObject(messages.length() - 1).optString("content", "")
                } else ""

                // Get Engine from router based on resolvedModelId
                val engine: InferenceEngine = ModelRouter.getEngineForModel(resolvedModelId)
                val params = InferenceParams(
                    temperature = temperature,
                    topP = topP,
                    apiKey = preferredApiKey,
                    enableNpuBackend = settings.enableNpuBackend,
                    bypassGpu = settings.bypassGpu,
                    preferredBackend = settings.preferredBackend
                )

                val promptPayload = messages.toString()
                var tokensGenerated = 0

                if (stream) {
                    sendHttpSseStartedHeaders(writer)
                    
                    val streamResult = engine.generateStreaming(context, resolvedModelId, promptPayload, params) { chunk ->
                        val chunkSse = OpenAiToGeminiTranslator.wrapLocalSuccessChunk(chunk, resolvedModelId)
                        writer.write(chunkSse)
                        writer.flush()
                    }

                    if (streamResult is InferenceResult.Success) {
                        statusCode = 200
                        responseText = streamResult.text
                        tokensGenerated = streamResult.tokensGenerated
                        val finishChunk = OpenAiToGeminiTranslator.wrapLocalSuccessChunk("", resolvedModelId, "stop")
                        writer.write(finishChunk)
                        writer.write("data: [DONE]\n\n")
                        writer.flush()
                    } else if (streamResult is InferenceResult.Error) {
                        statusCode = when (streamResult) {
                            is InferenceResult.Error.Unauthorized -> 401
                            is InferenceResult.Error.IncompleteWeights -> 400
                            else -> 500
                        }
                        val errMsg = OpenAiToGeminiTranslator.wrapStandardError(statusCode, streamResult.message)
                        writer.write("data: $errMsg\n\n")
                        writer.flush()
                        responseText = streamResult.message
                    }
                } else {
                    val result = engine.generate(context, resolvedModelId, promptPayload, params)
                    if (result is InferenceResult.Success) {
                        statusCode = 200
                        responseText = result.text
                        tokensGenerated = result.tokensGenerated
                        
                        val payload = OpenAiToGeminiTranslator.wrapLocalSuccess(
                            result.text,
                            result.tokensGenerated,
                            resolvedModelId
                        )
                        sendHttpResponse(writer, 200, payload, shouldKeepAlive)
                    } else if (result is InferenceResult.Error) {
                        statusCode = when (result) {
                            is InferenceResult.Error.Unauthorized -> 401
                            is InferenceResult.Error.IncompleteWeights -> 400
                            else -> 500
                        }
                        val wrapErr = OpenAiToGeminiTranslator.wrapStandardError(statusCode, result.message)
                        sendHttpResponse(writer, statusCode, wrapErr, shouldKeepAlive)
                        responseText = result.message
                    }
                }

                val latency = System.currentTimeMillis() - startTime
                recordLogEntry(method, path, requestText, responseText, statusCode, latency, modelUsed, null, tokensGenerated)

            } catch (t: Throwable) {
                LogUtility.logError("HttpGatewayServerClient", t)
                statusCode = 500
                val errString = OpenAiToGeminiTranslator.wrapStandardError(500, "Server Error: ${t.localizedMessage}")
                try {
                    sendHttpResponse(writer, 500, errString, shouldKeepAlive)
                } catch (e: Exception) {}
                val latency = System.currentTimeMillis() - startTime
                recordLogEntry(method, path, requestText, errString, 500, latency, modelUsed, t.message, 0)
                break // breakout keep-alive on crash
            }
        }

        try {
            socket.close()
        } catch (e: Exception) {}
    }

    private fun readByteLine(bis: BufferedInputStream): String {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = bis.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                bos.write(b)
            }
        }
        return bos.toString("UTF-8")
    }

    private fun sendHttpResponse(writer: BufferedWriter, code: Int, payload: String, keepAlive: Boolean = false) {
        writer.write("HTTP/1.1 $code ${if (code == 200) "OK" else if (code == 204) "No Content" else "Error"}\r\n")
        writer.write("Content-Type: application/json; charset=UTF-8\r\n")
        writer.write("Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n") // Global CORS support
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.write("Access-Control-Allow-Headers: *\r\n")
        writer.write("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
        writer.write("\r\n")
        writer.write(payload)
        writer.flush()
    }

    private fun sendCorsPreflightResponse(writer: BufferedWriter) {
        writer.write("HTTP/1.1 204 No Content\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.write("Access-Control-Allow-Headers: *\r\n")
        writer.write("Access-Control-Max-Age: 86400\r\n")
        writer.write("Connection: keep-alive\r\n")
        writer.write("\r\n")
        writer.flush()
    }

    private fun sendHttpSseStartedHeaders(writer: BufferedWriter) {
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/event-stream; charset=UTF-8\r\n")
        writer.write("Cache-Control: no-cache\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.write("Access-Control-Allow-Headers: *\r\n")
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
        error: String?,
        tokens: Int = 0
    ) {
        val snippetReq = if (reqSnippet.length > 200) reqSnippet.substring(0, 197) + "..." else reqSnippet
        val snippetRes = if (resSnippet.length > 250) resSnippet.substring(0, 247) + "..." else resSnippet

        clientScope.launch {
            try {
                repository.insertLog(
                    GatewayLog(
                        method = method,
                        endpoint = endpoint,
                        requestSnippet = snippetReq,
                        responseSnippet = snippetRes,
                        statusCode = statusCode,
                        latencyMs = duration,
                        modelUsed = modelUsed,
                        errorMessage = error,
                        tokensCount = tokens
                    )
                )
            } catch (t: Throwable) {
                Log.e("HttpGatewayServer", "Failed to preserve gateway access log", t)
            }
        }
    }
}
