# AI Proxy Gateway — Professional Product Documentation & System Blueprint

Welcome to the official developer documentation and system guide for the **AI Proxy Gateway**. This high-performance Android utility acts as an ultra-low-latency local HTTP proxy server running directly on any Android device or emulator. The gateway bridges legacy client applications using the standard OpenAI specification (`POST /v1/chat/completions` and `GET /v1/models`) with high-performance local on-device **LiteRT-LM** models or secure **Google Gemini Cloud APIs**.

---

## 🔗 Live Resources & Ecosystem Portals
*   **Official Google Gemini API Reference:** [https://ai.google.dev/gemini-api/docs](https://ai.google.dev/gemini-api/docs)
*   **OpenAI Chat API Reference:** [https://developers.openai.com/api/reference/resources/chat](https://developers.openai.com/api/reference/resources/chat)
*   **Google AI Edge - LiteRT Portal:** [https://ai.google.dev/edge/litert](https://ai.google.dev/edge/litert)
*   **LiteRT-LM Developer Portal:** [https://ai.google.dev/edge/litert-lm](https://ai.google.dev/edge/litert-lm)
*   **HuggingFace LiteRT Models Library:** [https://huggingface.co/litert-community](https://huggingface.co/litert-community)

---

## 1. Product Requirement Document (PRD)

### 1.1 Product Vision & Business Context
Modern software development contains massive installations of legacy, enterprise, and newly-engineered systems that consume AI resources strictly via the proprietary OpenAI API standard (`POST /v1/chat/completions`). Adopting alternative, high-performance engines (such as Google's Gemini family) typically demands expensive, invasive, and potentially destabilizing rewrites of production code. Additionally, transmitting all application prompts to cloud AI endpoints introduces recurrent API subscription bills, network latency overhead, and strict data-privacy compliance hurdles.

The **AI Proxy Gateway** directly solves these engineering roadblocks by delivering an intelligent offline-first local network hub:
*   **Zero-Invasive Direct-Drop Replacements:** Legacy software client applications point their API base URL to the local Android gateway IP address and port (e.g., `http://192.168.1.144:8080/v1`). No structural code rewrites are required.
*   **Enterprise Cost Containment & Off-Grid Utility:** System administrators swap routing strategies instantly between **Cloud Gemini APIs** and local on-device **LiteRT-LM** models. Harnessing local compute shielding optimizes organizational budgets and guarantees flawless off-grid functionality.
*   **Dynamic Provider Hot-Swapping:** Swapping the active routing engine dynamically updates persistent configuration parameters in a reactive Room SQLite database. The background thread listening loop processes changes per request, dynamically redirecting between OkHttp cloud pipelines and native local on-device weight files without restart overhead.
*   **Hard Data Protection:** Inputs are processed directly on-device using local weights, meaning high-security user prompts are restricted to physical local storage and never leave the host system boundaries.

### 1.2 Target Personas
*   **Software Engineers & Mobile Integration Leads:** Require highly stable, standards-compliant server mock-endpoints mirroring OpenAI payloads.
*   **Data Privacy & Compliance Architects:** Require full diagnostic logs, security bearer check restrictions, custom CORS capabilities, and precise generation latencies.
*   **On-Grid/Off-Grid Technicians & Hobbyists:** Desire a lightweight, intuitive mobile dashboard to administer local proxies, manage model downloads, analyze performance, and review real-time audit consoles.

### 1.3 Core Features & Functional Requirements
1.  **OpenAI v1 Emulation Protocol:**
    *   `POST /v1/chat/completions`: Deeply parses, maps, and translates OpenAI payload tokens, configuration variables, and history frames to Google-equivalent API structures, outputting standard compliance JSON models.
    *   `GET /v1/models`: Queries the registry schema, active states, and runtime conditions to output clean lists of supported endpoints.
2.  **Dual-Route Execution Pipelines:**
    *   **Cloud Gemini Routing:** Tunnels traffic to public Google Gemini API endpoints utilizing developer keys loaded securely via local Room tables or `.env` workspace variables.
    *   **On-Device LiteRT-LM Local Inference:** Leverages the official Google **`com.google.ai.edge.litertlm:litertlm-android`** package to run local inference. It matches execution specs faithfully, writing a `system_fingerprint` containing true, factual performance data (e.g., `litertlm:gpu:245ms:cache=hit` or `litertlm:cpu:102ms:cache=miss`).
    *   **Missing Models Protection:** If target weights are physically missing, the gateway rejects client prompts with a clean HTTP 400 Bad Request detailing missing elements instead of using mock simulations.
3.  **On-Demand Model Configuration UI:** Expanding any catalog card allows users to make selections, triggering visual highlight changes and binding active elements in local databases.
4.  **Local Model Fetcher & Storage Fallbacks:** Automatically streams giant model weight files from remote servers directly into modern Android-managed internal files, displaying fractional downloading rates (Mb/s) in real-time.
5.  **Logging Audit Console:** Captures caller parameters, client IP addresses, execution speeds, HTTP status signals, and authorization parameters.

---

## 2. Technical Architecture Blueprint & System Specifications

### 2.1 System Architecture Topology
The physical interaction pipeline, reactive events, and data translations are mapped in the architectural flow diagram below:

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
                  |   - Extracts Local Engine Requests for LiteRT   |     |
                  +------------------------+------------------------+     |
                                           |                              |
                                           +---> [ModelRouter]            |
                                                     |                    |
                    +--------------------------------+                    |
                    | (LiteRT-LM Mode)                                    | (Cloud Mode)
                    v                                                     v
+------------------------------------+                  +-----------------+------+-----+
|         LiteRtLmEngine             |                  |     Google Gemini Cloud      |
|    (On-device State Engine)        |                  |        REST Endpoint         |
|  Loads weights locally on device   |                  |    (Uses secure API key)     |
+-----------------+------------------+                  +------------------+-----------+
                  |                                                       |
                  v                                                       v
                  +------------------------+------------------------------+
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

### 2.2 Relational Data Dictionary
All dynamic gateway configurations and historical analytics are managed in local SQLite files using the Android **Room Database** framework.

#### Table 1: `proxy_settings`
Manages global system configurations. This table is strictly constrained by a primary key layout supporting **exactly one record (`id = 1`)** to prevent state pollution.

| Column Name | SQLite Data Type | Nullability | Primary/Foreign Key | Default Value | Functional Role & Physical Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY` (Must equal `1`) | `1` | Enforces a single master configuration record in the system. |
| `port` | `INTEGER` | `NOT NULL` | None | `8080` | Network port on which the proxy engine spawns its listener socket. |
| `proxyApiKey` | `TEXT` | `NOT NULL` | None | `""` | User-defined gateway bearer token. If set, rejects unauthorized calls with HTTP 401. |
| `activeModelId` | `TEXT` | `NOT NULL` | None | `"litert-community/gemma-4-E2B-it-litert-lm"` | Stores the designated default model ID utilized for local model operations. |
| `targetProvider` | `TEXT` | `NOT NULL` | None | `"CLOUD_GEMINI"` | The active routing module string configuration (`"CLOUD_GEMINI"` or `"LOCAL_VAL"`). |
| `geminiApiKey` | `TEXT` | `NOT NULL` | None | `""` | On-device Gemini token. Overrides system `.env` keys if defined. |
| `bypassGpu` | `INTEGER` | `NOT NULL` | None | `0` (False) | Forces the engine compiles to bypass GPU delegates, reverting to CPU kernels. |
| `enableNpuBackend`| `INTEGER` | `NOT NULL` | None | `0` (False) | Opt-in boolean flag enabling highly accelerated edge NPU workloads. |

#### Table 2: `gateway_logs`
Tracks diagnostic caller data to audit payload parameters, throughput latencies, and authorization states in real-time.

| Column Name | SQLite Data Type | Nullability | Primary/Foreign Key | Default Value | Functional Role & Physical Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY` (Auto-increment) | `0` | Automated incremental ID index. |
| `timestamp` | `INTEGER` | `NOT NULL` | None | `System.currentTimeMillis()` | UTC epoch timestamp capturing request initiation. |
| `method` | `TEXT` | `NOT NULL` | None | None | Captures caller HTTP method structures (`POST`, `GET`, etc.). |
| `path` | `TEXT` | `NOT NULL` | None | None | Target URL path parsed under this call (`/v1/chat/completions` or `/v1/models`). |
| `requestModel` | `TEXT` | `NOT NULL` | None | None | Raw model identifier extracted from incoming caller content payloads. |
| `clientIp` | `TEXT` | `NOT NULL` | None | None | Captures caller IP addresses to track network connections. |
| `status` | `INTEGER` | `NOT NULL` | None | None | HTTP Response status signal code (e.g., `200`, `400`, `401`, `500`). |
| `durationMs` | `INTEGER` | `NOT NULL` | None | None | Performance throughput timeframe calculated from parsing to dispatch. |
| `responsePreview` | `TEXT` | `NOT NULL` | None | None | Trimmed excerpt of the first 100 characters of the response payload. |
| `isAuthorized` | `INTEGER` | `NOT NULL` | None | None | Audits whether gateway security checks were successfully validated (`0` or `1`). |

#### DB Schema Migrations History
To prevent data wipes from destructive reconstructions (`fallbackToDestructiveMigration()`), schema upgrades are executed sequentially:
1.  **`MIGRATION_1_2`**: Upgrades schema from version 1 to 2 by dynamically instantiating user-specific overriding keys:
    ```sql
    ALTER TABLE proxy_settings ADD COLUMN geminiApiKey TEXT NOT NULL DEFAULT ''
    ```
2.  **`MIGRATION_2_3`**: Upgrades schema from version 2 to 3 by dynamically introducing hardware NPU optimization switches:
    ```sql
    ALTER TABLE proxy_settings ADD COLUMN enableNpuBackend INTEGER NOT NULL DEFAULT 0
    ```

---

### 2.3 Detailed File-by-File Source Mapping
The complete codebase is mapped systematically below across all 22 core components, explaining their functional responsibilities, upstream calling actors, and interacting downstream dependencies:

#### 1. `app/src/main/AndroidManifest.xml`
*   **Responsibility & Capabilities:** Defines the application’s operating parameters to the Android OS. It declares the application ID, service scopes, UI launchers, and grants network and background privileges (`android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`).
*   **Used By:** Android System Launcher, Google Play Store, Android Package Manager.
*   **Uses/Interactivity:** Instantiates `com.example.MainActivity` as the primary entry point activity, binds application label parameters to resource strings (`@string/app_name`), and configures the launcher icons.

#### 2. `app/build.gradle.kts`
*   **Responsibility & Capabilities:** Orchestrates build automation, target SDK compiling parameters (set to SDK compile/target 36), Room annotations, KSP compilers, and groups Maven dependencies (such as Jetpack Compose, Room, and the official Google `litertlm-android` package).
*   **Used By:** Gradle Compiler Daemon, Android SDK Compiler.
*   **Uses/Interactivity:** Defines namespace structures (`com.example`), sets the `applicationId` to `com.aistudio.aiproxygateway`, and injects sensitive security secrets from root-level files.

#### 3. `app/src/main/java/com/example/inference/AiCoreEngine.kt`
*   **Responsibility & Capabilities:** Defines the client interface implementation managing Google on-device system services (Gemini Nano) via classpath inspections. It contains dynamic JVM reflection checks targeting `com.google.android.gms.ai.AiFeatureManager` to dynamically detect if GMS-provided on-device service configurations are runnable.
*   **Used By:** `com.example.server.ModelRouter` (during routing checks of registered `RuntimeType.AICORE` models).
*   **Uses/Interactivity:** Suspends requests sequentially via `runBlocking` / Coroutines, validation systems, and system configurations.

#### 4. `app/src/test/java/com/example/inference/AiCoreEngineTest.kt`
*   **Responsibility & Capabilities:** A local JUnit JVM unit test compiling under Robolectric. It verifies classpaths, tests error routines when the Google on-device SDK is missing, and examines how loading systems respond to runtime exceptions.
*   **Used By:** Gradle Test Runner, CI Verification Systems.
*   **Uses/Interactivity:** Targets `com.example.inference.AiCoreEngine` to verify error management profiles without emulator runtime demands.

#### 5. `app/src/main/java/com/example/inference/LiteRtLmEngine.kt`
*   **Responsibility & Capabilities:** A high-performance local singleton execution container managing Google's LiteRT C++ native core. It dynamically handles thread contexts, initializes `Capabilities`, loads weight files from filesystems, configures runtime accelerators (GPU/NPU/CPU), and provides a thread-safe synchronized `Mutex` lock layer to safely schedule on-device model inquiries.
*   **Used By:** `com.example.server.ModelRouter` (to execute local processing pipelines).
*   **Uses/Interactivity:** Interacts with the underlying binary file structures, utilizes Room settings tables via `ProxySetting` objects to align NNAPI accelerators, and exposes state structures.

#### 6. `app/src/main/java/com/example/MainActivity.kt`
*   **Responsibility & Capabilities:** The host Android UI entry activity class. It initializes Jetpack Edge-to-Edge configurations (`enableEdgeToEdge()`) and bootstraps the View tree structure.
*   **Used By:** Android OS, Device Launcher.
*   **Uses/Interactivity:** Instantiates and links `com.example.ui.GatewayViewModel` and hooks UI layouts into `com.example.ui.GatewayScreen`.

#### 7. `app/src/main/java/com/example/data/AppDatabase.kt`
*   **Responsibility & Capabilities:** Defines the Room Database configuration. It declares the tables mapping (`ProxySetting`, `GatewayLog`), instantiates volatile JVM instances, and defines explicit migration matrices (`MIGRATION_1_2` and `MIGRATION_2_3`).
*   **Used By:** `com.example.data.GatewayRepository`, `com.example.MainActivity`.
*   **Uses/Interactivity:** Exposes standard DAO entry points `proxySettingDao()` and `gatewayLogDao()` to access SQLite engines.

#### 8. `app/src/main/java/com/example/data/GatewayLog.kt`
*   **Responsibility & Capabilities:** Database entity modeling schema for audit parameters. Encapsulates caller timestamp ranges, routing methods, target client IPs, total throughput latencies, and authorization flags.
*   **Used By:** `com.example.data.AppDatabase`, `com.example.data.GatewayLogDao`, `com.example.data.GatewayRepository`.
*   **Uses/Interactivity:** Stores immutable call results generated down the request pipeline.

#### 9. `app/src/main/java/com/example/data/GatewayLogDao.kt`
*   **Responsibility & Capabilities:** Abstract Data Access Object mapping SQL operators directly onto the physical database storage layers. Defines insertions, limits, and dynamic deletion routines for history records.
*   **Used By:** `com.example.data.AppDatabase`, `com.example.data.GatewayRepository`.
*   **Uses/Interactivity:** Interacts with the `gateway_logs` table, exposing direct reactive Kotlin `Flow` wrappers.

#### 10. `app/src/main/java/com/example/data/GatewayRepository.kt`
*   **Responsibility & Capabilities:** The Repository pattern implementation. Acts as a unified mediator between database entities, UI viewmodels, and background proxy components.
*   **Used By:** `com.example.ui.GatewayViewModel`, `com.example.server.ProxyServerManager`.
*   **Uses/Interactivity:** Holds direct references to `ProxySettingDao` and `GatewayLogDao`, exposing cohesive configuration flows.

#### 11. `com/example/data/ModelsRegistry.kt`
*   **Responsibility & Capabilities:** Enforces a rigid catalog of allowed models. Maps 15 registered models to their respective properties (`modelId`, `RuntimeType`, aliases, and experimental indicators), preventing runtime errors.
*   **Used By:** `com.example.server.ModelRouter`, `com.example.ui.GatewayViewModel`, `com.example.ui.GatewayScreen`.
*   **Uses/Interactivity:** Hardcodes configuration bounds for on-device structures and exposes alias arrays such as `gpt-4o-mini`.

#### 12. `app/src/main/java/com/example/data/ProxySetting.kt`
*   **Responsibility & Capabilities:** Database data model for system configurations. It encapsulates active port selections, security gateway keys, target models, API providers, and GPU/NPU performance preferences.
*   **Used By:** `com.example.data.AppDatabase`, `com.example.data.ProxySettingDao`, `com.example.data.GatewayRepository`, `com.example.ui.GatewayViewModel`.
*   **Uses/Interactivity:** Instantiated directly in memory to dynamically adjust routing parameters per call.

#### 13. `app/src/main/java/com/example/data/ProxySettingDao.kt`
*   **Responsibility & Capabilities:** Data Access Object administering global proxy configurations. Features unique SQL queries supporting database insertions, singleton fetches, and reactive parameter tracking.
*   **Used By:** `com.example.data.AppDatabase`, `com.example.data.GatewayRepository`.
*   **Uses/Interactivity:** Controls and updates single records mapped to the `proxy_settings` table.

#### 14. `app/src/main/java/com/example/ui/GatewayViewModel.kt`
*   **Responsibility & Capabilities:** Business logic coordinator. Exposes reactive streams (`StateFlow`), handles background server socket threads, tracks model downloading, computes fractions of downloading speeds (Mb/s), and manages UI events.
*   **Used By:** `com.example.ui.GatewayScreen`, `com.example.MainActivity`.
*   **Uses/Interactivity:** Holds references to standard `GatewayRepository` wrappers, handles network downloading loops, and monitors active configuration profiles.

#### 15. `app/src/main/java/com/example/ui/GatewayScreen.kt`
*   **Responsibility & Capabilities:** Jetpack Compose layout building. Compiles Material 3 layout hierarchies featuring deep slate visual themes, server status badges, expandable catalog panels, real-time downloaders, and logging records.
*   **Used By:** `com.example.MainActivity`.
*   **Uses/Interactivity:** Observes `GatewayViewModel` state flows and binds semantic interactions.

#### 16. `app/src/main/java/com/example/server/ModelRouter.kt`
*   **Responsibility & Capabilities:** The central decision routing engine. Resolves incoming requested model IDs (such as `gpt-4o-mini` or individual LiteRT options) into target destinations.
*   **Used By:** `com.example.server.ProxyServerManager`.
*   **Uses/Interactivity:** Evaluates local directories and model weights, checks Gemini credentials, and throws descriptive nested `RoutingError` exceptions when assets are missing.

#### 17. `app/src/test/java/com/example/server/ModelRouterTest.kt`
*   **Responsibility & Capabilities:** Highly comprehensive JVM local tests mapping 14 critical cases targeting resolution checks (verifying correct mappings for aliases, routing path decisions, and missing resource errors).
*   **Used By:** Gradle, CI Engines.
*   **Uses/Interactivity:** Executes tests against `ModelRouter` and mock resources.

#### 18. `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`
*   **Responsibility & Capabilities:** An structural data adapter. Converts incoming OpenAI parameters and history payloads to Gemini compatible API blocks, and translates outputs back into compliant response templates.
*   **Used By:** `com.example.server.ProxyServerManager`.
*   **Uses/Interactivity:** Uses `org.json` to parse structures, extracts system metadata, and formats Server-Sent Events (SSE) chunks.

#### 19. `app/src/main/java/com/example/server/ProxyServerManager.kt`
*   **Responsibility & Capabilities:** Sockets driver managing background server thread cycles. It starts TCP socket services, handles client connection pools, validates custom authorization tokens, performs robust downstream JSON body syntax validation (reclaiming control and rejecting malformed inputs to ensure compliance), routes requests through appropriate engines, and generates HTTP response wrappers or Server-Sent Events (SSE) loops.
*   **Used By:** `com.example.ui.GatewayViewModel`.
*   **Uses/Interactivity:** Evaluates `ModelRouter`, logs metrics through `GatewayRepository`, formats data via translation blocks, and connects LiteRT or OkHttp REST backends.

#### 20. `app/src/test/java/com/example/ExampleRobolectricTest.kt`
*   **Responsibility & Capabilities:** Bootstraps basic application tests under Robolectric to verify system initialization processes and resource bindings.
*   **Used By:** Gradle test executors.
*   **Uses/Interactivity:** Loads target resources to confirm context loads.

#### 21. `app/src/test/java/com/example/GreetingScreenshotTest.kt`
*   **Responsibility & Capabilities:** Implements UI verification pipelines using Roborazzi. Validates core UI layouts to catch unintended visual changes.
*   **Used By:** Roborazzi verification pipelines.
*   **Uses/Interactivity:** Exercises target interface components under simulation profiles to capture layout representations.

#### 22. `app/src/test/java/com/example/server/ProxyServerManagerTest.kt`
*   **Responsibility & Capabilities:** Highly robust functional unit test executing under Robolectric. Validates local server socket communication, JSON validation logic, and proper rejection of syntactically broken inputs with HTTP 400 Bad Request error.
*   **Used By:** Gradle test runner, CI verification pipelines.
*   **Uses/Interactivity:** Connects loopback sockets to target server instances to verify parsing compliance.

---

### 2.4 Software Engineering Best Practices & Design Patterns
*   **Repository Pattern (Single Source of Truth):** `GatewayRepository` abstracts access to database sources, acting as a buffer between the underlying Android Room storage APIs and UI ViewModel layers.
*   **Adapter / Translator Pattern:** `OpenAiToGeminiTranslator` translates OpenAI JSON inputs to compliant Google Gemini shapes, decoupling client specs from underlying provider implementations.
*   **MVVM (Model-View-ViewModel) Pattern:** Clear division of responsibilities ensures Compose UI modules (`GatewayScreen`) remain clean, observing state reactively from `GatewayViewModel`.
*   **Singleton Pattern:** Native compute-intensive processes (`LiteRtLmEngine`, `AiCoreEngine`) are restricted to single shared instances to manage memory footprints and prevent concurrent thread collisions.
*   **Strategy & Resolution Patterns:** `ModelRouter.resolve` determines routing behaviors based on the physical state of the underlying system, cleanly matching incoming models to cloud or local engines.
*   **Reactive & Event-Driven Patterns:** State changes are propagated from database models through the repositories to ViewModels via Kotlin `StateFlow` and `SharedFlow`.
*   **Concurrency & Synchronized locks:** `LiteRtLmEngine` safeguards C++ libraries using robust Coroutines `Mutex` lock patterns, queuing multiple prompts gracefully.

---

## 3. Step-by-Step User Guide

### 3.1 Step 1: Provisioning Cloud Credentials
If you wish to utilize Google's cloud APIs (`CLOUD_GEMINI`), developers can configure authorization tokens via two pathways:

#### Pathway A: Real-Time In-App Input (Highly Recommended)
1. Run the application on your Android target device.
2. Scroll to the **GATEWAY CONFIGURATION** section.
3. Locate the card labeled **Device-Stored Gemini API Key (Optional)**.
4. Paste your Google AI platform key.
5. Click **APPLY SETTINGS** to write parameters to the Room DB.

#### Pathway B: Bundle Secrets via Project Environment Config
The workspace utilizes the Secrets Gradle Plugin, configured to read from `.env` compiled directly inside internal configurations:
1. Create a file named `.env` in the root folder of the project.
2. Store your token:
   ```properties
   GEMINI_API_KEY=AIzaSyYourGeminiApiKeyHere
   ```

---

### 3.2 Step 2: Running the Proxy Server
1. Click the **PORT** configuration box and set your desired port (e.g., `8080`).
2. Establish a **GATEWAY API KEY** token (e.g., `SecureToken112`) to authenticate incoming socket calls. Leaving this blank disables client authorization checks.
3. Tap **PROVIDER** and toggle to select either `Cloud Gemini API` or `LiteRT-LM`.
4. Click **APPLY SETTINGS** to write changes to local SQLite systems.
5. Click **START SERVER**. The server status card will instantly display a green **"RUNNING"** state and show host IP details (e.g., `http://192.168.1.144:8080`).

---

### 3.3 Step 3: Fetching and Activating LiteRT Models
To run offline inferencing, you need local weight files:
1. Scroll to the **ON-DEVICE MODEL DIRECTORY** section.
2. Expand the target model card (such as `litert-community/Gemma3-1B-IT`).
3. Click the **DOWNLOAD FILE** action button. The downloader will stream the file, displaying fractional progress and speed updates in real-time.
4. Once completed, download flags show active and verified states.
5. Tap **COPY PATH** to copy the target weight destination path to your clipboard.

---

### 3.4 Step 4: Dispatching Client OpenAI Requests
From any developer machine on the same local network, client application requests can target the Android Host IP:

```bash
curl -X POST http://192.168.1.144:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SecureToken112" \
  -d '{
    "model": "litert-community/Gemma3-1B-IT",
    "messages": [
      {
        "role": "user",
        "content": "Explain how gravity shapes stellar structures."
      }
    ],
    "temperature": 0.4
  }'
```

---

### 3.5 Step 5: Running Tests & Verifications
Validate system conditions locally using clean Gradle invocations:

*   **Execute JUnit & Robolectric Unit Tests:**
    ```bash
    gradle :app:testDebugUnitTest
    ```
*   **Run Roborazzi Visual Regression Verification Tests:**
    ```bash
    gradle :app:verifyRoborazziDebug
    ```
*   **Update and Record New UI Reference Screenshots:**
    ```bash
    gradle :app:recordRoborazziDebug
    ```

---

*AI Proxy Gateway — Local, Secured, Standardized, and Low-Latency AI Infrastructures on Device.*
