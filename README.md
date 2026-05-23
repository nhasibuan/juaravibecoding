# ATS AI Proxy Gateway (Documentation & User Guide)

Welcome to the **ATS AI Proxy Gateway**, a high-performance Android middleware built specifically for modern **Applicant Tracking Systems (ATS)**. This application bridges the gap between traditional enterprise recruiting systems—which strictly require OpenAI-compatible REST API formats—and modern, high-speed, cost-effective Google Gemini models (both local on-device simulation/LiteRT-LM and secure cloud-hosted Gemini APIs).

---

## 🔗 Platform Links & Live Resources

*   **Development App URL:** [https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Shared App Preview URL:** [https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Official Google Gemini API Reference:** [https://ai.google.dev/gemini-api/docs](https://ai.google.dev/gemini-api/docs)

---

## 1. Product Requirement Document (PRD)

### 1.1 Product Vision & Business Context
Human Resource and recruiting departments utilize **Applicant Tracking Systems (ATS)** to parse resumes, review qualifications, grade code challenges, match roles, and schedule interviews. However, processing thousands of resumes through proprietary external cloud models introduces two massive business friction points:
1.  **Astronomical LLM API Costs:** Direct-to-consumer standard billing models quickly scale up as resume files grow in length and context.
2.  **Strict Privacy Regulations (GDPR, CCPA, APEC):** Transmitting raw personal data containing candidate names, phone numbers, home addresses, employment records, and historical compensation over external cloud pipelines results in high compliance and security overheads.

The **ATS AI Proxy Gateway** addresses this by functioning as a secure, local, inter-office network proxy server running on an Android platform. By hosting this lightweight server on a dedicated Android device (or high-capacity local emulator) inside the company's internal network:
*   Inbound screening processes can hit local IP addresses via standard OpenAI API formatting (`POST /v1/chat/completions`).
*   The gateway translates payloads to the **Google Gemini API** or executes them fully offline using an immersive **On-Device Mock Simulator** or **LiteRT-LM** configurations with zero network overhead.

### 1.2 User Personas
*   **HR Admin / Recruiter:** Needs a reliable, simple visual screen to see if the translation service is running, register connected clients, verify the system's live statuses, and inspect candidate evaluation results in real-time.
*   **IT & Compliance Officer:** Requires local operational logging, strict network binding options, custom authentication key checks to verify only authorized corporate software is calling the gateway, and a clear audit log of traffic.
*   **ATS Integration Engineer:** Needs a seamless, drop-in replacement endpoint matching the exact OpenAI specification so they only have to modify the `baseURL` parameter in their existing ATS code.

### 1.3 Core Product Capabilities
*   **OpenAI v1 Emulation Protocol:** Full emulation of `POST /v1/chat/completions` and `GET /v1/models`. Direct parsing of incoming variables (`model`, `messages`, `temperature`, `max_tokens`/`max_completion_tokens`).
*   **Deep JSON Payload Translation:**
    *   **Inbound Extraction:** Extracts messages and handles both modern structures (standard strings) and multi-part complex text array requests (`type = "text"`). Correctly segments roles, turning `"system"` instructions into explicit Gemini `systemInstruction` configurations, and `"user"` / `"assistant"` messages into valid `contents` lists with model alignment.
    *   **Outbound Reconstruction:** Wraps the Gemini responses/token counts inside a standard OpenAI output schema containing `id`, `object`, `created`, `choices` with message contents, and calculated `usageMetadata` variables to prevent client crash issues.
*   **Multi-Provider Strategy Routing:**
    *   **CLOUD_GEMINI:** Proxies requests directly to Google Gemini models using API key configurations securely bound within AI Studio Secrets.
    *   **MOCK / SIMULATOR:** Runs a fully offline simulation mode that formats responses locally instantly. Features thinking step tags (`<think>...</think>`) imitating distilled reasoning models like DeepSeek-R1 to emulate multi-stage applicant screening.
*   **Interactive Gateway Server Lifecycle:** Dynamic Start/Stop socket management on custom network ports (e.g., `8080`, `9000`), automatically detecting device network interfaces (WiFi, cellular, or local loopbacks) to broadcast the access address.
*   **Real-time Traffic Auditing Console:** Instant terminal-style inspection list displaying HTTP request statistics, route processing times, responses, and security parameters.

### 1.4 Non-Functional Requirements (Compliance & Performance)
*   **Resource Resiliency:** Severe content-length threshold checks (limiting body input payload sizes to **10MB**) to preserve device memory.
*   **Threading Safety:** Strict segregation of active sockets, persistent Room queries, and memory-allocated IP sweeps onto background `Dispatchers.IO` threads to ensure a crash-free experience.
*   **Low Footprint UI:** Responsive Material 3 design conforming to fluid, accessible screen density layouts.

---

## 2. Technical Architecture & Blueprint

### 2.1 Architecture Blueprint

The application uses an **MVVM (Model-View-ViewModel)** design combined with unidirectional data flows (UDF) powered by Jetpack Compose.

```
       +--------------------------------------------+
       |            External ATS Clients            |
       |       (Send OpenAI /v1/chat/completions)   |
       +---------------------+----------------------+
                             | [WiFi LAN Socket]
                             v
       +--------------------------------------------+
       |   [ProxyServerManager] Server Socket       | <---+ (Auto-started)
       +---------------------+----------------------+     |
                             |                            |
       +---------------------+----------------------+     | Controls Sockets
       |    [OpenAiToGeminiTranslator] Translates   |     | & Reads Configs
       |    - Inbound OpenAI keys -> Gemini REST     |     |
       |    - Outbound Gemini REST -> OpenAI layout  |     |
       +-----------+--------------------+-----------+     |
                   |                    |                 |
                   | (Mock Mode)        | (Cloud Mode)    |
                   v                    v                 |
       +---------------------+ +--------------------+     |
       |   On-Device Local   | | Google Gemini Cloud|     |
       |  Simulation Engine  | |   REST Endpoint    |     |
       +---------------------+ +--------------------+     |
                                                          |
  ===================== STATE ENGINE =====================|
                                                          |
  +-------------------------------------------------+     |
  |               [GatewayViewModel]                | ----+
  |  - settingsState: StateFlow<ProxySetting?>      |
  |  - logsState: StateFlow<List<GatewayLog>>       |
  |  - isServerRunning/serverPort/serverIp          |
  +-----------------------+-------------------------+
                          | Displays
                          v
  +-------------------------------------------------+
  |                [GatewayScreen]                  |
  |  - Jetpack Compose Material 3 UI Layout         |
  |  - Config Forms, Audit Logs, Log Details Sheet   |
  +-------------------------------------------------+
```

---

### 2.2 Data Dictionary

This application utilizes an SQLite database managed via **Room ORM** (`AppDatabase`) to maintain persistence configuration and historical auditing information.

#### Table I: `proxy_settings`
Strictly holds the operational parameters of the AI Proxy Gateway. By business design, this table maintains **exactly one persistent row** (`id = 1`) to ensure there is never a conflict with multiple active ports or security keys.

| Field Name | Storage Data Type | Nullability | Constraints | Default Value | Functional Role / Business Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY` | `1` | Strictly locks this record to `id = 1` for single-record system operations. |
| `port` | `INTEGER` | `NOT NULL` | Min: `1024`, Max: `65535` | `8080` | Local socket server port. Ports under 1024 are restricted to bypass rooted device requirements. |
| `proxyApiKey` | `TEXT` | `NOT NULL` | - | `""` | Restricts access to your gateway. When set, requests must carry an `Authorization: Bearer <key>` header. |
| `activeModelId` | `TEXT` | `NOT NULL` | - | `"litert-community/gemma-4-E2B-it-litert-lm"` | Active model identifier key. Selectable from system model directory. |
| `targetProvider` | `TEXT` | `NOT NULL` | Either `"CLOUD_GEMINI"`, `"LOCAL_VAL"`, or `"MOCK"` | `"CLOUD_GEMINI"` | Sets target routing architecture. |

#### Table II: `gateway_logs`
An audit trail recording all processed HTTP traffic handled by the proxy gateway.

| Field Name | Storage Data Type | Nullability | Constraints | Default Value | Functional Role / Business Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY AUTOINCREMENT` | - | Unique system ID for the logs. |
| `timestamp` | `INTEGER` | `NOT NULL` | - | `System.currentTimeMillis()` | UTC Epoch millisecond timestamp of incoming transaction. |
| `method` | `TEXT` | `NOT NULL` | - | - | HTTP Method (e.g., `"POST"`, `"GET"`, `"OPTIONS"`). |
| `path` | `TEXT` | `NOT NULL` | - | - | Endpoint hit by the external system (e.g., `"/v1/chat/completions"`). |
| `requestModel` | `TEXT` | `NOT NULL` | - | `"unknown-model"` | Model requested inside the client content payload. |
| `clientIp` | `TEXT` | `NOT NULL` | - | `"unknown"` | IPv4 address of the caller machine. |
| `status` | `INTEGER` | `NOT NULL` | - | `200` | HTTP status code returned to the client (e.g., `200`, `401`, `413`, `500`). |
| `durationMs` | `INTEGER` | `NOT NULL` | - | `0` | Computational round-trip speed in milliseconds. |
| `responsePreview` | `TEXT` | `NOT NULL` | - | `""` | Extracted snippet copy of the final completion answer. |
| `isAuthorized` | `INTEGER` | `NOT NULL` | `0` or `1` | `1` | Boolean audit checking whether the client passed authentication. |

---

### 2.3 Use of the `AndroidManifest.xml` File

The configuration file `/app/src/main/AndroidManifest.xml` directs how Android declares resources, allows activities to start, and handles hardware accesses.
*   **Internet Access Configuration:**
    `AndroidManifest.xml` declares `<uses-permission android:name="android.permission.INTERNET" />`. This is mandatory since the app initiates external SSL queries to Google Gemini cloud servers, and starts a server background socket to accept incoming LAN clients.
*   **Network Status Configuration:**
    Declares `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />` and `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` to dynamically discover active WiFi network state, obtain local addresses, and listen to connectivity hooks.
*   **Uses Cleartext Traffic Configuration:**
    Sets `android:usesCleartextTraffic="true"` on the `<application>` node to safely negotiate unencrypted HTTP communication channels on local network IPs inside local development networks.
*   **Launch Orientation Activity Mode:**
    The `.MainActivity` is declared with `android:exported="true"`, marking it as the primary entry point containing the category `android.intent.category.LAUNCHER`.

---

### 2.4 Detailed File-by-File Blueprint Analysis

This section analyzes the specific functional roles and structural logic of each file in this project:

#### 1. `app/src/main/AndroidManifest.xml`
*   **Direct Role:** System manifest registering application metadata.
*   **Implementation Specs:** Configures application permissions, points launcher icons to adaptive paths, and registers the entry activity `com.example.MainActivity`.

#### 2. `app/src/main/java/com/example/MainActivity.kt`
*   **Direct Role:** Main system entrypoint, managing standard activity lifecycle states.
*   **Implementation Specs:** Inherits from `ComponentActivity`. Automatically triggers `enableEdgeToEdge()` on birth. Calls `setContent { MyApplicationTheme { ... } }` to initialize Jetpack Compose, binds the `GatewayViewModel`, and wraps it in a screen-padding safe Material 3 layout container.

#### 3. `app/src/main/java/com/example/data/AppDatabase.kt`
*   **Direct Role:** Main Room relational database class.
*   **Implementation Specs:** Extends `RoomDatabase`. Declares `ProxySetting` and `GatewayLog` database entities. Provides abstract methods for retrieval DAOs. Implements thread-safe singleton initialization with destructive migrations fallback.

#### 4. `app/src/main/java/com/example/data/GatewayLog.kt`
*   **Direct Role:** Room Database data model mapping representing traffic logging tables.
*   **Implementation Specs:** Declared as `@Entity(tableName = "gateway_logs")`. It represents individual client queries, and encapsulates all performance metrics.

#### 5. `app/src/main/java/com/example/data/GatewayLogDao.kt`
*   **Direct Role:** Data Access Object for traffic log storage.
*   **Implementation Specs:** `@Dao` interface. Defines `@Query("SELECT * FROM gateway_logs ORDER BY timestamp DESC LIMIT 100")` to stream log history back reactive-style via a `Flow<List<GatewayLog>>`.

#### 6. `app/src/main/java/com/example/data/GatewayRepository.kt`
*   **Direct Role:** Coordinates and aggregates underlying Room database reads and writes.
*   **Implementation Specs:** Clean repository model. Exposes `Flow` streams for configuration updates and live audit streams, preventing high-concurrency DB collisions.

#### 7. `app/src/main/java/com/example/data/ModelsRegistry.kt`
*   **Direct Role:** Central database metadata dictionary compiling approved AI models on Android.
*   **Implementation Specs:** Standard `object`. Predefines details for Gemini Nano (via AICore) and LiteRT-LM (Gemma 4 E2B/E4B, Gemma 3n, Qwen 2.5, DeepSeek R1 Distill, function-calling variants). Maps memory requirements, file sizes, thinking paths, and modality properties.

#### 8. `app/src/main/java/com/example/data/ProxySetting.kt`
*   **Direct Role:** Primary settings entity.
*   **Implementation Specs:** Annotated with `@Entity(tableName = "proxy_settings")`. Uses a fixed ID (`Primary Key = 1`) to ensure there is never more than 1 persistent system configuration saved.

#### 9. `app/src/main/java/com/example/data/ProxySettingDao.kt`
*   **Direct Role:** DAO managing the proxy configuration database row.
*   **Implementation Specs:** Provides asynchronous inserts/updates using `OnConflictStrategy.REPLACE`, keeping configuration state atomic.

#### 10. `app/src/main/java/com/example/ui/GatewayViewModel.kt`
*   **Direct Role:** Primary view controller managing user interactions, database states, dynamic UI inputs, and coordinating background socket worker activities safely away from the main thread.
*   **Implementation Specs:** Contains `settingsState` and `logsState` using hot `stateIn(SharingStarted.WhileSubscribed(5000))` structures to automatically pause database collection when the application goes to the background. Coordinates settings updates, model switching, and log clearing cleanly off the main thread using scoped coroutines and `Dispatchers.IO` contexts to ensure UI responsiveness.

#### 11. `app/src/main/java/com/example/ui/GatewayScreen.kt`
*   **Direct Role:** The Material 3 frontend user interface.
*   **Implementation Specs:** A high-contrast dashboard with responsive components. Consists of:
    *   **Server Controls Panel:** Displays port indicators, active addresses, copy actions, and toggle controls.
    *   **Settings Editor Section:** Interactive fields for ports, authorization keys, and dropdown selection cards.
    *   **Log Viewer Section:** Clean lists detailing traffic log entries with quick-clear capabilities.
    *   **Details Bottom Sheet Dialog:** Expands to show details of selected logs including performance data, payloads, and response previews.

#### 12. `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`
*   **Direct Role:** Real-time data translation mapper converting JSON payloads.
*   **Implementation Specs:**
    *   `translateRequest(openAiJson: String)`: Extracts standard OpenAI arguments, maps temperatures, formats system directives, and converts roles to build Gemini cloud payloads.
    *   `translateResponse(geminiJson: String, openAiModel: String)`: Extracts candidate tokens and content texts, builds choices objects, and maps token metrics back to OpenAI formats.
    *   `generateSimulatedResponse(openAiJson: String, openAiModel: String)`: Generates dynamic developer and testing mocks if network access is missing. Simulates reasoning traces using structural thought layouts (`<think>...</think>`).

#### 13. `app/src/main/java/com/example/server/ProxyServerManager.kt`
*   **Direct Role:** High-availability background socket engine handling network traffic.
*   **Implementation Specs:** Runs background loops listening on server socket ports. Handles client handshakes, validates authorizations, processes preflight CORS OPTIONS requests, detects and rejects payloads larger than 10MB (payload limit), and coordinates response translation or mocking. All IO operations run securely scheduled in isolated coroutines.

#### 14. `app/src/test/java/com/example/ExampleRobolectricTest.kt`
*   **Direct Role:** Robolectric JVM operational test class.
*   **Implementation Specs:** Verifies fundamental operations of the system on local developer machines without starting heavy device emulators. Tests activity launch and application resources retrieval.

#### 15. `app/src/test/java/com/example/GreetingScreenshotTest.kt`
*   **Direct Role:** Roborazzi automated visual regression test.
*   **Implementation Specs:** Renders Compose UI interfaces, captures high-fidelity screenshots, and saves visual states to verify layout alignment.

---

## 3. Step-by-Step User Guide (How to Integrate with ATS Systems)

Follow this end-to-end setup guide to start the proxy, configure your options, run terminal tests, and check operations:

### Step 1: Secure Cloud Credential Configuration
For CLOUD_GEMINI mode, the gateway needs a secure path to contact Google Gemini servers.
1.  Navigate to your Google AI Studio dashboard.
2.  Generate a standard API Developer Token.
3.  Add it to your AI Studio project **Secrets** panel using the variable key name: `GEMINI_API_KEY`.
4.  At runtime, the project compilation automatically builds and injects this key into `BuildConfig.GEMINI_API_KEY`, keeping all source code clean and secure.

---

### Step 2: Configure & Wake the Android Gateway Server
1.  Launch the **AI Proxy Gateway** on your Android device or emulator.
2.  Review the configuration widgets in the main dashboard:
    *   **PORT:** Establish a custom port (e.g., `8080`).
    *   **GATEWAY API KEY:** Set an optional verification key (e.g., `SecureATSKey99`). External clients must supply this as a bearer token.
    *   **PROVIDER:** Select **CLOUD_GEMINI** for cloud resolution or **MOCK / SIMULATOR** for rapid, zero-cost local trials.
    *   **TARGET MODEL:** Pick from the list of registered models in the selection dropdown window.
3.  Click the **APPLY SETTINGS** button to securely save configurations to the local Room database interface.
4.  Activate the gateway by clicking the **START SERVER** button. The server status indicator instantly flashes a cyan **"RUNNING"** message.
5.  Check the connection cards below the status widget. Real-time network readers will output the complete accessible endpoint addresses, e.g., `http://192.168.1.144:8080/v1/chat/completions`. Tap the **COPY** action icon to save this URL to your clipboard.

---

### Step 3: Integrate & Test from your Business System (using cURL)
To verify the gateway is reachable, open a terminal on another computer connected to the same local WiFi network as the Android device and fire a test payload:

```bash
curl -X POST http://192.168.1.144:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SecureATSKey99" \
  -d '{
    "model": "deepseek-r1-distill",
    "messages": [
      {
        "role": "system",
        "content": "You are a professional recruiting coordinator. Extract summary stats from candidate data."
      },
      {
        "role": "user",
        "content": "Analyze Candidate: Alice Smith. Qualifications: 8 Years Kotlin, Jetpack Compose, Room Database. Grade?"
      }
    ],
    "temperature": 0.2
  }'
```

The terminal will receive an OpenAI-compatible JSON response mapping token usage metrics and mock thoughts:
```json
{
  "id": "chatcmpl-a8b9c...fdf5",
  "object": "chat.completion",
  "created": 1779515124,
  "model": "deepseek-r1-distill",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "<think>\n1. User is asking: \"Analyze Candidate: Alice Smith... Grade?\"\n2. Models: Local Simulator using deepseek-r1-distill\n</think>\nAlice Smith is graded as an elite Senior Android Developer profile..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 34,
    "completion_tokens": 120,
    "total_tokens": 154
  }
}
```

---

### Step 4: Live Transaction Auditing
1.  Verify transactions directly inside the Android app under the **Traffic Logs** dashboard list.
2.  Successful queries appear as clean list rows marked with a green checkmark and HTTP code `200`. Invalid keys show HTTP `401`. Extremely large bodies show HTTP `413`.
3.  Tap any row. An inspection sheet displays duration speeds, client IP addresses, complete payload queries, and full response texts.
4.  Click the **CLEAR LOGS** trash bin icon in the header to safely delete database audit records.
5.  Shut down the gateway at any time by pressing the **STOP** button to release local socket connections safely.

---
*ATS AI Proxy Gateway Documentation - Secure, Cost-Effective, Compliant Middlewares.*
