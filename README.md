# AI Proxy Gateway (Professional Product Documentation & System Guide)

Welcome to the **AI Proxy Gateway**, a high-performance Android middleware designed to bridge standard OpenAI-compatible applications with Google Gemini models. This application runs entirely on an Android target (device or emulator), providing an ultra-low-latency local HTTP proxy server that intercepts standard OpenAI REST API requests, translates them to Google Gemini formats, forwards them to the active engine (**LiteRT-LM** or **Cloud Gemini APIs**), and returns the results back to the caller in perfect OpenAI format.

---

## 🔗 Platform Links & Live Resources

*   **Development App URL:** [https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Shared App Preview URL:** [https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Official Google Gemini API Reference:** [https://ai.google.dev/gemini-api/docs](https://ai.google.dev/gemini-api/docs)
*   **OpenAI Chat API Reference:** [https://developers.openai.com/api/reference/resources/chat](https://developers.openai.com/api/reference/resources/chat)
*   **OpenAI Chat Responses Reference:** [https://developers.openai.com/api/reference/resources/responses](https://developers.openai.com/api/reference/resources/responses)
*   **Google AI Edge - LiteRT Portal:** [https://ai.google.dev/edge/litert](https://ai.google.dev/edge/litert)
*   **LiteRT GitHub Repository:** [https://github.com/google-ai-edge/LiteRT](https://github.com/google-ai-edge/LiteRT)
*   **LiteRT-LM Developer Portal:** [https://ai.google.dev/edge/litert-lm](https://ai.google.dev/edge/litert-lm)
*   **LiteRT-LM Project Repository:** [https://github.com/google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
*   **Google AI Edge Overview:** [https://ai.google.dev/edge](https://ai.google.dev/edge)
*   **Google AI Edge Models Gallery:** [https://github.com/google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)
*   **Hostings-HuggingFace LiteRT Models Library:** [https://huggingface.co/litert-community](https://huggingface.co/litert-community)

---

## 1. Detailed Product Requirement Document (PRD)

### 1.1 Product Vision & Business Context
Many legacy, enterprise, and newly-engineered server integrations are built around the proprietary OpenAI API standard (`POST /v1/chat/completions`). Adopting alternative high-quality models (such as Google’s Gemini series) typically requires massive, invasive rewrites of existing production codebases. Additionally, routing high volumes of cloud AI requests introduces substantial operational API billing costs, high network dependency risks, and system compliance hurdles concerning user data leakage and data residency.

The **AI Proxy Gateway** solves these pain points by behaving as an intelligent local network hub on any Wi-Fi or local IP network:
*   **Zero-Invasive Integration:** Existing client software can simply alter their API base URL to the local Android gateway IP address and port (e.g., `http://192.168.1.144:8080/v1`). No client code rewrites are required.
*   **Cost Control & Offline Resiliency:** Teams can switch between secure **Cloud Gemini API** endpoints and on-device **LiteRT-LM** configurations with a single tap, shielding organizations from high API billing and supporting total offline functionality.
*   **Dynamic Provider Switching:** Teams can seamlessly switch routing modes directly under the **Gateway Configuration** panel. Tapping these choices updates a centralized configuration record in a thread-safe Room SQLite database. The live background socket handler actively reads this persistent state for every raw incoming HTTP client request, swapping the execution pipeline between OkHttp cloud tunnels and native local on-device processing instantly without requiring client updates, server restarts, or device reboots.
*   **Total Data Protection:** Highly confidential inputs can be translated and executed offline without sending any raw data outside the local area network (LAN).

### 1.2 Target Personas
*   **Network & Software Developers:** Require a seamless, standard, and highly compliant endpoint matching the exact OpenAI API specifications.
*   **System & Privacy Administrators:** Require clear traffic logging, request auditing, security token management, and detailed latency diagnostics.
*   **Field Operations Managers:** Want a simple mobile dashboard to run networks off-grid, monitor active transactions, and execute localized model files directly without cloud overheads.

### 1.3 Core Product Features & Requirements
*   **OpenAI v1 Emulation Protocol:** Implements standard REST endpoints:
    *   `POST /v1/chat/completions` (full parameter coverage for messages/contents, models, temperatures, token limits).
    *   `GET /v1/models` (exposes valid available local/cloud model IDs).
*   **Robust Multi-Provider Routing:**
    *   **Cloud Gemini:** Proxying inbound requests directly to online Google Gemini Models using standard API keys.
    *   **On-Device LiteRT-LM Engine:** Integrates direct execution of highly efficient local `*.litertlm` (LiteRT Language Model) weights downloaded or sideloaded across Android storage directories. When LiteRT-LM is enabled, standard OpenAI REST requests are processed directly on-device by loading model weights from local directories (e.g., inside Scoped or Shared Storage) on high-speed Android accelerators (GPU/CPU). If physical model files are not yet downloaded, the backend returns a clear, descriptive HTTP 400 Bad Request error specifying that model weights are missing and must be downloaded first. No fake simulations or mock fallbacks are used.
*   **LiteRT-LM Model Storage Path Manager:** Explicit directory tracking across Android storage directories (e.g. `/sdcard/Android/data/...`) to let downstream runtimes target downloadable files directly.
*   **Integrated Local Download Downloader:** A dynamic background download engine that fetches `*.litertlm` models direct from hosting locations, reports accurate fractional download speeds, saves them on Android storage targets, and automatically activates the downloaded model on completion.
*   **Live Sockets Lifecycle Controls:** Safe network server socket operations on dynamic ports configured via local UI.
*   **Real-time Traffic Auditing Console:** Persistent logging and inspection interface listing system responses, durations, path methods, performance indicators, and caller IP addresses.

### 1.4 Active Engine Comparison
To fit different operational, privacy, and cost profiles, teams can select among the two active engines on the gateway:

| Attribute | Cloud Gemini APIs (`CLOUD_GEMINI`) | Downloadable LiteRT-LM (`LOCAL_VAL`) |
| :--- | :--- | :--- |
| **Operational Mode** | Offloads processing to secure Google cloud servers. | Loads and executes model binaries natively on the device. |
| **Internet Dependency** | Yes (Active network internet/cellular connection is mandatory). | No (100% offline local loop operation). |
| **Hardware Overhead** | Minimal (Standard networking, minimal battery impact). | Moderate-High (Consumes CPU/GPU and system RAM based on model size). |
| **Data Residency** | Input transmitted securely to Google Gemini API servers. | 100% confidential. No data ever leaves the physical target device. |
| **Capabilities Profile** | Access to complex ultra-large models (e.g., Gemini 2.5 Pro / Flash). | Run highly-optimized small edge models (Gemma-3n/4, Qwen-2.5, DeepSeek-Distill). |
| **Cost Profile** | Standard API token billing rates of Google Cloud AI. | Free local compute. Absolute zero API token bills. |

---

## 2. Technical Architecture & Blueprint

### 2.1 System Architecture

The application uses an **MVVM (Model-View-ViewModel)** architectural pattern. Data streams bind reactively through Jetpack Compose state collection, and all intensive transactions are offloaded to asynchronous coroutine scopes.

```
       +--------------------------------------------+
       |             External Clients               |
       |       (Send OpenAI /v1/chat/completions)   |
       +---------------------+----------------------+
                             | [Wi-Fi LAN Socket Connection]
                             v
       +--------------------------------------------+
       |   [ProxyServerManager] Server Socket       | <---+ (Manage State Loop)
       +---------------------+----------------------+     |
                             |                            |
       +---------------------+----------------------+     | Controls Sockets
       |    [OpenAiToGeminiTranslator] Translates   |     | & Reads Configs
       |    - Inbound OpenAI JSON -> Gemini format  |     |
       |    - Outbound Gemini JSON -> OpenAI layout |     |
       +-----------+--------------------+-----------+     |
                   |                    |                 |
                   | (LiteRT-LM Mode)   | (Cloud Mode)    |
                   v                    v                 |
       +---------------------+ +--------------------+     |
       |  LiteRT-LM Engine   | | Google Gemini Cloud|     |
       | (On-Device weights) | |   REST Endpoint    |     |
       +---------------------+ +--------------------+     |
                                                          |
    ===================== STATE ENGINE =====================|
                                                          |
    +-------------------------------------------------+     |
    |               [GatewayViewModel]                | ----+
    |  - settingsState: StateFlow<ProxySetting?>      |
    |  - logsState: StateFlow<List<GatewayLog>>       |
    |  - isServerRunning, serverPort, serverIp        |
    |  - downloadProgress, downloadStatus             |
    +-----------------------+-------------------------+
                            | Dynamic UI Binding
                            v
    +-------------------------------------------------+
    |                [GatewayScreen]                  |
    |  - Material 3 Visual Controller Screen          |
    |  - Interactive Model Cards, Progress Indicators |
    +-------------------------------------------------+
```

---

### 2.2 Data Dictionary

The persistent system details are written to an SQLite database managed via the **Room ORM** layer (`AppDatabase`).

#### Table 1: `proxy_settings` (Updated to version 2)
This table encapsulates the core configuration state of the proxy server. For robust singleton operation, the ID row is hardcoded to `1`, ensuring exactly one operational settings instance is maintained.

| Column Name | Storage Type | Nullability | Constraints | Default Value | Functional Role & Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY` | `1` | Forces a singleton master settings record constraint. |
| `port` | `INTEGER` | `NOT NULL` | Range: `1024` - `65535` | `8080` | Port assigned to start the background proxy server socket. |
| `proxyApiKey` | `TEXT` | `NOT NULL` | None | `""` | Restricts client API access. Rejects requests lacking bearer matching. |
| `activeModelId` | `TEXT` | `NOT NULL` | None | `"litert-community/gemma-4-E2B-it-litert-lm"` | Active AI model target selection ID. |
| `targetProvider` | `TEXT` | `NOT NULL` | One of: `"CLOUD_GEMINI"`, `"LOCAL_VAL"` | `"CLOUD_GEMINI"` | Active routing provider strategy. |
| `geminiApiKey` | `TEXT` | `NOT NULL` | None | `""` | Device-stored custom Gemini API Key. Overrides BuildConfig keys. |

#### Table 2: `gateway_logs`
An audit trail record table archiving HTTP requests processed over the local Android loopback or Wi-Fi channel.

| Column Name | Storage Type | Nullability | Constraints | Default Value | Functional Role & Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY AUTOINCREMENT` | - | Unique incremental record index. |
| `timestamp` | `INTEGER` | `NOT NULL` | None | `System.currentTimeMillis()` | UTC timestamp in epoch milliseconds. |
| `method` | `TEXT` | `NOT NULL` | None | - | Received HTTP Action prefix (e.g., `"POST"`, `"GET"`, `"OPTIONS"`). |
| `path` | `TEXT` | `NOT NULL` | None | - | Visited address subpath (e.g., `"/v1/chat/completions"`). |
| `requestModel` | `TEXT` | `NOT NULL` | None | `"unknown-model"` | Target AI Model parsed from client payload. |
| `clientIp` | `TEXT` | `NOT NULL` | None | `"unknown"` | Origin IPv4 or IPv6 network address of remote caller. |
| `status` | `INTEGER` | `NOT NULL` | None | `200` | HTTP Response Status Code returned to client (e.g. `200`, `401`, `413`, `500`). |
| `durationMs` | `INTEGER` | `NOT NULL` | None | `0` | Absolute duration execution speed metric in milliseconds. |
| `responsePreview` | `TEXT` | `NOT NULL` | None | `""` | Summary sample of the processed response text. |
| `isAuthorized` | `INTEGER` | `NOT NULL` | `0` (false) / `1` (true) | `1` | Denotes whether client credentials evaluation passed checks. |

---

### 2.3 Detailed File-by-File Blueprint & Usage Analysis

A comprehensive mapping of each of the codebase files, outlining their direct responsibilities, input/output structures, and consumer references across the system.

```
┌───────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                             FILE INTER-DEPENDENCY MAP                                         │
├──────────────────────────────────────┬────────────────────────────────────────────────────────────────────────┤
│ FILE PATH                            │ DIRECT CONSUMER REFS / CALLED BY WHO                                   │
├──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────┤
│ AndroidManifest.xml                  │ System Launcher (Android Operating System Context)                     │
│ MainActivity.kt                      │ System Launcher Entrypoint                                             │
│ AppDatabase.kt                       │ GatewayViewModel, GatewayRepository                                    │
│ ProxySetting.kt                      │ AppDatabase, ProxySettingDao, GatewayRepository, GatewayViewModel      │
│ ProxySettingDao.kt                   │ AppDatabase, GatewayRepository                                         │
│ GatewayLog.kt                        │ AppDatabase, GatewayLogDao, GatewayRepository, GatewayViewModel        │
│ GatewayLogDao.kt                     │ AppDatabase, GatewayRepository                                         │
│ GatewayRepository.kt                 │ GatewayViewModel                                                       │
│ ModelsRegistry.kt                    │ GatewayScreen, GatewayViewModel, ProxyServerManager                    │
│ OpenAiToGeminiTranslator.kt          │ ProxyServerManager                                                     │
│ ProxyServerManager.kt                │ GatewayViewModel, MainActivity                                         │
│ GatewayViewModel.kt                  │ MainActivity, GatewayScreen                                            │
│ GatewayScreen.kt                     │ MainActivity                                                           │
│ ExampleRobolectricTest.kt            │ Android Unit Test Runner Environment                                   │
│ GreetingScreenshotTest.kt            │ Roborazzi Snapshot Test Engine                                         │
└──────────────────────────────────────┴────────────────────────────────────────────────────────────────────────┘
```

#### 1. `app/src/main/AndroidManifest.xml`
*   **Direct Role:** System configuration and capability permissions manifest.
*   **Direct Consumers:** Launched directly by the Android OS framework.
*   **Detailed Function:** Defines application launcher metadata. Declares vital system level permissions:
    *   `android.permission.INTERNET` to allow background server sockets to process network packets.
    *   `android.permission.ACCESS_NETWORK_STATE` and `android.permission.ACCESS_WIFI_STATE` to discover local Wi-Fi IP interfaces.
    *   Configures `usesCleartextTraffic="true"` to process unencrypted REST debugging payloads natively.

#### 2. `app/src/main/java/com/example/MainActivity.kt`
*   **Direct Role:** High-level UI Host Activity bootstrap.
*   **Direct Consumers:** Core system entrypoint launched by `AndroidManifest.xml`.
*   **Detailed Function:** Inherits from `ComponentActivity`. Hooks the modern Material 3 design systems Theme. Instantiates the system `GatewayViewModel`, and binds our single-screen user-interface container `GatewayScreen`. Ensures graceful setup and binds window insets.

#### 3. `app/src/main/java/com/example/data/AppDatabase.kt`
*   **Direct Role:** Singleton Room SQL Access Point.
*   **Direct Consumers:** Instantiated by `GatewayRepository` to distribute database capabilities.
*   **Detailed Function:** Defines persistent tables (`ProxySetting` and `GatewayLog`). Increments the SQLite configuration schema to **version 2** to integrate custom device-stored API keys, defining automated migrations to preserve stability across deployments.

#### 4. `app/src/main/java/com/example/data/ProxySetting.kt`
*   **Direct Role:** Data entity model representing gateway preferences.
*   **Direct Consumers:** Referenced by `AppDatabase`, `ProxySettingDao`, `GatewayRepository`, `GatewayViewModel`, `GatewayScreen`, and `ProxyServerManager`.
*   **Detailed Function:** Formulates fields for `port`, `proxyApiKey`, `activeModelId`, `targetProvider`, and `geminiApiKey` (overriding default keys contextwide). Restricts rows to `id = 1` to guarantee schema singleton compliance.

#### 5. `app/src/main/java/com/example/data/ProxySettingDao.kt`
*   **Direct Role:** Relational query mappings for system preferences.
*   **Direct Consumers:** Binds database hooks compiled by `AppDatabase`.
*   **Detailed Function:** Implements Kotlin `@Dao`. Houses query operations enabling reactive Flow streams of the system configuration, providing `upsert` and deletion wrappers.

#### 6. `app/src/main/java/com/example/data/GatewayLog.kt`
*   **Direct Role:** Request auditing and transaction data structure.
*   **Direct Consumers:** Referenced by `AppDatabase`, `GatewayLogDao`, `GatewayRepository`, `GatewayViewModel`, and `GatewayScreen`.
*   **Detailed Function:** Implements a strict `@Entity` marking for mapping caller IPs, latency values, method paths, targets, payload samples, and authentication checks.

#### 7. `app/src/main/java/com/example/data/GatewayLogDao.kt`
*   **Direct Role:** Relational query mapping for transit logs.
*   **Direct Consumers:** Bound by `AppDatabase` and called by `GatewayRepository`.
*   **Detailed Function:** Compiles transactional SQL strings. Includes `@Query("SELECT * FROM gateway_logs ORDER BY timestamp DESC LIMIT 100")` to supply live, reactive log vectors as a Kotlin flow stream.

#### 8. `app/src/main/java/com/example/data/GatewayRepository.kt`
*   **Direct Role:** Clean repository architectural pattern data broker.
*   **Direct Consumers:** Injected into `GatewayViewModel` to handle operational actions.
*   **Detailed Function:** Decouples direct DB actions from interface states. Manages background transaction updates and provides read synchronization.

#### 9. `app/src/main/java/com/example/data/ModelsRegistry.kt`
*   **Direct Role:** Manifest repository cataloging supported models.
*   **Direct Consumers:** Queried by `GatewayScreen`, `GatewayViewModel`, and `ProxyServerManager`.
*   **Detailed Function:** Registers available options (such as Gemma-3n, Gemma-4, functiongemma, etc.). Resolves exact on-device physical paths dynamically under file-explorer structures automatically using `getResolvedTargetFile(context)`.

#### 10. `app/src/main/java/com/example/ui/GatewayViewModel.kt`
*   **Direct Role:** Central state machine coordinating operations.
*   **Direct Consumers:** Binds UI actions launched in `GatewayScreen`.
*   **Detailed Function:** Governs visual states, active server status, network sockets, logs, and downloads:
    *   Launches long-running dynamic Http network downloads to fetch `*.litertlm` targets.
    *   Tracks fractional download speeds (Mb/s) and exposes real-time status flows.
    *   Directly handles the server life-cycle through safe thread execution.

#### 11. `app/src/main/java/com/example/ui/GatewayScreen.kt`
*   **Direct Role:** Modern Material 3 Jetpack Compose Interface.
*   **Direct Consumers:** Renders inside `MainActivity`.
*   **Detailed Function:** Formulates visual layout sections (Server Activity, Configuration Input Fields, Interactive Model Directory, Download Managers, Audit Logs, and Transaction Sheet overlays).

#### 12. `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`
*   **Direct Role:** Bidirectional REST translation processor.
*   **Direct Consumers:** Executed by `ProxyServerManager`.
*   **Detailed Function:** Maps inbound standard OpenAI JSON payloads onto Google Gemini API structures. Translates results back to the OpenAI schema, and includes support for parsing and compiling offline responses for native on-device LiteRT-LM executed targets (`generateLiteRtLmResponse`).

#### 13. `app/src/main/java/com/example/server/ProxyServerManager.kt`
*   **Direct Role:** Background network multi-threaded server socket.
*   **Direct Consumers:** Initialized, monitored, and stopped by `GatewayViewModel`.
*   **Detailed Function:** Runs socket connections, parses inputs, filters malicious streams, enforces authorization keys, verifies 10MB memory limits, intercepts CORS preflights, and routes processes to chosen provider pipelines (Google Gemini API or on-device LiteRT-LM).

#### 14. `app/src/test/java/com/example/ExampleRobolectricTest.kt`
*   **Direct Role:** Fast local unit tests.
*   **Direct Consumers:** Run locally in CI/CD pipeline or workstation testing.
*   **Detailed Function:** Leverages Robolectric to mock Android runtime architectures locally, verifying settings persistence and correct initialization.

#### 15. `app/src/test/java/com/example/GreetingScreenshotTest.kt`
*   **Direct Role:** Automated visual screenshot test.
*   **Direct Consumers:** Called by Roborazzi engines.
*   **Detailed Function:** Launches layout components, rendering exact graphics to capture and spot layout changes before deployment.

---

## 3. Step-by-Step User Guide (How to Integrate and Operate)

Follow this step-by-step pipeline to configure credentials, manage downloadables, launch the proxy server, and verify transactions with test payloads.

### Step 1: Configure Gemini API Keys

To route API traffic to Google Cloud Gemini servers (`CLOUD_GEMINI`), the proxy requires an online API developer secret. You can configure this key using either of two secure methods:

#### Method A: Direct In-App Settings Form (Recommended)
1. Open the application on your Android target device.
2. Scroll to the **GATEWAY CONFIGURATION** section.
3. Locate the **Device-Stored Gemini API Key (Optional)** field.
4. Paste your Gemini API Developer Token directly into this field.
5. Tap **SHOW** to review the entered token, or **HIDE** to secure it.
6. Click **APPLY SETTINGS**. This action stores the API key directly inside the on-device Room SQLite database. The gateway will prioritize this key for all future cloud-routing transactions.

#### Method B: Project Workspace Environment Config
Alternatively, you can compile and bundle development credentials directly into the application build:
*   **Google AI Studio Console:** Add your key to the project **Secrets panel** using the variable name `GEMINI_API_KEY`. It compiles automatically via `BuildConfig.GEMINI_API_KEY`.
*   **Local Properties Workspace:** If compiling from source on your workstation, add your credentials in `local.properties` at the project root:
    ```properties
    GEMINI_API_KEY=AIzaSy...your_gemini_api_key_here...
    ```

---

### Step 2: Configure & Wake the Proxy Server

1.  Launch the **AI Proxy Gateway** on your Android device.
2.  Adjust configuration parameters in the **GATEWAY CONFIGURATION** card:
    *   **PORT:** Enter a dynamic listening port (e.g. `8080`).
    *   **GATEWAY API KEY:** Establish a gateway password (e.g., `SecureProxyKey99`) to block unauthorized external clients on your Wi-Fi network. Leaving this setting blank skips client authorization checking.
    *   **PROVIDER:** Choose your active execution engine:
        *   `Cloud Gemini API`: Route requests online to Google cloud servers.
        *   `LiteRT-LM`: Run local downloaded `*.litertlm` models with 100% offline security. Note: If the weight files are not downloaded yet, the gateway will return an HTTP 400 Bad Request error to callers instead of executing simulated fallbacks.
3.  Click the **APPLY SETTINGS** button in the layout. This updates parameters instantly inside the Room database.
4.  Launch the listener by clicking the **START SERVER** button. The server card will instantly display a green **"RUNNING"** status and display host IP details (e.g., `http://192.168.1.144:8080`).

---

### Step 3: Manage and Download LiteRT Model Files (*.litertlm)

For offline LiteRT execution, you can download model weight files directly onto your device storage:
1. Navigate to the **ON-DEVICE GATED MODELS** section.
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

To verify the gateway is routing correctly, launch a terminal on any computer connected to the same Wi-Fi subnetwork and run a test payload:

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

### Step 6: Running Automated Tests

You can run Unit and Screenshot tests locally on your development system using these Gradle commands:

*   **To Run Standard Unit & Robolectric Tests:**
    ```bash
    gradle :app:testDebugUnitTest
    ```
*   **To Verify Screenshot Tests for Visual Regressions:**
    ```bash
    gradle :app:verifyRoborazziDebug
    ```
*   **To Record New Screen Visual Snapshots:**
    ```bash
    gradle :app:recordRoborazziDebug
    ```

---

## 4. Diagnostics & Troubleshooting

### 4.1 Understanding the InputDispatcher Channel Broken Warning
During active iteration or hot-redeploying of the APK from your development workstation, you may observe the following log in Android Logcat:
```text
E/InputDispatcher: channel 'be574b0 com.aistudio.aiproxygateway.jxrqtm/com.example.MainActivity' ~ Channel is unrecoverably broken and will be disposed!
```
**Root Cause and Context:**
*   **Normal Process Lifecycle:** When a new build is deployed via Gradle, Android's Package Manager (PM) intentionally terminates the existing application process (`com.aistudio.aiproxygateway.jxrqtm`) to install and launch the updated version. 
*   **Expected OS Behavior:** When the process is terminated, the window rendering connection between Android's `InputDispatcher` and the client's window is broken suddenly. The OS prints this warning to indicate it is releasing the window's event resources. 
*   **No Functional Impact:** This is standard, harmless Android OS behavior during redeployments. It is not a crash, regression, or leak of the application itself.

### 4.2 High-Reliability Socket Lifecycle Management
To ensure a robust, leak-free development lifecycle when the OS reinstalls the app or recreates the Activity (e.g., during screen rotations), the gateway implements the following architectural safeguards:
1.  **Unified Socket Release:** When the View Model is cleared during recreation or termination, the `onCleared()` callback instantly calls `serverManager.stopServer()`, ensuring all open network ports are unbound.
2.  **Atomic Reboot Transactions:** The configuration panel utilizes synchronized Mutex locking (`serverMutex.withLock`) inside `ProxyServerManager` to close and rebind server sockets sequentially. This prevents race conditions or "Port already in use" (`BindException`) crashes during hot updates.
3.  **Local Loopback Fallbacks:** Network helper routines are fully wrapped in safe network exception handlers (`try-catch Throwable`), falling back gracefully to standard local loopback configurations if Wi-Fi adapters or hardware peripherals are modified.

---
*AI Proxy Gateway - Low Latency, Compliant, and High-Performance Android AI Middlewares.*
