# AI Proxy Gateway — Professional Product Documentation & System Blueprint

Welcome to the official developer documentation and system guide for the **AI Proxy Gateway**. This high-performance Android utility acts as an ultra-low-latency local HTTP proxy server running directly on any Android device or emulator. The gateway bridges legacy client applications using the standard OpenAI specification (`POST /v1/chat/completions` and `GET /v1/models`) with high-performance local on-device **LiteRT-LM** models or secure **Google Gemini Cloud APIs**.

---

## 🔗 Platform Links & Live Resources

*   **Development App URL:** [https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Shared App Preview URL:** [https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Official Google Gemini API Reference:** [https://ai.google.dev/gemini-api/docs](https://ai.google.dev/gemini-api/docs)
*   **OpenAI Chat API Reference:** [https://developers.openai.com/api/reference/resources/chat](https://developers.openai.com/api/reference/resources/chat)
*   **Google AI Edge - LiteRT Portal:** [https://ai.google.dev/edge/litert](https://ai.google.dev/edge/litert)
*   **LiteRT-LM Developer Portal:** [https://ai.google.dev/edge/litert-lm](https://ai.google.dev/edge/litert-lm)
*   **Hostings-HuggingFace LiteRT Models Library:** [https://huggingface.co/litert-community](https://huggingface.co/litert-community)

---

## 1. Product Requirement Document (PRD)

### 1.1 Product Vision & Business Context
Many legacy, enterprise, and newly-engineered systems have integrated AI features around the proprietary OpenAI API standard (`POST /v1/chat/completions`). Adopting alternative, high-performance engines (such as Google’s Gemini family) typically demands expensive, invasive, and error-prone rewrites of production server code. Additionally, transmitting all application data to cloud AI endpoints introduces operational API costs, network dependencies, and strict regulatory compliance hurdles regarding data residency.

The **AI Proxy Gateway** directly addresses these challenges by acting as an intelligent local network hub:
*   **Zero-Invasive Integrations:** Client apps simply point their API base URL to the local Android gateway IP address and port (e.g., `http://192.168.1.144:8080/v1`). No structural code rewrites are required.
*   **API Cost Control & Off-Grid Operation:** Administrators can switch between secure **Cloud Gemini API** endpoints and on-device **LiteRT-LM** local inference engines. Utilizing local compute shields organizations from high cloud token bills and guarantees full off-grid functionality.
*   **Dynamic Provider Switching:** Swapping the routing engine instantly updates a persistent configuration record in a reactive Room SQLite database. The live background thread listener processes these preferences on every incoming request, dynamically redirecting between OkHttp cloud tunnels and native local on-device weight files without restarting the proxy.
*   **Absolute Data Protection:** Sensitive client inputs are processed locally via on-device weights, so confidential data never leaves the physical target device.

### 1.2 Target Personas
*   **Mobile & Enterprise Software Engineers:** Require a reliable, standards-compliant endpoint matching the exact OpenAI chat completion structure.
*   **Compliance & IT Security Administrators:** Require complete traffic auditing, secure Gateway Bearer token authentication, CORS flexibility, and real-time transaction timings.
*   **On-Grid/Off-Grid Field Technicians:** Want a streamlined, easy-to-use mobile control dashboard to run network proxy services, monitor downloads, view physical model sizes, and examine active timings.

### 1.3 Core Features & Requirements
*   **OpenAI v1 Emulation Layer:**
    *   `POST /v1/chat/completions` (maps prompts, parameters, and system messages into Google structures; compiles unified OpenAI responses).
    *   `GET /v1/models` (dynamically queries system preferences, marks the current active local engine with `"selected": true`, and outputs available local/cloud options).
*   **Dual-Route Execution Pipelines:**
    *   **Cloud Gemini Pipeline:** Forwards incoming requests to Gemini models using API keys stored securely on the database or injected via `BuildConfig`.
    *   **On-Device LiteRT-LM Pipeline:** Integrates the official Google **`com.google.mediapipe:tasks-genai:0.10.14`** SDK to execute local inference using `*.bin` / `*.litertlm` models. Evaluated within a robust `try-catch-finally` block to release native memory instantly via `inference.close()`.
    *   **Missing Models Protection:** If local weights are not present, the gateway promptly rejects requests with a clean HTTP 400 Bad Request detailing missing elements rather than simulating a fake mock fallback.
*   **On-Demand Model UI Bindings:** Clicking any model card under the **ON-DEVICE MODEL DIRECTORY** overrides and binds the active model in system preferences, rendering a visual color boundary and an elegant **"ACTIVE"** badge.
*   **Adaptive Background Downloader:** Downloads models from remote hostings directly into Android scoped files, calculates real-time fractional downloading speeds (Mb/s), and launches the model dynamically once downloaded.
*   **Real-Time Audit Console:** A localized Room-backed logger archiving caller IP addresses, API methods, latency speeds, response summaries, and authentication checks.

### 1.4 Active Engine Comparison

| Attribute | Cloud Gemini APIs (`CLOUD_GEMINI`) | On-Device LiteRT-LM (`LOCAL_VAL`) |
| :--- | :--- | :--- |
| **Operational Mode** | Offloads processing to secure Google cloud servers. | Loads and executes model binaries natively on the device. |
| **Internet Dependency** | Yes (Active network internet/cellular connection is mandatory). | No (100% offline local loop operation). |
| **Hardware Overhead** | Minimal (Standard networking, minimal battery impact). | Moderate-High (Consumes CPU/GPU and system RAM based on model size). |
| **Data Residency** | Input transmitted securely to Google Gemini API servers. | 100% confidential. No data ever leaves the physical target device. |
| **Capabilities Profile** | Access to complex ultra-large models (e.g., Gemini 2.5 Pro / Flash). | Run highly-optimized small edge models (Gemma-3n/4, Qwen-2.5, DeepSeek-Distill). |
| **Cost Profile** | Standard API token billing rates of Google Cloud AI. | Free local compute. Absolute zero API token bills. |

---

## 2. Technical Architecture & Blueprint

### 2.1 System Architecture Topology

```
                  +-------------------------------------------------+
                  |                External Clients                 |
                  |       (Send OpenAI /v1/chat/completions)        |
                  +------------------------+------------------------+
                                           | [Wi-Fi LAN Socket Connection]
                                           v
                  +-------------------------------------------------+
                  |      ProxyServerManager (Server Socket Run)     | <---+ (Reactively Read
                  +------------------------+------------------------+     |  Preferences State)
                                           |                              |
                                           v                              |
                  +-------------------------------------------------+     |
                  |            OpenAiToGeminiTranslator             |     |
                  |   - Translates OpenAI JSON input to Gemini      |     |
                  |   - Translates Gemini output to OpenAI format   |     |
                  +------------------------+------------------------+     |
                                           |                              |
                   +-----------------------+-----------------------+      |
                   |                                               |      |
                   | (LiteRT-LM Mode)                              | (Cloud Mode)
                   v                                               v      |
+------------------------------------+           +-----------------+------+-----+
|         LiteRT-LM Engine           |           |     Google Gemini Cloud      |
|     (Mediapipe LlmInference)       |           |        REST Endpoint         |
|  Loads weights locally on device   |           |    (Uses secure API key)     |
+-----------------+------------------+           +------------------+-----------+
                  |                                                 |
                  v                                                 v
                  +------------------------+------------------------+
                                           |
                                  OUTPUT TO LOCAL PORT
                                           |
                                           v
                  +------------------------+------------------------+
                  |               [GatewayViewModel]                |
                  |  - Shuttles flows between DB layer and views    |
                  |  - Manages server socket and download loops     |
                  +------------------------+------------------------+
                                           | Reactive State Bindings
                                           v
                  +-------------------------------------------------+
                  |         GatewayScreen (Material 3 UI)           |
                  | - Real-time metrics, config files, audit logs  |
                  +-------------------------------------------------+
```

---

### 2.2 Data Dictionary

The persistence engine utilizes **Room Database** to store local structures, query definitions, and structural schemas.

#### Table 1: `proxy_settings` (Schema Version 2)
Stores configuration details for-context. Encapsulates a row constraint (`id = 1`) to operate strictly as a single active configuration record in the system.

| Column Name | Storage Type | Nullability | Constraints | Default Value | Functional Role & Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY` (Must equal `1`) | `1` | Forces a singleton master settings record constraint. |
| `port` | `INTEGER` | `NOT NULL` | Range: `1024` - `65535` | `8080` | Port assigned to start the background proxy server socket. |
| `proxyApiKey` | `TEXT` | `NOT NULL` | None | `""` | Restricts client API access. Rejects requests lacking bearer matching. |
| `activeModelId` | `TEXT` | `NOT NULL` | None | `"litert-community/gemma-4-E2B-it-litert-lm"` | Active AI model target selection ID. |
| `targetProvider` | `TEXT` | `NOT NULL` | One of: `"CLOUD_GEMINI"`, `"LOCAL_VAL"` | `"CLOUD_GEMINI"` | Active routing provider strategy. |
| `geminiApiKey` | `TEXT` | `NOT NULL` | None | `""` | Device-stored custom Gemini API Key. Overrides BuildConfig keys. |

#### Table 2: `gateway_logs`
Archived audits of HTTP request activities processed natively.

| Column Name | Storage Type | Nullability | Constraints | Default Value | Functional Role & Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY AUTOINCREMENT` | - | Unique incremental record index. |
| `timestamp` | `INTEGER` | `NOT NULL` | None | `System.currentTimeMillis()` | UTC timestamp in epoch milliseconds. |
| `method` | `TEXT` | `NOT NULL` | None | - | Received HTTP Action prefix (e.g., `"POST"`, `"GET"`, `"OPTIONS"`). |
| `path` | `TEXT` | `NOT NULL` | None | - | Visited address subpath (e.g., `"/v1/chat/completions"`). |
| `requestModel` | `TEXT` | `NOT NULL` | None | `"unknown-model"` | Target AI Model parsed from client payload. |
| `clientIp` | `TEXT` | `NOT NULL` | None | `"unknown"` | Origin IPv4 or IPv6 network address of remote caller. |
| `status` | `INTEGER` | `NOT NULL` | None | `200` | HTTP Response Status Code returned to client. |
| `durationMs` | `INTEGER` | `NOT NULL` | None | `0` | Absolute duration execution speed metric in milliseconds. |
| `responsePreview` | `TEXT` | `NOT NULL` | None | `""` | Summary sample of the processed response text. |
| `isAuthorized` | `INTEGER` | `NOT NULL` | `0` (false) / `1` (true) | `1` | Denotes whether client credentials evaluation passed checks. |

---

### 2.3 Applied Design Patterns

1.  **Model-View-ViewModel (MVVM) Pattern**:
    *   Our presentation layers (`GatewayScreen`) are clean, completely decoupled visual rendering nodes in Jetpack Compose.
    *   Interactive events and updates route immediately to the coordinator State Machine (`GatewayViewModel`).
    *   Schedules core actions in specialized, lifecycle-aware Kotlin Coroutine Scopes with clean background execution.
2.  **Repository Pattern (`GatewayRepository`)**:
    *   Decouples the View-Model state layer from direct database schema code.
    *   Bridges SQL queries (`ProxySettingDao`, `GatewayLogDao`) into structured, reactive read streams (`Flow<ProxySetting?>` and `Flow<List<GatewayLog>>`).
3.  **Dependency Injection (DI) & Factory Pattern (`GatewayViewModelFactory`)**:
    *   Avoids heavy DI framework overhead by leveraging manual, clean constructor injection.
    *   `GatewayViewModelFactory` handles database lookup, repository instantiation, and proxy-server initialization sequentially. It is applied when binding the view-model in `MainActivity` via `viewModel(factory = GatewayViewModelFactory(application))`.
4.  **Observer Pattern (Reactive Streams)**:
    *   Utilizes Kotlin `StateFlow` and Compose `collectAsStateWithLifecycle` pipelines. Changes to ports, API keys, active providers, downloads, or audit logs update the interface and running server instantly.
5.  **Adapter Pattern (`OpenAiToGeminiTranslator`)**:
    *   Converts OpenAI request JSON keys (e.g., `messages`, `model`, `temperature`) into Google Gemini specifications.
    *   Reconstructs outbound Gemini JSON responses into strict, valid OpenAI completions layouts.

---

### 2.4 Kotlin & Android Engineering Best Practices Utilized

*   **Coroutines & Thread Safety**: All high-overhead operations (background Server Sockets, network downloads, Disk file operations, database transactions) run entirely on context-appropriate dispatchers (`Dispatchers.IO`).
*   **Atomic Socket Operations & State Locks**: Configures atomic state bindings and synchronized locks across thread domains (`ProxyServerManager`). This ensures uninhibited restarts and avoids "Port already bound" (`BindException`) state crashes.
*   **Safe Closeable Pattern**: Implements strict `try-catch-finally` pipelines on the on-device LiteRT execution loop:
    ```kotlin
    val inference = LlmInference.createFromOptions(context, options)
    try {
        val prompt = OpenAiToGeminiTranslator.extractUserPrompt(rawBody)
        realInferenceResult = inference.generateResponse(prompt)
    } finally {
        try {
            inference.close()
        } catch (ignored: Throwable) {}
    }
    ```
    This releases native C++ graphics and CPU pipeline resources immediately, avoiding file handle and virtual memory leaks.
*   **Strict Type-Safety**: Formulates sealed classes and interfaces, strictly avoiding generic typecast wrappers (`Any`) to ensure robust code maintainability.
*   **Dynamic Android Interface Layouts**: Implements optimal Material Design 3 guidelines:
    *   Declares `enableEdgeToEdge()` to respect status and system navigation bars.
    *   Uses dynamic screen classes with responsive components to support foldables, tablets, and ChromeOS.
    *   Meets strict accessibility targets with a minimum interactive touch target size of 48.dp x 48.dp.

---

## 3. Step-by-Step User Guide

This guide details how to configure API credentials, manage downloadable weights, launch the proxy gateway, and verify routing with active payloads.

### Step 1: Configure Gemini Cloud API Credentials
If you intend to route request traffic online to Google's public cloud servers (`CLOUD_GEMINI`), the proxy requires an online API developer secret. Configure this key using either of two secure methods:

#### Method A: Direct In-App Settings Form (Recommended)
1. Launch the **AI Proxy Gateway** on your Android device.
2. Scroll down to the **GATEWAY CONFIGURATION** section.
3. Locate the input card: **Device-Stored Gemini API Key (Optional)**.
4. Paste your Gemini API Developer Token directly into this field.
5. Tap **SHOW** to review the entered token, or **HIDE** to mask it securely.
6. Click **APPLY SETTINGS** to store the key in the encrypted local Room SQLite database. The gateway will prioritize this custom key for all Cloud-routed API transactions.

#### Method B: Project Workspace Environment Config
Alternatively, you can compile and bundle development credentials directly into the application build:
*   **Google AI Studio Console:** Add your key to the project **Secrets panel** using the variable name `GEMINI_API_KEY`. It compiles automatically via `BuildConfig.GEMINI_API_KEY`.
*   **Local Properties Workspace:** If compiling from source on your workstation, add your credentials in `local.properties` at the project root:
    ```properties
    GEMINI_API_KEY=AIzaSy...your_gemini_api_key_here...
    ```

---

### Step 2: Configure & Launch the Proxy Server
1.  Open the application on your Android target device.
2.  Adjust host server specifications under the **GATEWAY CONFIGURATION** panel:
    *   **PORT:** Enter a dynamic listening port (e.g., `8080`).
    *   **GATEWAY API KEY:** Establish a password (e.g., `SecureProxyKey99`) to secure incoming requests and block unauthorized access from your local Wi-Fi network. Leaving this field blank skips client authorization checking.
    *   **PROVIDER:** Choose your active execution engine:
        *   `Cloud Gemini API`: Intercepts and routes OpenAI REST API requests to Gemini's cloud servers.
        *   `LiteRT-LM`: Runs on-device AI models offline with 100% data privacy. *Note: If model weights are not downloaded, the gateway rejects incoming requests with an HTTP 400 Bad Request.*
3.  Click **APPLY SETTINGS** to apply parameters instantly inside the Room database.
4.  Click **START SERVER**. The server status card will instantly display a green **"RUNNING"** state and show host IP details (e.g., `http://192.168.1.144:8080`).

---

### Step 3: Manage and Download On-Device LiteRT Models
To run local inference offline via LiteRT-LM, you can download model weight files directly onto your device storage:
1. Navigate to the **ON-DEVICE MODEL DIRECTORY** section.
2. View the clean, uncluttered visual directory cards representing each of the supported LiteRT-LM models. These cards focus purely on file status and download progress without redundant selection indicators, default markers, check icons, or interactive radio buttons.
3. Tap to expand the desired model's card to access weight location paths and status flags:
    *   The absolute path on your device file-system where weights are or will be stored.
    *   A file status badge: Gray **"NOT DOWNLOADED"** or green **"DOWNLOADED & READY"**.
4. Click the **DOWNLOAD FILE** action button on the expanded card.
5. A progress tracker will show the percentage progress and current download rate in real time.
6. Once downloaded, the system verifies the physical model file. The gateway dynamically resolves and targets this model file when requested by external client APIs (through the client's request model parameter or `/v1/models` API resolution).
7. Tap **COPY PATH** to copy the target weight destination path to your clipboard.

---

### Step 4: Dispatch Integration Calls (using cURL)
To verify correct routing and translation, launch a terminal on any computer connected to the same Wi-Fi subnetwork as your Android device, and run a test payload:

```bash
curl -X POST http://192.168.1.144:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SecureProxyKey99" \
  -d '{
    "model": "gemma-4-thinking",
    "messages": [
      {
        "role": "system",
        "content": "You are a helpful programming assistant."
      },
      {
        "role": "user",
        "content": "Verify if this calculate function is optimized: fun calculate(x: Int) = x * 2"
      }
    ],
    "temperature": 0.3
  }'
```

The server processes the payload and returns standard compliant JSON structure:

```json
{
  "id": "chatcmpl-b4e859fa21e2b4...",
  "object": "chat.completion",
  "created": 1779515541,
  "model": "gemma-4-thinking",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Yes, this function is highly optimized as it compiles down into a simple bitshift or fast multiply..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 42,
    "completion_tokens": 140,
    "total_tokens": 182
  }
}
```

If LiteRT-LM is selected but you have not downloaded the corresponding model weights, the gateway responds with an HTTP 400 Bad Request error payload:

```json
{
  "error": {
    "message": "Local LiteRT-LM model weights for 'litert-community/gemma-4-E2B-it-litert-lm' are not downloaded elements. Please download the weights first through the gateway application UI before choosing LiteRT-LM route.",
    "type": "model_not_found",
    "code": 400
  }
}
```

---

### Step 5: Real-time Traffic Auditing
1.  Review requests as they arrive in the **Traffic Logs** panel at the bottom of your screen.
2.  Successful transactions appear in green marked with HTTP code `200`. Rejected connections (e.g., missing API keys) report code `401`. Extremely large inputs exceeding restrictions display status code `413`.
3.  Tap any row to open the **Transaction Details Sheet**. This sheet reveals complete metadata timings, origin client IPs, full request inputs, and complete output payloads.
4.  Clear tracking archives at any time by pressing the trash bin icon (**CLEAR LOGS**), and safely shut down socket listeners by tapping the **STOP** button.

---

### Step 6: Engineering Local Verification
You can execute standard tests or capture screenshots locally using these commands:
*   **Run Standard Unit & Robolectric Tests:**
    ```bash
    gradle :app:testDebugUnitTest
    ```
*   **Verify Reference Screenshots via Roborazzi:**
    ```bash
    gradle :app:verifyRoborazziDebug
    ```
*   **Record Reference Screenshots:**
    ```bash
    gradle :app:recordRoborazziDebug
    ```

---

## 4. Diagnostics & Troubleshooting

### 4.1 InputDispatcher Channel Broken Warning
During active iteration or redeployment from your development workstation, you may observe the following log in Android Logcat:
```text
E/InputDispatcher: channel 'be574b0 com.aistudio.aiproxygateway.jxrqtm/com.example.MainActivity' ~ Channel is unrecoverably broken and will be disposed!
```
**Root Cause and Context:**
*   **Normal Process Lifecycle:** When a new build is deployed via Gradle, Android's Package Manager (PM) intentionally terminates the existing application process (`com.aistudio.aiproxygateway.jxrqtm`) to install and launch the updated version. 
*   **Expected OS Behavior:** When the process is terminated, the window rendering connection between Android's `InputDispatcher` and the client's window is broken suddenly. The OS prints this warning to indicate it is releasing the window's event resources. 
*   **No Functional Impact:** This is standard, harmless Android OS behavior during redeployments. It is not a crash, regression, or leak of the application itself.

### 4.2 Socket Port Bind Conflicts & Memory Safety
To ensure complete robustness across screen rotations, OS background memory purges, and hot updates:
1.  **Uniform Model Resource Disposal:** Tapping **STOP** or clearing the View-Model thread contexts instantly terminates the background socket, unbinding and releasing host ports.
2.  **Concurrency Safekeeping:** Employs synchronized Kotlin locks over `ServerSocket` re-binding instances, ensuring clean, predictable restarts without experiencing `BindException` issues.
3.  **Local Adapter Fallbacks:** Isolates loop network helpers via robust `try-catch Throwable` parameters, gracefully switching back to offline-loop adapters if network connectivity changes are detected.

---
*AI Proxy Gateway - Low Latency, Compliant, and High-Performance Android AI Middlewares.*
