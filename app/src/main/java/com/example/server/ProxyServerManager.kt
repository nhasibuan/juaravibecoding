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
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("ProxyServerManager", "Unhandled coroutine exception in ProxyServerManager", exception)
    }
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
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
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement() ?: continue
                val addresses = networkInterface.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement() ?: continue
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val host = address.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ProxyServerManager", "Failed to resolve IP address", e)
        }
        return "127.0.0.1"
    }

    suspend fun startServer(port: Int) = withContext(Dispatchers.IO) {
        _serverPort.value = port
        isRunningLoop = true

        serverMutex.withLock {
            if (_isServerRunning.value) return@withLock

            try {
                val sSocket = ServerSocket()
                sSocket.reuseAddress = true
                sSocket.bind(java.net.InetSocketAddress(port))
                serverSocket = sSocket
                _isServerRunning.value = true
                Log.d("ProxyServerManager", "Socket Server started successfully on port $port")

                // Run the accept loop in a separate coroutine so we do not block the Mutex lock
                serverScope.launch {
                    try {
                        while (isRunningLoop) {
                            val clientSocket = try {
                                sSocket.accept()
                            } catch (e: Throwable) {
                                // socket closed or stopped
                                break
                            }
                            launch {
                                try {
                                    handleClient(clientSocket)
                                } catch (e: Throwable) {
                                    Log.e("ProxyServerManager", "Exception handling client socket connection", e)
                                    try {
                                        clientSocket.close()
                                    } catch (closeEx: Throwable) {
                                        // Ignore close exception
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e("ProxyServerManager", "Exception in server accept loop", e)
                    } finally {
                        withContext(NonCancellable) {
                            serverMutex.withLock {
                                _isServerRunning.value = false
                                if (serverSocket == sSocket) {
                                    serverSocket = null
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
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
                } catch (dbEx: Throwable) {
                    Log.e("ProxyServerManager", "Database write failed in start exception handler", dbEx)
                }
            }
        }
    }

    suspend fun rebootServer(newPort: Int) = withContext(Dispatchers.IO) {
        _serverPort.value = newPort
        isRunningLoop = false // Signal current loop to exit

        serverMutex.withLock {
            // 1. Force close the existing server socket under lock
            try {
                serverSocket?.close()
                serverSocket = null
            } catch (e: Throwable) {
                Log.e("ProxyServerManager", "Error closing old socket during reboot", e)
            }

            _isServerRunning.value = false

            // 2. Open the new server socket under lock
            isRunningLoop = true
            try {
                val sSocket = ServerSocket()
                sSocket.reuseAddress = true
                sSocket.bind(java.net.InetSocketAddress(newPort))
                serverSocket = sSocket
                _isServerRunning.value = true
                Log.d("ProxyServerManager", "Socket Server rebooted successfully on port $newPort")

                serverScope.launch {
                    try {
                        while (isRunningLoop) {
                            val clientSocket = try {
                                sSocket.accept()
                            } catch (e: Throwable) {
                                break
                            }
                            launch {
                                try {
                                    handleClient(clientSocket)
                                } catch (e: Throwable) {
                                    Log.e("ProxyServerManager", "Exception handling client socket connection", e)
                                    try {
                                        clientSocket.close()
                                    } catch (closeEx: Throwable) {
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e("ProxyServerManager", "Exception in server accept loop on reboot", e)
                    } finally {
                        withContext(NonCancellable) {
                            serverMutex.withLock {
                                _isServerRunning.value = false
                                if (serverSocket == sSocket) {
                                    serverSocket = null
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("ProxyServerManager", "Failed to start socket server during reboot on port $newPort", e)
                _isServerRunning.value = false
                isRunningLoop = false

                try {
                    repository.insertLog(
                        GatewayLog(
                            method = "SYSTEM",
                            path = "START",
                            requestModel = "NONE",
                            clientIp = "127.0.0.1",
                            status = 500,
                            durationMs = 0,
                            responsePreview = "Failed to reboot server on port $newPort: ${e.localizedMessage}",
                            isAuthorized = true
                        )
                    )
                } catch (dbEx: Throwable) {
                    Log.e("ProxyServerManager", "Database write failed in reboot exception handler", dbEx)
                }
            }
        }
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        isRunningLoop = false
        try {
            com.example.inference.LiteRtLmEngine.close()
        } catch (t: Throwable) {
            Log.e("ProxyServerManager", "Error closing LiteRtLmEngine during stopServer", t)
        }
        serverMutex.withLock {
            if (!_isServerRunning.value && serverSocket == null) return@withLock
            try {
                serverSocket?.close()
                serverSocket = null
                _isServerRunning.value = false
                Log.d("ProxyServerManager", "Socket Server stopped successfully")
            } catch (e: Throwable) {
                Log.e("ProxyServerManager", "Error stopping socket server", e)
            }
        }
    }

    fun stopServerSync() {
        isRunningLoop = false
        try {
            com.example.inference.LiteRtLmEngine.cancel()
        } catch (_: Throwable) {}
        try {
            serverSocket?.close()
            serverSocket = null
            _isServerRunning.value = false
        } catch (e: Exception) {
            Log.e("ProxyServerManager", "Error stopping socket server synchronously in onCleared", e)
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
                    var contentTooLarge = false
                    while (line != null && line.isNotEmpty()) {
                        val lowercaseLine = line.lowercase(Locale.US)
                        if (lowercaseLine.startsWith("content-length:")) {
                            contentLength = line.substring("content-length:".length).trim().toIntOrNull() ?: 0
                            if (contentLength > 10 * 1024 * 1024) { // 10MB Limit
                                contentTooLarge = true
                            }
                        } else if (lowercaseLine.startsWith("authorization:")) {
                            authorizationHeader = line.substring("authorization:".length).trim()
                        }
                        line = reader.readLine()
                    }

                    if (contentTooLarge) {
                        sendErrorResponse(outputStream, 413, "Content Too Large: Maximum supported content body size is 10MB")
                        return@withContext
                    }

                    // 3. Handle OPTIONS (CORS preflight)
                    if (method == "OPTIONS") {
                        sendOptionsSuccess(outputStream)
                        return@withContext
                    }

                    val startTime = System.currentTimeMillis()

                    // 4. Validate Endpoint path and request method with resilient cleaning
                    val normalizedPath = if (path.contains("://")) {
                        try {
                            java.net.URL(path).path
                        } catch (e: Exception) {
                            path.substringAfter("://").substringAfter("/", "")
                        }
                    } else {
                        path
                    }
                    val cleanPath = "/" + normalizedPath.substringBefore("?").trim { it == '/' }

                    val isChatEndpoint = method == "POST" && (
                        cleanPath.contains("/chat/completions") || 
                        cleanPath == "/chat/completions" || 
                        cleanPath == "/v1" || 
                        cleanPath == "/"
                    )
                    val isModelsEndpoint = method == "GET" && (
                        cleanPath.contains("/models") || 
                        cleanPath == "/v1" || 
                        cleanPath == "/"
                    )

                    if (!isChatEndpoint && !isModelsEndpoint) {
                        val duration = System.currentTimeMillis() - startTime
                        val errorResponse = "{\"error\": {\"message\": \"Gateway only proxies POST /v1/chat/completions and GET /v1/models requests. Received Path: $path (Cleaned: $cleanPath)\", \"type\": \"invalid_request_error\"}}"
                        sendJsonResponse(outputStream, 404, errorResponse)
                        try {
                            repository.insertLog(
                                GatewayLog(
                                    method = method,
                                    path = path,
                                    requestModel = "Unsupported Endpoint",
                                    clientIp = clientIp,
                                    status = 404,
                                    durationMs = duration,
                                    responsePreview = "Rejected: Method=$method, Path=$path, Cleaned=$cleanPath",
                                    isAuthorized = true
                                )
                            )
                        } catch (dbEx: Exception) {
                            Log.e("ProxyServerManager", "DB logging failed for 404 endpoint", dbEx)
                        }
                        return@withContext
                    }

                    var requestModel = if (isModelsEndpoint) "GET Models" else "unknown-model"
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

                        // 2. Handle GET Models request
                        if (isModelsEndpoint) {
                            val activeModelId = settings?.activeModelId ?: "litert-community/gemma-4-E2B-it-litert-lm"
                            val modelsList = org.json.JSONArray()
                            
                            val targetProvider = settings?.targetProvider ?: "CLOUD_GEMINI"
                            val deviceKey = settings?.geminiApiKey ?: ""
                            val geminiKey = if (deviceKey.isNotEmpty()) deviceKey else BuildConfig.GEMINI_API_KEY
                            val hasCloudKey = geminiKey.isNotEmpty() && geminiKey != "MY_GEMINI_API_KEY"

                            com.example.data.ModelsRegistry.allowedModels.forEach { model ->
                                val available = when (model.runtimeType) {
                                    "cloud" -> (targetProvider == "CLOUD_GEMINI" && hasCloudKey)
                                    "litert-lm" -> {
                                        if (targetProvider == "LOCAL_VAL") {
                                            val file = model.getResolvedTargetFile(context)
                                            try { file.exists() && file.isFile && file.length() > 0 } catch (_: Throwable) { false }
                                        } else {
                                            false
                                        }
                                    }
                                    "aicore" -> false
                                    else -> false
                                }

                                if (available) {
                                    val mObj = org.json.JSONObject()
                                        .put("id", model.modelId)
                                        .put("object", "model")
                                        .put("created", 1710000000)
                                        .put("owned_by", if (model.runtimeType == "cloud") "google-cloud" else "gateway-local")
                                        .put("selected", model.modelId == activeModelId)
                                        .put("x_runtime", model.runtimeType)
                                    modelsList.put(mObj)
                                }
                            }

                            val responseObj = org.json.JSONObject()
                                .put("object", "list")
                                .put("data", modelsList)

                            outputResponseText = responseObj.toString()
                            sendJsonResponse(outputStream, 200, outputResponseText)

                            val duration = System.currentTimeMillis() - startTime
                            try {
                                repository.insertLog(
                                    GatewayLog(
                                        method = method,
                                        path = path,
                                        requestModel = "GET Models",
                                        clientIp = clientIp,
                                        status = 200,
                                        durationMs = duration,
                                        responsePreview = "Loaded ${modelsList.length()} models",
                                        isAuthorized = true
                                    )
                                )
                            } catch (dbEx: Exception) {
                                Log.e("ProxyServerManager", "DB logging failed for models endpoint", dbEx)
                            }
                            return@withContext
                        }

                        // 3. Read exact body from Reader using Content-Length
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

                        val deviceKey = settings?.geminiApiKey ?: ""
                        val geminiKey = if (deviceKey.isNotEmpty()) deviceKey else BuildConfig.GEMINI_API_KEY
                        val hasCloudKey = geminiKey.isNotEmpty() && geminiKey != "MY_GEMINI_API_KEY"

                        val routingResult = ModelRouter.resolve(
                            requestedId = requestModel,
                            settings = settings ?: com.example.data.ProxySetting(),
                            weightsAvailable = { m ->
                                val file = m.getResolvedTargetFile(context)
                                try { file.exists() && file.isFile && file.length() > 0 } catch (_: Throwable) { false }
                            },
                            hasCloudKey = { hasCloudKey }
                        )

                        if (routingResult.isFailure) {
                            val err = routingResult.exceptionOrNull() as? RoutingError ?: RoutingError.UnknownModel(requestModel)
                            httpStatus = err.httpStatus
                            outputResponseText = org.json.JSONObject()
                                .put("error", org.json.JSONObject()
                                    .put("message", err.msg)
                                    .put("type", err.type)
                                    .put("param", org.json.JSONObject.NULL)
                                    .put("code", err.httpStatus)
                                ).toString()
                            sendJsonResponse(outputStream, httpStatus, outputResponseText)
                        } else {
                            when (val routed = routingResult.getOrThrow()) {
                                is RoutedModel.Cloud -> {
                                    val cloudModelInfo = routed.info
                                    val upstreamId = cloudModelInfo.cloudUpstreamId ?: cloudModelInfo.modelId
                                    val geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/$upstreamId:generateContent?key=$geminiKey"
                                    
                                    val geminiPayload = OpenAiToGeminiTranslator.translateRequest(rawBody)
                                    val mediaType = "application/json".toMediaType()
                                    val reqBodyArgs = geminiPayload.toRequestBody(mediaType)

                                    val googleRequest = Request.Builder()
                                        .url(geminiUrl)
                                        .post(reqBodyArgs)
                                        .build()

                                    okHttpClient.newCall(googleRequest).execute().use { response ->
                                        val code = response.code
                                        val responseBody = response.body?.string() ?: ""

                                        if (code == 200) {
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
                                is RoutedModel.LiteRtLm -> {
                                    val targetModelInfo = routed.info
                                    val localReq = OpenAiToGeminiTranslator.extractLocalEngineRequest(rawBody)
                                    val params = com.example.inference.LiteRtLmEngine.GenerationParams(
                                        maxOutputTokens = localReq.maxTokens,
                                        temperature = localReq.temperature,
                                        topK = localReq.topK,
                                        topP = localReq.topP
                                    )

                                    val loadErr = com.example.inference.LiteRtLmEngine.ensureLoadedAndReset(
                                        context = context,
                                        model = targetModelInfo,
                                        params = params,
                                        systemInstruction = localReq.systemInstruction,
                                        history = localReq.history
                                    )

                                    if (loadErr != null) {
                                        val (code, body) = OpenAiToGeminiTranslator.wrapLocalError(loadErr, requestModel)
                                        httpStatus = code
                                        outputResponseText = body
                                        sendJsonResponse(outputStream, code, body)
                                    } else {
                                        when (val r = com.example.inference.LiteRtLmEngine.generate(localReq.latestUserText)) {
                                            is com.example.inference.LiteRtLmEngine.Result.Ok -> {
                                                val successResponse = OpenAiToGeminiTranslator.wrapLocalSuccess(r, requestModel)
                                                outputResponseText = successResponse
                                                sendJsonResponse(outputStream, 200, successResponse)
                                            }
                                            is com.example.inference.LiteRtLmEngine.Result.Err -> {
                                                val (code, body) = OpenAiToGeminiTranslator.wrapLocalError(r, requestModel)
                                                httpStatus = code
                                                outputResponseText = body
                                                sendJsonResponse(outputStream, code, body)
                                            }
                                        }
                                    }
                                }
                                is RoutedModel.AiCore -> {
                                    httpStatus = 501
                                    outputResponseText = "{\"error\": {\"message\": \"AICore runtime is not implemented yet.\", \"type\": \"not_implemented\", \"code\": 501}}"
                                    sendJsonResponse(outputStream, 501, outputResponseText)
                                }
                            }
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
                        } catch (dbEx: Throwable) {
                            Log.e("ProxyServerManager", "DB logging failed for successful gateway run", dbEx)
                        }

                    } catch (ex: Throwable) {
                        Log.e("ProxyServerManager", "Fatal handle exception error", ex)
                        val errJson = "{\"error\": {\"message\": \"Gateway failure: ${ex.localizedMessage}\", \"type\": \"gateway_error\"}}"
                        try {
                            sendJsonResponse(outputStream, 500, errJson)
                        } catch (writeEx: Throwable) {
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
                        } catch (dbEx: Throwable) {
                            Log.e("ProxyServerManager", "DB logging failed in catch block of handleClient", dbEx)
                        }
                    }
                }
            } catch (e: Throwable) {
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

    private fun isEmulator(): Boolean {
        val brand = (android.os.Build.BRAND ?: "").lowercase(Locale.ROOT)
        val device = (android.os.Build.DEVICE ?: "").lowercase(Locale.ROOT)
        val model = (android.os.Build.MODEL ?: "").lowercase(Locale.ROOT)
        val product = (android.os.Build.PRODUCT ?: "").lowercase(Locale.ROOT)
        val hardware = (android.os.Build.HARDWARE ?: "").lowercase(Locale.ROOT)
        val fingerprint = (android.os.Build.FINGERPRINT ?: "").lowercase(Locale.ROOT)
        val manufacturer = (android.os.Build.MANUFACTURER ?: "").lowercase(Locale.ROOT)
        val board = (android.os.Build.BOARD ?: "").lowercase(Locale.ROOT)
        
        return brand.startsWith("generic") || 
                (brand.contains("google") && device.startsWith("vsoc")) ||
                brand.contains("redroid") ||
                device.startsWith("generic") ||
                device.contains("vsoc") || 
                device.contains("redroid") || 
                model.contains("google_sdk") || 
                model.contains("emulator") || 
                model.contains("android sdk built for") ||
                model.contains("cuttlefish") ||
                model.contains("redroid") || 
                hardware.contains("goldfish") || 
                hardware.contains("ranchu") ||
                hardware.contains("cutf") ||
                hardware.contains("cuttlefish") ||
                hardware.contains("redroid") || 
                hardware.contains("qemu") || 
                product.contains("sdk_gphone") ||
                product.contains("sdk_gpc") ||
                product.contains("sdk") ||
                product.contains("cuttlefish") ||
                product.contains("redroid") || 
                product.contains("vbox") || 
                fingerprint.startsWith("generic") ||
                fingerprint.contains("test-keys") ||
                fingerprint.contains("release-keys") && (brand.contains("generic") || brand.contains("unknown")) ||
                manufacturer.contains("genymotion") ||
                manufacturer.contains("nox") ||
                manufacturer.contains("google") && model.contains("sdk") ||
                board.contains("cuttlefish") ||
                board.contains("redroid") ||
                (android.os.Build.SUPPORTED_ABIS ?: emptyArray()).any { (it ?: "").contains("x86") || (it ?: "").contains("x86_64") }
    }
}

// NativeLlmRunner has been removed, replaced by first-class LiteRtLmEngine
