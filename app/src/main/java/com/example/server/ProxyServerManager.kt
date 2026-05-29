package com.example.server

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.GatewayLog
import com.example.data.GatewayRepository
import com.example.data.ModelsRegistry
import com.example.data.ProxySetting
import com.example.data.RuntimeType
import com.example.inference.LiteRtLmEngine
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

    fun startServer(port: Int) {
        _serverPort.value = port
        isRunningLoop = true

        serverScope.launch {
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
                    launch {
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
    }

    fun rebootServer(newPort: Int) {
        _serverPort.value = newPort
        isRunningLoop = false // Signal current loop to exit

        serverScope.launch {
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

                    launch {
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
                } catch (e: Throwable) {
                    Log.e("ProxyServerManager", "Error stopping socket server", e)
                }
            }
            // Release native LiteRT-LM resources outside the server-socket mutex so a
            // long close call does not block subsequent server starts.
            try {
                LiteRtLmEngine.close()
            } catch (e: Throwable) {
                Log.e("ProxyServerManager", "Error closing LiteRtLmEngine on stop", e)
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

                        // 2. Handle GET Models request.
                        //
                        // The registry is now the single source of truth (see
                        // plan.md §5.4). Every id we list here is guaranteed to
                        // route successfully when its prerequisite resource is
                        // present (cloud key for CLOUD entries, on-disk weights
                        // for LITERT_LM entries). Per-model runtime selection
                        // (plan.md §11 PR #8) means there is no global provider
                        // gate to honor here.
                        if (isModelsEndpoint) {
                            val cloudKeyPresent = run {
                                val deviceKey = settings?.geminiApiKey ?: ""
                                val resolved = if (deviceKey.isNotEmpty()) deviceKey else BuildConfig.GEMINI_API_KEY
                                resolved.isNotEmpty() && resolved != "MY_GEMINI_API_KEY"
                            }

                            val modelsList = org.json.JSONArray()
                            ModelsRegistry.allowedModels.forEach { m ->
                                val available = when (m.runtimeType) {
                                    RuntimeType.CLOUD -> cloudKeyPresent
                                    RuntimeType.LITERT_LM -> {
                                        val f = m.getResolvedTargetFile(context)
                                        f.exists() && f.isFile && f.length() > 0
                                    }
                                    // AICore is registered but not yet implemented;
                                    // do not advertise a model we cannot serve.
                                    RuntimeType.AICORE -> false
                                }
                                if (!available) return@forEach

                                val ownedBy = when (m.runtimeType) {
                                    RuntimeType.CLOUD -> "google-cloud"
                                    RuntimeType.LITERT_LM -> "gateway-local"
                                    RuntimeType.AICORE -> "android-aicore"
                                }
                                modelsList.put(
                                    org.json.JSONObject()
                                        .put("id", m.modelId)
                                        .put("object", "model")
                                        .put("created", 1710000000)
                                        .put("owned_by", ownedBy)
                                        .put("x_runtime", m.runtimeType.name)
                                        .put("x_experimental", m.experimental)
                                )
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

                        // Parse the requested model id plus two request flags:
                        //  - `tools` / legacy `functions`: function-calling is not
                        //    implemented yet (plan.md §11 PR #9). A non-empty list
                        //    triggers an early 501 below; `tools: []` is a no-op.
                        //  - `stream: true`: honored on the local LiteRT-LM path
                        //    (plan.md §11 PR #6/#11); cloud streaming would need
                        //    Gemini's streamGenerateContent endpoint and is a
                        //    future PR.
                        var hasToolsField = false
                        var isStreaming = false
                        try {
                            if (rawBody.trim().isNotEmpty()) {
                                val jsonObj = JSONObject(rawBody)
                                requestModel = jsonObj.optString("model", "unknown-model")
                                isStreaming = jsonObj.optBoolean("stream", false)
                                val tools = jsonObj.opt("tools")
                                if (tools is org.json.JSONArray && tools.length() > 0) {
                                    hasToolsField = true
                                }
                                val legacyFunctions = jsonObj.opt("functions")
                                if (legacyFunctions is org.json.JSONArray && legacyFunctions.length() > 0) {
                                    hasToolsField = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ProxyServerManager", "Failed to parse requested input JSON model ID", e)
                        }

                        // Function calling / tool_calls round-trips are not yet
                        // implemented (plan.md §11 PR #9). Reject early with
                        // a 501 + `not_implemented` so clients can fall back
                        // to a tools-free turn rather than have the gateway
                        // silently drop the tools array on the floor and
                        // produce a normal text reply that ignores the
                        // requested function schema.
                        if (hasToolsField) {
                            httpStatus = 501
                            outputResponseText = HttpErrors.jsonError(
                                message = "Function calling / tool_calls is not yet implemented by this gateway. " +
                                        "Remove the `tools` (or legacy `functions`) field from the request and retry, " +
                                        "or use a model and gateway that supports it.",
                                type = "not_implemented",
                                code = 501
                            )
                            sendJsonResponse(outputStream, 501, outputResponseText)
                            val duration = System.currentTimeMillis() - startTime
                            try {
                                repository.insertLog(
                                    GatewayLog(
                                        method = method,
                                        path = path,
                                        requestModel = requestModel,
                                        clientIp = clientIp,
                                        status = 501,
                                        durationMs = duration,
                                        responsePreview = "Rejected: tools field present (501 not_implemented)",
                                        isAuthorized = authorized
                                    )
                                )
                            } catch (dbEx: Throwable) {
                                Log.e("ProxyServerManager", "DB logging failed for tools 501 reject", dbEx)
                            }
                            return@withContext
                        }

                        // Resolve the request to a concrete RoutedModel using
                        // ModelsRegistry as the single source of truth. The router
                        // does all the validation: unknown id, missing weights,
                        // missing key. Per-model runtime selection (plan.md §11
                        // PR #8) means there is no global provider gate — each
                        // registered id picks its own runtime via RuntimeType.
                        // See ModelRouter.kt.
                        val resolvedSettings = settings ?: ProxySetting()
                        val deviceGeminiKey = settings?.geminiApiKey ?: ""
                        val effectiveGeminiKey = if (deviceGeminiKey.isNotEmpty()) {
                            deviceGeminiKey
                        } else {
                            BuildConfig.GEMINI_API_KEY
                        }
                        val cloudKeyPresent = effectiveGeminiKey.isNotEmpty() &&
                                effectiveGeminiKey != "MY_GEMINI_API_KEY"

                        val routeResult = ModelRouter.resolve(
                            requestedId = requestModel,
                            weightsAvailable = { m ->
                                val f = m.getResolvedTargetFile(context)
                                f.exists() && f.length() > 0
                            },
                            hasCloudKey = { cloudKeyPresent }
                        )

                        if (routeResult.isFailure) {
                            // Router said no — render the typed RoutingError
                            // through the shared OpenAI-shaped error envelope.
                            val re = routeResult.exceptionOrNull() as? RoutingErrorException
                            val err: RoutingError = re?.error ?: RoutingError.UnknownModel(requestModel)
                            httpStatus = err.httpStatus
                            outputResponseText = HttpErrors.jsonError(err)
                            sendJsonResponse(outputStream, err.httpStatus, outputResponseText)
                        } else {
                            when (val routed = routeResult.getOrNull()!!) {
                                is RoutedModel.Cloud -> {
                                    // Cloud streaming via Gemini's
                                    // `streamGenerateContent` endpoint is
                                    // a future PR. For now, refuse
                                    // `stream:true` on cloud routes with a
                                    // clean 501 — the LITERT_LM branch
                                    // below honors `stream:true` natively.
                                    if (isStreaming) {
                                        httpStatus = 501
                                        outputResponseText = HttpErrors.jsonError(
                                            message = "Streaming for cloud Gemini models is not yet implemented " +
                                                    "by this gateway. Drop `stream:true` from the request, or use " +
                                                    "an on-device LiteRT-LM model id (which does support streaming).",
                                            type = "not_implemented",
                                            code = 501
                                        )
                                        sendJsonResponse(outputStream, 501, outputResponseText)
                                        val duration = System.currentTimeMillis() - startTime
                                        try {
                                            repository.insertLog(
                                                GatewayLog(
                                                    method = method,
                                                    path = path,
                                                    requestModel = requestModel,
                                                    clientIp = clientIp,
                                                    status = 501,
                                                    durationMs = duration,
                                                    responsePreview = "Rejected: stream:true on cloud route (501 not_implemented)",
                                                    isAuthorized = authorized
                                                )
                                            )
                                        } catch (dbEx: Throwable) {
                                            Log.e("ProxyServerManager", "DB logging failed for cloud-stream 501 reject", dbEx)
                                        }
                                        return@withContext
                                    }

                                    // Cloud Gemini: cloudUpstreamId is set by the
                                    // registry for every CLOUD entry, so the old
                                    // substring-`pro` heuristic is gone. The
                                    // upstream id is whatever the registry says.
                                    val upstreamId = routed.info.cloudUpstreamId ?: routed.info.modelId

                                    val geminiPayload = OpenAiToGeminiTranslator.translateRequest(rawBody)
                                    val mediaType = "application/json".toMediaType()
                                    val reqBodyArgs = geminiPayload.toRequestBody(mediaType)
                                    val geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" +
                                            "$upstreamId:generateContent?key=$effectiveGeminiKey"

                                    val googleRequest = Request.Builder()
                                        .url(geminiUrl)
                                        .post(reqBodyArgs)
                                        .build()

                                    okHttpClient.newCall(googleRequest).execute().use { response ->
                                        val code = response.code
                                        val responseBody = response.body?.string() ?: ""

                                        if (code == 200) {
                                            // Translate response back to standard OpenAI shape;
                                            // requestModel (the client-facing id, possibly an alias)
                                            // is returned to the caller verbatim.
                                            val openAiFormat = OpenAiToGeminiTranslator.translateResponse(responseBody, requestModel)
                                            outputResponseText = openAiFormat
                                            sendJsonResponse(outputStream, 200, openAiFormat)
                                        } else {
                                            httpStatus = code
                                            outputResponseText = HttpErrors.jsonError(
                                                message = "Upstream Gemini error: $responseBody",
                                                type = "upstream_error",
                                                code = code
                                            )
                                            sendJsonResponse(outputStream, code, outputResponseText)
                                        }
                                    }
                                }

                                is RoutedModel.LiteRtLm -> {
                                    // Real on-device LiteRT-LM execution.
                                    // See app/src/main/java/com/example/inference/LiteRtLmEngine.kt
                                    // for the canonical lifecycle, mirroring google-ai-edge/gallery's LlmChatModelHelper.
                                    val req = OpenAiToGeminiTranslator.extractLocalEngineRequest(rawBody)
                                    val params = LiteRtLmEngine.GenerationParams(
                                        maxOutputTokens = req.maxTokens,
                                        temperature = req.temperature,
                                        topK = req.topK,
                                        topP = req.topP
                                    )
                                    val loadErr = LiteRtLmEngine.ensureLoadedAndReset(
                                        context = context,
                                        model = routed.info,
                                        params = params,
                                        systemInstruction = req.systemInstruction,
                                        history = req.history,
                                        npuOptIn = resolvedSettings.enableNpuBackend
                                    )
                                    if (loadErr != null) {
                                        // Engine-load failures come back as
                                        // JSON 4xx/5xx whether or not the
                                        // client asked to stream. The client
                                        // hasn't seen any SSE bytes yet, so
                                        // a clean JSON envelope is the right
                                        // wire shape — not a half-opened SSE
                                        // stream that immediately errors.
                                        val (code, body) = OpenAiToGeminiTranslator.wrapLocalError(loadErr, requestModel)
                                        httpStatus = code
                                        outputResponseText = body
                                        sendJsonResponse(outputStream, code, body)
                                    } else if (isStreaming) {
                                        // Real SSE streaming path. Wires
                                        // `LiteRtLmEngine.generateStreaming`
                                        // (added in PR #6) into the OpenAI
                                        // chat.completion.chunk frame format
                                        // built by the translator helpers.
                                        // Once headers go out the door, all
                                        // subsequent failures must surface
                                        // as in-band SSE error frames + DONE
                                        // sentinel — we cannot rewind the
                                        // socket to send a JSON 5xx.
                                        sendSseHeaders(outputStream)
                                        val (streamId, createdSec) = OpenAiToGeminiTranslator.newStreamSession()
                                        writeSseFrame(
                                            outputStream,
                                            OpenAiToGeminiTranslator.streamingFirstDelta(
                                                streamId, createdSec, requestModel
                                            )
                                        )

                                        val streamResult = LiteRtLmEngine.generateStreaming(
                                            userText = req.latestUserText,
                                            onDelta = { deltaText, _ ->
                                                // Each accumulated-message
                                                // turn from the SDK arrives
                                                // as a delta from the prior
                                                // call (the engine slices
                                                // it for us via lastSentLength).
                                                writeSseFrame(
                                                    outputStream,
                                                    OpenAiToGeminiTranslator.streamingContentDelta(
                                                        streamId, createdSec, requestModel, deltaText
                                                    )
                                                )
                                            }
                                        )

                                        when (streamResult) {
                                            is LiteRtLmEngine.Result.Ok -> {
                                                writeSseFrame(
                                                    outputStream,
                                                    OpenAiToGeminiTranslator.streamingFinish(
                                                        streamId, createdSec, requestModel, streamResult
                                                    )
                                                )
                                                writeSseDone(outputStream)
                                                httpStatus = 200
                                                // Gateway-log preview:
                                                // surface the accumulated
                                                // text. The "Raw: ..." JSON
                                                // fallback in the log block
                                                // below picks this up.
                                                outputResponseText = streamResult.text
                                            }
                                            is LiteRtLmEngine.Result.Err -> {
                                                writeSseFrame(
                                                    outputStream,
                                                    OpenAiToGeminiTranslator.streamingError(
                                                        streamResult.message,
                                                        streamResult.type,
                                                        requestModel
                                                    )
                                                )
                                                writeSseDone(outputStream)
                                                // Map the engine error type
                                                // to the same status we'd
                                                // have used non-streaming,
                                                // for the gateway log only —
                                                // the wire is already SSE.
                                                val (code, _) = OpenAiToGeminiTranslator.wrapLocalError(streamResult, requestModel)
                                                httpStatus = code
                                                outputResponseText = "stream_err: ${streamResult.type}: ${streamResult.message}"
                                            }
                                        }
                                    } else {
                                        when (val r = LiteRtLmEngine.generate(req.latestUserText)) {
                                            is LiteRtLmEngine.Result.Ok -> {
                                                outputResponseText = OpenAiToGeminiTranslator.wrapLocalSuccess(r, requestModel)
                                                sendJsonResponse(outputStream, 200, outputResponseText)
                                            }
                                            is LiteRtLmEngine.Result.Err -> {
                                                val (code, body) = OpenAiToGeminiTranslator.wrapLocalError(r, requestModel)
                                                httpStatus = code
                                                outputResponseText = body
                                                sendJsonResponse(outputStream, code, body)
                                            }
                                        }
                                    }
                                }

                                is RoutedModel.AiCore -> {
                                    // Defensive — ModelRouter rejects AICore upstream
                                    // with AiCoreUnsupported, so this branch is unreachable
                                    // unless the router is bypassed in the future.
                                    val err = RoutingError.AiCoreUnsupported(routed.info.modelId)
                                    httpStatus = err.httpStatus
                                    outputResponseText = HttpErrors.jsonError(err)
                                    sendJsonResponse(outputStream, err.httpStatus, outputResponseText)
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
            413 -> "413 Content Too Large"
            429 -> "429 Too Many Requests"
            501 -> "501 Not Implemented"
            502 -> "502 Bad Gateway"
            503 -> "503 Service Unavailable"
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

    /**
     * Writes the response headers for a server-sent-events (SSE) stream:
     * `text/event-stream`, no caching, and `Connection: close` so the client
     * detects end-of-stream from EOF (we don't use HTTP/1.1 chunked
     * transfer-encoding here for simplicity — most SSE clients handle the
     * close-on-EOF pattern fine).
     */
    private fun sendSseHeaders(out: OutputStream) {
        val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), true)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/event-stream; charset=utf-8\r\n")
        writer.print("Cache-Control: no-cache\r\n")
        // X-Accel-Buffering disables proxy buffering on nginx-style intermediaries.
        writer.print("X-Accel-Buffering: no\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, Authorization, *\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }

    /** Writes one `data: <json>\n\n` SSE frame and flushes the socket. */
    private fun writeSseFrame(out: OutputStream, json: String) {
        out.write("data: ".toByteArray(StandardCharsets.UTF_8))
        out.write(json.toByteArray(StandardCharsets.UTF_8))
        out.write("\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    /** Writes the OpenAI-spec terminator `data: [DONE]\n\n`. */
    private fun writeSseDone(out: OutputStream) {
        out.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
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
            413 -> "413 Content Too Large"
            429 -> "429 Too Many Requests"
            501 -> "501 Not Implemented"
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
