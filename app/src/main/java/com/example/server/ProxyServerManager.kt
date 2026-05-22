package com.example.server

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.GatewayLog
import com.example.data.GatewayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

class ProxyServerManager(
    private val context: Context,
    private val repository: GatewayRepository
) {
    private var serverSocket: ServerSocket? = null
    private val serverScope = CoroutineScope(Dispatchers.IO)
    private val serverMutex = Mutex()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort = _serverPort.asStateFlow()

    // Flag to keep the loop active
    @Volatile
    private var isRunningLoop = false

    fun getLocalIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val host = address.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ProxyServerManager", "Failed to resolve IP address", e)
        }
        return "127.0.0.1"
    }

    fun startServer(port: Int) {
        _serverPort.value = port
        isRunningLoop = true

        serverScope.launch {
            serverMutex.withLock {
                if (_isServerRunning.value) return@withLock

                try {
                    val sSocket = ServerSocket(port)
                    serverSocket = sSocket
                    _isServerRunning.value = true
                    Log.d("ProxyServerManager", "Socket Server started successfully on port $port")

                    // Run the accept loop in a separate coroutine so we do not block the Mutex lock
                    launch {
                        try {
                            while (isRunningLoop) {
                                val clientSocket = try {
                                    sSocket.accept()
                                } catch (e: Exception) {
                                    // socket closed or stopped
                                    break
                                }
                                launch {
                                    handleClient(clientSocket)
                                }
                            }
                        } finally {
                            serverMutex.withLock {
                                _isServerRunning.value = false
                                if (serverSocket == sSocket) {
                                    serverSocket = null
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ProxyServerManager", "Failed to start socket server on port $port", e)
                    _isServerRunning.value = false
                    isRunningLoop = false
                    
                    try {
                        // Write internal error to logs to alert user
                        repository.insertLog(
                            GatewayLog(
                                method = "SYSTEM",
                                path = "START",
                                requestModel = "NONE",
                                clientIp = "127.0.0.1",
                                status = 500,
                                durationMs = 0,
                                responsePreview = "Failed to start server on port $port: ${e.localizedMessage}",
                                isAuthorized = true
                            )
                        )
                    } catch (dbEx: Exception) {
                        Log.e("ProxyServerManager", "Database write failed in start exception handler", dbEx)
                    }
                }
            }
        }
    }

    fun stopServer() {
        isRunningLoop = false
        serverScope.launch {
            serverMutex.withLock {
                if (!_isServerRunning.value && serverSocket == null) return@withLock
                try {
                    serverSocket?.close()
                    serverSocket = null
                    _isServerRunning.value = false
                    Log.d("ProxyServerManager", "Socket Server stopped successfully")
                } catch (e: Exception) {
                    Log.e("ProxyServerManager", "Error stopping socket server", e)
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                // Set reasonable client timeouts to prevent un-recycled blocking threads
                socket.soTimeout = 15000
                socket.use { client ->
                    val clientIp = client.inetAddress?.hostAddress ?: "unknown"
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val outputStream = client.getOutputStream()

                    // 1. Read HTTP request line
                    val firstLine = reader.readLine()
                    if (firstLine == null || firstLine.trim().isEmpty()) {
                        return@withContext
                    }
                    val requestParts = firstLine.split(" ")
                    if (requestParts.size < 2) {
                        sendErrorResponse(outputStream, 400, "Bad Request: Invalid Request Line")
                        return@withContext
                    }

                    val method = requestParts[0].uppercase(Locale.US)
                    val path = requestParts[1]

                    Log.d("ProxyServerManager", "Inward client request: $method $path from $clientIp")

                    // 2. Read headers with timeout limits
                    var contentLength = 0
                    var authorizationHeader = ""
                    var line: String? = reader.readLine()
                    while (line != null && line.isNotEmpty()) {
                        val lowercaseLine = line.lowercase(Locale.US)
                        if (lowercaseLine.startsWith("content-length:")) {
                            contentLength = line.substring("content-length:".length).trim().toIntOrNull() ?: 0
                        } else if (lowercaseLine.startsWith("authorization:")) {
                            authorizationHeader = line.substring("authorization:".length).trim()
                        }
                        line = reader.readLine()
                    }

                    // 3. Handle OPTIONS (CORS preflight)
                    if (method == "OPTIONS") {
                        sendOptionsSuccess(outputStream)
                        return@withContext
                    }

                    // 4. Validate Endpoint path and request method
                    if (method != "POST" || path != "/v1/chat/completions") {
                        val errorResponse = "{\"error\": {\"message\": \"Gateway only proxies POST /v1/chat/completions requests\", \"type\": \"invalid_request_error\"}}"
                        sendJsonResponse(outputStream, 404, errorResponse)
                        return@withContext
                    }

                    val startTime = System.currentTimeMillis()
                    var requestModel = "unknown-model"
                    var httpStatus = 200
                    var outputResponseText = ""
                    var authorized = true

                    try {
                        // Get active settings holding proxy api check configs
                        val settings = repository.getSettingsDirect()
                        val systemApiKey = settings?.proxyApiKey ?: ""

                        // 1. API Key Authorization Checks
                        if (systemApiKey.isNotEmpty()) {
                            val expectedToken = "Bearer $systemApiKey"
                            if (authorizationHeader != expectedToken) {
                                authorized = false
                                httpStatus = 401
                                val errorResponse = "{\"error\": {\"message\": \"Unauthorized: Invalid Gateway API check\", \"type\": \"invalid_api_key\"}}"
                                sendJsonResponse(outputStream, 401, errorResponse)

                                try {
                                    repository.insertLog(
                                        GatewayLog(
                                            method = method,
                                            path = path,
                                            requestModel = "Blocked Authentication",
                                            clientIp = clientIp,
                                            status = 401,
                                            durationMs = 0,
                                            responsePreview = "Blocked call: Authorization mismatched key.",
                                            isAuthorized = false
                                        )
                                    )
                                } catch (dbEx: Exception) {
                                    Log.e("ProxyServerManager", "DB logging failed for auth mismatch", dbEx)
                                }
                                return@withContext
                            }
                        }

                        // 2. Read exact body from Reader using Content-Length
                        val rawBody = if (contentLength > 0) {
                            val bodyBuffer = CharArray(contentLength)
                            var bytesRead = 0
                            while (bytesRead < contentLength) {
                                val result = reader.read(bodyBuffer, bytesRead, contentLength - bytesRead)
                                if (result == -1) break
                                bytesRead += result
                            }
                            String(bodyBuffer, 0, bytesRead)
                        } else {
                            ""
                        }

                        // Parse requested model out of the payload
                        try {
                            if (rawBody.trim().isNotEmpty()) {
                                val jsonObj = JSONObject(rawBody)
                                requestModel = jsonObj.optString("model", "unknown-model")
                            }
                        } catch (e: Exception) {
                            Log.e("ProxyServerManager", "Failed to parse requested input JSON model ID", e)
                        }

                        // Determine active routing mode (Local Simulator vs Cloud Gemini Proxy)
                        val isCloudMode = settings?.targetProvider == "CLOUD_GEMINI"

                        if (isCloudMode) {
                            // Resolve backend injected Gemini API key in workspace environment
                            val geminiKey = BuildConfig.GEMINI_API_KEY
                            if (geminiKey.isEmpty() || geminiKey == "MY_GEMINI_API_KEY") {
                                httpStatus = 500
                                outputResponseText = "{\"error\": {\"message\": \"Gemini API Key is missing. Please add it via the Secrets panel in AI Studio.\", \"type\": \"gateway_setup_error\"}}"
                                sendJsonResponse(outputStream, 500, outputResponseText)
                            } else {
                                // Translate to standard Gemini payloads
                                val geminiPayload = OpenAiToGeminiTranslator.translateRequest(rawBody)

                                // Forward request to Google Gemini API
                                val mediaType = "application/json".toMediaType()
                                val reqBodyArgs = geminiPayload.toRequestBody(mediaType)

                                // Map model type
                                val geminiModelId = if (requestModel.contains("pro", ignoreCase = true)) {
                                    "gemini-2.5-pro"
                                } else {
                                    "gemini-2.5-flash"
                                }

                                val geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/$geminiModelId:generateContent?key=$geminiKey"
                                
                                val googleRequest = Request.Builder()
                                    .url(geminiUrl)
                                    .post(reqBodyArgs)
                                    .build()

                                okHttpClient.newCall(googleRequest).execute().use { response ->
                                    val code = response.code
                                    val responseBody = response.body?.string() ?: ""

                                    if (code == 200) {
                                        // Translate response back to standard OpenAI
                                        val openAiFormat = OpenAiToGeminiTranslator.translateResponse(responseBody, requestModel)
                                        outputResponseText = openAiFormat
                                        sendJsonResponse(outputStream, 200, openAiFormat)
                                    } else {
                                        httpStatus = code
                                        outputResponseText = "{\"error\": {\"message\": \"Inward Gemini Error: $responseBody\", \"type\": \"upstream_error\", \"code\": $code}}"
                                        sendJsonResponse(outputStream, code, outputResponseText)
                                    }
                                }
                            }
                        } else {
                            // 3. Local/Mock simulator response
                            val localSimulatedJson = OpenAiToGeminiTranslator.generateSimulatedResponse(rawBody, requestModel)
                            outputResponseText = localSimulatedJson
                            sendJsonResponse(outputStream, 200, localSimulatedJson)
                        }

                        val duration = System.currentTimeMillis() - startTime

                        // Extract preview for database log displays
                        val preview = try {
                            val outObj = JSONObject(outputResponseText)
                            val firstChoice = outObj.getJSONArray("choices").getJSONObject(0)
                            val contentText = firstChoice.getJSONObject("message").getString("content")
                            contentText.take(120) + (if (contentText.length > 120) "..." else "")
                        } catch (e: Exception) {
                            "Raw: " + outputResponseText.take(100)
                        }

                        // Insert into tracking database logs safely
                        try {
                            repository.insertLog(
                                GatewayLog(
                                    method = method,
                                    path = path,
                                    requestModel = requestModel,
                                    clientIp = clientIp,
                                    status = httpStatus,
                                    durationMs = duration,
                                    responsePreview = preview,
                                    isAuthorized = authorized
                                )
                            )
                        } catch (dbEx: Exception) {
                            Log.e("ProxyServerManager", "DB logging failed for successful gateway run", dbEx)
                        }

                    } catch (ex: Exception) {
                        Log.e("ProxyServerManager", "Fatal handle exception error", ex)
                        val errJson = "{\"error\": {\"message\": \"Gateway failure: ${ex.localizedMessage}\", \"type\": \"gateway_error\"}}"
                        try {
                            sendJsonResponse(outputStream, 500, errJson)
                        } catch (writeEx: Exception) {
                            Log.e("ProxyServerManager", "Failed to write exception JSON to client socket", writeEx)
                        }

                        val duration = System.currentTimeMillis() - startTime
                        try {
                            repository.insertLog(
                                GatewayLog(
                                    method = method,
                                    path = path,
                                    requestModel = requestModel,
                                    clientIp = clientIp,
                                    status = 500,
                                    durationMs = duration,
                                    responsePreview = "Exception: ${ex.localizedMessage}",
                                    isAuthorized = authorized
                                )
                            )
                        } catch (dbEx: Exception) {
                            Log.e("ProxyServerManager", "DB logging failed in catch block of handleClient", dbEx)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ProxyServerManager", "Error in socket communication client worker", e)
            }
        }
    }

    private fun sendJsonResponse(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), true)

        val statusMsg = when (status) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            404 -> "404 Not Found"
            else -> "500 Internal Server Error"
        }

        writer.print("HTTP/1.1 $statusMsg\r\n")
        writer.print("Content-Type: application/json; charset=utf-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, Authorization, *\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
    }

    private fun sendOptionsSuccess(out: OutputStream) {
        val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), true)
        writer.print("HTTP/1.1 204 No Content\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, Authorization, *\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }

    private fun sendErrorResponse(out: OutputStream, status: Int, rawTxt: String) {
        val bytes = rawTxt.toByteArray(StandardCharsets.UTF_8)
        val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), true)
        val statusMsg = when (status) {
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            404 -> "404 Not Found"
            else -> "500 Internal Server Error"
        }
        writer.print("HTTP/1.1 $statusMsg\r\n")
        writer.print("Content-Type: text/plain; charset=utf-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
    }
}
