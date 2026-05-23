# AI Proxy Gateway (Documentation & User Guide)

Welcome to the **AI Proxy Gateway**, a high-performance Android middleware designed to bridge standard OpenAI-compatible applications with Google Gemini models. This application runs entirely on an Android target (device or emulator), providing a local HTTP proxy server that intercepts standard OpenAI REST API requests, translates them to Google Gemini formats, forwards them to the active engine (local simulation, downloadable LiteRT-LM, or Cloud Gemini APIs), and translates the results back to the caller in perfect OpenAI format.

---

## 🔗 Platform Links & Live Resources

*   **Development App URL:** [https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Shared App Preview URL:** [https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Official Google Gemini API Reference:** [https://ai.google.dev/gemini-api/docs](https://ai.google.dev/gemini-api/docs)

---

## 1. Product Requirement Document (PRD)

### 1.1 Product Vision & Business Context
Many legacy, enterprise, and newly-engineered server integrations are built around the proprietary OpenAI API standard (`POST /v1/chat/completions`). Adopting other AI services often requires massive, invasive rewrites of existing codebases. Alternatively, routing high volumes of cloud AI requests introduces substantial operational API billing costs, high network dependency risks, and compliance hurdles concerning clear data residency and user data leakage.

The **AI Proxy Gateway** solves this problem by behaving as a local network hub on any Wi-Fi or local IP network:
*   **Zero-Invasive Integration:** Existing client software can simply alter their API base URL to the local Android gateway IP address (e.g., `http://192.168.1.144:8080/v1`).
*   **Cost Control & Offline Resiliency:** Teams can switch between secure **Cloud Gemini API** endpoints and **On-Device Local Simulation / LiteRT-LM** configurations with a single tap, shielding organizations from high api bills and supporting total offline functionality.
*   **Total Data Protection:** Highly confidential inputs can be translated and executed offline without sending any raw data outside the local area network (LAN).

### 1.2 User Personas
*   **Network & Software Developers:** Require a seamless, standard, and highly compliant endpoint matching the exact OpenAI API specifications.
*   **System & Privacy Administrators:** Require clear traffic logging, request auditing, security token management, and detailed latency diagnostics.
*   **Field Operations Managers:** Want a simple mobile dashboard to run networks off-grid, monitor active transactions, and execute localized model files directly without cloud overheads.

### 1.3 Core Product Features & Requirements
*   **OpenAI v1 Emulation Protocol:** Implements standard REST endpoints:
    *   `POST /v1/chat/completions` (full parameter coverage for messages/contents, models, temperatures, token limits).
    *   `GET /v1/models` (exposes valid available local/cloud model IDs).
*   **Robust Multi-Provider Routing:**
    *   **Cloud Gemini:** Proxying inbound requests directly to online Google Gemini Models using standard API keys.
    *   **On-Device Mock Simulator:** A zero-dependency local simulation framework. Simulates deep chain-of-thought outputs using structural thought blocks (`<think>...</think>`) and mimics high-speed responses.
*   **LiteRT-LM Model Storage Path Manager:** Explicit directory tracking across Android storage directories (e.g. `/sdcard/Android/data/...`) to let downstream runtimes target downloadable files directly.
*   **Integrated Local Download Downloader:** A dynamic background download engine that fetches *.litertlm models direct from hosting locations, reports accurate fractional download speeds, saves them on Android storage targets, and automatically activates the downloaded model on completion.
*   **Live Sockets Lifecycle Controls:** Safe network server socket operations on dynamic ports configured via local UI.
*   **Real-time Traffic Auditing Console:** Persistent logging and inspection interface listing system responses, durations, path methods, performance indicators, and caller IP addresses.

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
                   | (Mock Mode)        | (Cloud Mode)    |
                   v                    v                 |
       +---------------------+ +--------------------+     |
       |   Local Developer   | | Google Gemini Cloud|     |
       |  Simulation Engine  | |   REST Endpoint    |     |
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
   |  - Model Selector Radio Cards, Progress Indicators|
   +-------------------------------------------------+
```

---

### 2.2 Data Dictionary

The persistent system details are written to an SQLite database managed via the **Room ORM** layer (`AppDatabase`).

#### Table 1: `proxy_settings`
This table encapsulates the core configuration state of the proxy server. For robust singleton operation, the ID is hardcoded to `1`, ensuring exactly one operational settings instance is maintained.

| Column Name | Storage Type | Nullability | Constraints | Default Value | Functional Role & Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY` | `1` | Forces a singleton master settings record constraint. |
| `port` | `INTEGER` | `NOT NULL` | Range: `1024` - `65535` | `8080` | Port assigned to start the background proxy server socket. |
| `proxyApiKey` | `TEXT` | `NOT NULL` | None | `""` | Restricts client API access. Rejects requests lacking bearer matching. |
| `activeModelId` | `TEXT` | `NOT NULL` | None | `"litert-community/gemma-4-E2B-it-litert-lm"` | Active AI model target selection ID. |
| `targetProvider` | `TEXT` | `NOT NULL` | One of: `"CLOUD_GEMINI"`, `"MOCK"` | `"CLOUD_GEMINI"` | Active routing provider strategy. |

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

### 2.3 Detailed File-by-File Blueprint Analysis

This section analyzes the exact role, responsibilities, and structural code implementation of each of the 15 system files in this project:

#### 1. `app/src/main/AndroidManifest.xml`
*   **Direct Role:** Application Manifest configuration and system capabilities declaration file.
*   **Functional Implementation Details:** Binds essential runtime permissions and application configurations:
    *   `<uses-permission android:name="android.permission.INTERNET" />` to establish local sockets and make outgoing API calls.
    *   `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />` and `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` to read Wi-Fi network states and discover local IP addresses.
    *   Sets `android:usesCleartextTraffic="true"` to process unencrypted local HTTP payloads on local Wi-Fi scopes.
    *   Registers `com.example.MainActivity` as the primary launcher activity.

#### 2. `app/src/main/java/com/example/MainActivity.kt`
*   **Direct Role:** Primary Android Activity holding the window content frame and coordinating Jetpack Compose setup.
*   **Functional Implementation Details:** Inherits from `ComponentActivity`. Configures full edge-to-edge window display characteristics (`enableEdgeToEdge()`). Binds the UI using `setContent`, injecting the modern design system theme wrapper, acquiring the `GatewayViewModel` instance, and anchoring standard screen constraints.

#### 3. `app/src/main/java/com/example/data/AppDatabase.kt`
*   **Direct Role:** Room SQLite abstraction class representing the database hub.
*   **Functional Implementation Details:** Inherits from `RoomDatabase`. Registers the schema entities (`ProxySetting::class`, `GatewayLog::class`). Declares internal database getter access methods: `proxySettingDao()` and `gatewayLogDao()`. Employs thread-safe Companion Object logic with synchronized volatile builder instances to maintain safe app-wide database connections.

#### 4. `app/src/main/java/com/example/data/GatewayLog.kt`
*   **Direct Role:** Relational data model representing traffic audit indexes.
*   **Functional Implementation Details:** Denotes a clean `@Entity(tableName = "gateway_logs")` database table mapping all the transaction tracking attributes described in the Data Dictionary.

#### 5. `app/src/main/java/com/example/data/GatewayLogDao.kt`
*   **Direct Role:** Data Access Object for logging table CRUD updates.
*   **Functional Implementation Details:** annotated with `@Dao`. Streamlines SQL statements including:
    *   `@Query("SELECT * FROM gateway_logs ORDER BY timestamp DESC LIMIT 100")` to push live, persistent log streams as a reactive `Flow<List<GatewayLog>>`.
    *   `@Insert` to append incoming traffic records.
    *   `@Query("DELETE FROM gateway_logs")` to purge historical traffic database details.

#### 6. `app/src/main/java/com/example/data/GatewayRepository.kt`
*   **Direct Role:** Data broker layer decoupling VM operations from raw DB actions.
*   **Functional Implementation Details:** Performs thread-safe reads and writes on Room tables. Exposes standard getters/setters for configuration states and outputs flow pipelines targeting historical request records.

#### 7. `app/src/main/java/com/example/data/ModelsRegistry.kt`
*   **Direct Role:** Compilation metadata dictionary indexing verified Android models.
*   **Functional Implementation Details:** Defines a static list of models, tracking parameters like storage file names, parameters (e.g. 2B or 4B sizes), and specific destination directories. 
    *   Also includes the dynamic helper property `targetFilePath: String`, which accurately resolves destination paths in Android directory scopes (e.g., `/sdcard/Android/data/com.google.ai.edge.gallery/files/...`) based on current model properties.

#### 8. `app/src/main/java/com/example/data/ProxySetting.kt`
*   **Direct Role:** Entity wrapping proxy operating configurations.
*   **Functional Implementation Details:** Annotated with `@Entity(tableName = "proxy_settings")`. Includes fields targetProvider, proxyApiKey, activeModelId and port, and forces primary key constraint `id = 1` to guarantee singleton instance rules.

#### 9. `app/src/main/java/com/example/data/ProxySettingDao.kt`
*   **Direct Role:** Configuration record DAO interface.
*   **Functional Implementation Details:** Annotated with `@Dao`. Declares `@Query("SELECT * FROM proxy_settings WHERE id = 1")` to flow reactive setting rows. Employs conflict-free updates using `OnConflictStrategy.REPLACE`.

#### 10. `app/src/main/java/com/example/ui/GatewayViewModel.kt`
*   **Direct Role:** Main view state controller coordinating asynchronous tasks and network operations.
*   **Functional Implementation Details:** Manages reactive states for local proxy settings, running diagnostics, and traffic lists:
    *   Exposes `isServerRunning`, `serverPort`, and `serverIp` flows which react dynamically to physical network status.
    *   Implements settings application operations mapping inputs context-safely onto `Dispatchers.IO` threads.
    *   Maintains download states `downloadProgress: Flow<Map<String, Float>>` and `downloadStatus: Flow<Map<String, String>>`.
    *   Integrates the **Model Download Engine** (`downloadModel(model)`): uses long-running `HttpURLConnection` threads to stream raw byte arrays from online hosting sources to storage directories (`model.targetFilePath`), notifying progress percentages in real time. Upon download completion, it triggers `changeActiveModel` to assign the newly fetched model.

#### 11. `app/src/main/java/com/example/ui/GatewayScreen.kt`
*   **Direct Role:** Screen visual design layout utilizing Material 3 Jetpack Compose.
*   **Functional Implementation Details:** Organizes the full developer-focused layout:
    *   **Dashboard Server Status Card:** Renders dynamic network properties, client loops, and connection strings.
    *   **Config Form Segment:** Form fields to tweak local server ports, security keys, and router options.
    *   **Interactive Adaptive Selector:** Renders interactive cards featuring a **RadioButton selector** next to each registered model, indicating active system targeting.
    *   **Progress Indicators & Downloader Cards:** Renders the download panel for LiteRT models, displaying path copying hooks, current storage status (e.g., "DOWNLOADED & READY" vs "NOT DOWNLOADED"), linear-progress bars, download action trigger buttons, and error messages.
    *   **Traffic Log console & BottomSheet Details Dialog:** An auditing pane displaying response statuses, rendering a clean custom sheet containing transaction diagnostics, timings, request objects, and outputs.

#### 12. `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`
*   **Direct Role:** REST request and response schema mapping engine.
*   **Functional Implementation Details:** Fully translates request structures bidirectionally:
    *   `translateRequest(...)`: Parses standard OpenAI structures (`messages`, `temperature`, `max_tokens`), and converts systematic text payloads to build valid cloud Gemini JSON API calls.
    *   `translateResponse(...)`: Extracts generated cloud response strings and calculated stats, and maps text parts, role structures, and completion parameters to OpenAI `choices` schemas.
    *   `generateSimulatedResponse(...)`: Powers mock simulations. Translates responses locally when offline, rendering thinking traces wrapped in `<think>...</think>` markup imitating reasoning models (like DeepSeek-R1) to test system loops without real-world API token billing.

#### 13. `app/src/main/java/com/example/server/ProxyServerManager.kt`
*   **Direct Role:** Core background multi-thread socket proxy engine.
*   **Functional Implementation Details:** Coordinates background loops running secure client loops:
    *   Listens on user-defined ports, accepting socket client handshakes dynamically.
    *   Validates authorization headers, processes CORS preflight OPTIONS requests, and limits incoming HTTP bodies to **10MB** to safeguard device memory.
    *   Implements error-resilient exception handlers and wraps teardown scenarios inside `withContext(NonCancellable)` scopes to release ports immediately on cancellation.

#### 14. `app/src/test/java/com/example/ExampleRobolectricTest.kt`
*   **Direct Role:** Local JVM JVM Unit test verifying app initialization properties.
*   **Functional Implementation Details:** Runs inside local test environments using Robolectric to quickly check if activity instances and essential target assets process normally without starting a physical emulator.

#### 15. `app/src/test/java/com/example/GreetingScreenshotTest.kt`
*   **Direct Role:** Automated Roborazzi visual screenshot snapshot and visual regression test.
*   **Functional Implementation Details:** Leverages Roborazzi to launch Compose components, rendering precise interface graphics, and compares reference frames to verify consistent layouts.

---

## 3. Step-by-Step User Guide (How to Integrate and Operate)

Follow this complete integration pipeline to configure the cloud keys, download local models, launch the background network proxy, and dispatch local integration tests.

### Step 1: Configure Secure Cloud Credentials
If referencing **CLOUD_GEMINI** routing, your proxy needs a valid key path to query Google servers.
1.  Navigate to your Google AI Studio Dashboard.
2.  Create a standard API Developer Token.
3.  Add it to your AI Studio project **Secrets panel** using the key name: `GEMINI_API_KEY`.
4.  At build time, the project compilation automatically packages this secret, exposing it dynamically in code through `BuildConfig.GEMINI_API_KEY`.

---

### Step 2: Configure & Waking the Proxy Server
1.  Launch the **AI Proxy Gateway** on your Android device (or via the web developer emulator).
2.  Adjust operating parameters under the **GATEWAY CONFIGURATION** section:
    *   **PORT:** Input an appropriate network listening port (e.g., `8080` (Default)).
    *   **GATEWAY API KEY:** Establish an authorization password (e.g., `SecureProxyKey99`). Leaving this empty skips validation.
    *   **PROVIDER:** Select the active execution mode (**CLOUD_GEMINI** for live endpoints or **MOCK / SIMULATOR** for sandbox evaluations).
3.  Click the **APPLY SETTINGS** button in the layout, which updates the local Room database parameters.
4.  Launch the background listener by clicking the **START SERVER** button. The server card will instantly flash a green **"RUNNING"** status.
5.  View the active connection card containing your device IP network access addresses, e.g., `http://192.168.1.144:8080/v1/chat/completions`. Tap the **COPY** button to copy this address.

---

### Step 3: Manage and Download LiteRT Model Files (*.litertlm)
For local LiteRT configurations, you can download model binaries inside the dashboard:
1.  Expand the **TARGET MODELS DIRECTORY** dropdown.
2.  View the target models. Standard LiteRT models display an interactive diagnostic card showing:
    *   📂 The local Android **target storage directory path**.
    *   A status badge: either gray **"NOT DOWNLOADED"** (if the file doesn't exist) or green **"DOWNLOADED & READY"** (if local storage checks pass).
3.  Click the **DOWNLOAD FILE** action button.
4.  The card switches to download mode: a live percentage status displays the progress alongside an active linear progress bar tracker.
5.  On completion, the file is saved to target storage directory, the indicator lights up as green **"DOWNLOADED & READY"**, and the selector **automatically activates** that model as your current default target.
6.  You can easily click the **COPY PATH** button to get the absolute path to paste into downstream offline code engines.

---

### Step 4: Dispatch Integration Calls (using cURL)
To verify the gateway is routing correctly, launch a terminal on any computer connected to the same Wi-Fi subnetwork and run a test payload:

```bash
curl -X POST http://192.168.1.144:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SecureProxyKey99" \
  -d '{
    "model": "deepseek-r1-distill",
    "messages": [
      {
        "role": "system",
        "content": "You are a helpful programming assistant. Analyze this block of code."
      },
      {
        "role": "user",
        "content": "Does this function look optimized? fun calculate(x: Int) = x * 2"
      }
    ],
    "temperature": 0.3
  }'
```

The server processes the payload and returns standard compliant JSON structure:

```json
{
  "id": "chatcmpl-b4e85...9fa2",
  "object": "chat.completion",
  "created": 1779515541,
  "model": "deepseek-r1-distill",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "<think>\nDetermining calculations optimizations.\nInput is: fun calculate(x: Int) = x * 2\n</think>\nYes, this function is highly optimized as it utilizes a single mathematical operation..."
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

---

### Step 5: Real-time Transaction Auditing
1.  Check processed requests in the **Traffic Logs** panel at the bottom of your screen.
2.  Successful transactions appear in green marked with HTTP code `200`. Rejected connections (e.g. wrong key usage) report code `401`. Extremely large inputs exceeding restrictions display status code `413`.
3.  Tap any row to open the **Transaction Details Sheet**. This sheet reveals complete metadata timings, origin client IPs, full request inputs, and complete output payloads.
4.  Clear tracking archives at any time by pressing the trash bin icon (**CLEAR LOGS**), and safely shut down socket listeners by tapping the **STOP** button.

---
*AI Proxy Gateway - Low Latency, Compliant, and High-Performance Android AI Middlewares.*
