# Juaravibecoding - Android AI Proxy Gateway

Juaravibecoding is a high-performance local AI gateway and proxy application built for Android. It transforms your mobile device into a cloud-orchestrated AI server by translating standard OpenAI-compatible API requests into secure proxy calls to Google's **Google AI Studio (Gemini)** cloud endpoints, alongside offering a fully mockable/simulation-based offline fallback suite for on-device **LiteRT (TensorFlow Lite)** runtime prototyping.

Through this application, developers can seamlessly integrate their existing OpenAI-based development stacks, agent frameworks, and AI code editors directly with LLMs running locally or bridged securely on their Android phones.

---

## 📖 Table of Contents
1. [Detail PRD (Product Requirement Document)](#1-detail-prd-product-requirement-document)
2. [Architectural Blueprint](#2-architectural-blueprint)
   - [Data Dictionary & Room Persistence Schemas](#data-dictionary--room-persistence-schemas)
   - [Use of Objects and Functions (Used By / Used For)](#use-of-objects-and-functions-used-by--used-for)
   - [Best Practice Design Patterns](#best-practice-design-patterns)
3. [Step-by-Step User Guide](#3-step-by-step-user-guide)
   - [Launch and Start Proxy Server](#step-1-launch-and-start-proxy-server)
   - [Manage On-Device LLM Weights](#step-2-manage-on-device-llm-weights)
   - [Configure API Key Secret Overrides](#step-3-configure-api-key-secret-overrides)
   - [Connect Your Computer or IDE to the Local Server](#step-4-connect-your-computer-or-ide-to-the-local-server)
4. [Pushing to GitHub & Developer Notes](#4-pushing-to-github--developer-notes)

---

## 1. Detail PRD (Product Requirement Document)

### **Objectives & Scope**
The purpose of Juaravibecoding is to bridge the gap between heavy cloud-dependent developer environments and mobile-centric on-device intelligence. Its core goal is to expose an OpenAI-compatible API server directly on an Android device's local loopback (`localhost`) or local area network (Wi-Fi IP), handling translations and processing internally. Samsung Galaxy S22 is the primary target device for local on-device inference optimization.

### **Core Capabilities & Features**
- **Robust TCP Server Socket Orchestrator**: Hosts an embedded HTTP proxy server on a highly customizer-defined port, utilizing async Coroutine thread pools to prevent blocking user interfaces.
- **OpenAI-to-Gemini REST Translation Middleware**: Dynamically parsing incoming `/v1/chat/completions` and `/v1/models` JSON payloads, mapping them onto Google AI Studio REST APIs and re-framing downstream responses format-compatibly.
- **Genuine Cloud Bridge**: Fully operational real-time proxy routing directly to the Google AI Studio cloud endpoints.
- **LiteRT/AICore Simulation Playground**: On-device models (e.g. Gemma, Gemini Nano) and their weight downloading are fully simulated (mocked backend execution) in this prototype, showing downloading states, UI toggles, and performance indicators without embedding massive native ML runtime binaries.
- **Comprehensive Logging & Audit Trails**: Maintains detailed history logs tracking HTTP status, API latency metrics, used models, raw input/output payload snippets, and active stack trace errors.
- **Hardware Co-Processor Acceleration Configurator (UI/Playground Mode)**: Displays and toggles configurations (NPU Cores acceleration vs. GPU Driver bypass) for on-device hardware pipelines as interactive prototype controls.
- **Cosmic Dark Design UI Interface**: Crafted under Material Design 3 guidelines using beautiful typography pairing, cohesive spacing grids, and high-visibility live status tracers.

## 2. Architectural Blueprint

### **Data Dictionary (Local Room SQL Schemas)**

#### Table Name: `proxy_settings`
This table persists global gateway configurations, security credentials, and active server behavior switches.

| SQLite Column Name | Kotlin Class Variable Type | Description / Constraints |
| :--- | :--- | :--- |
| `id` | `Int` (Primary Key, Default: `1`) | Enforces a Single-Row constraint for global system configurations. |
| `port` | `Int` (Default: `8080`) | The listening socket address for incoming HTTP requests. |
| `geminiApiKey` | `String` (Default: `""`) | Encrypted override API key for Google AI Studio cloud requests. |
| `enableNpuBackend` | `Boolean` (Default: `false`) | Toggles the use of Neural processing specialized SDKs during execution. |
| `bypassGpu` | `Boolean` (Default: `true`) | Standard GPU driver fallback bypass to enforce robust CPU instructions on complex architectures. |
| `gatewayAuthToken`| `String` (Default: `""`) | Encrypted, secure bearer token used to authenticate local API gateway requests. |
| `preferredBackend`| `String` (Default: `"AUTO"`) | Specifies the routing preference for local inference engines (e.g., `AUTO`, `CPU`, `GPU`, `NPU`). |
| `exposeToLan`     | `Boolean` (Default: `false`) | Toggles socket binding between loopback-only (`127.0.0.1`) and LAN interface wildcard (`0.0.0.0`). |

#### Table Name: `gateway_logs`
Tracks transactional requests made to the server, assisting debugging, analysis, and latency/token tracing.

| SQLite Column Name | Kotlin Class Variable Type | Description / Constraints |
| :--- | :--- | :--- |
| `id` | `Long` (Primary Key, Auto-Generate) | Unique sequential ID assigned to every network transition. |
| `timestamp` | `Long` (Default: Unix epoch MS) | Precise time indicating when the incoming socket connection was opened. |
| `method` | `String?` | Http Method of the request (`GET`, `POST`, `OPTIONS`). |
| `endpoint` | `String?` | Request path requested (e.g., `/v1/chat/completions`). |
| `requestSnippet` | `String?` (Default: `null`) | Visual trim of the JSON request payload containing inputs and system configurations. |
| `responseSnippet` | `String?` (Default: `null`) | Visual trim of the generated completions response payload. |
| `statusCode` | `Int` | Handled HTTP response status (e.g., `200` OK, `401` Unauthorized, `500` Error). |
| `latencyMs` | `Long` | Complete handling duration from socket read to socket stream flush. |
| `modelUsed` | `String?` | System identifier of the model routed for the completions task. |
| `errorMessage` | `String?` (Default: `null`) | Stores detailed exception messages or routing errors where applicable. |
| `tokensCount` | `Int` (Default: `0`) | Evaluated token footprint for request and response context. |

#### Table Name: `model_download_states`
Maintains individual model weight initialization, range-resume offsets, and checksum status verification.

| SQLite Column Name | Kotlin Class Variable Type | Description / Constraints |
| :--- | :--- | :--- |
| `modelId` | `String` (Primary Key) | Standard slug matching the target model in `ModelsRegistry`. |
| `progress` | `Int` (Default: `0`) | Current percentage download progression (0 to 100). |
| `status` | `String` (Default: `"NOT_STARTED"`) | Active lifecycle status (`NOT_STARTED`, `DOWNLOADING`, `PAUSED`, `VERIFYING`, `COMPLETED`, `FAILED`). |
| `downloadedBytes` | `Long` (Default: `0`) | Number of bytes correctly retrieved and stored on disk. |
| `totalBytes` | `Long` (Default: `0`) | Expected file payload footprint derived from the download headers. |
| `errorMessage` | `String?` (Default: `null`) | Captured string of download or checksum error exceptions. |

---

### **Use of Objects and Functions (Used By / Used For)**

The Juaravibecoding source codebase is structured logically to maintain a strict separation of concerns, operating cleanly across data boundaries, state controllers, and rendering components:

```
                                        +---------------------------------------+
                                        |             MainActivity              |
                                        +-------------------+-------------------+
                                                            |
                                                            v Uses (Triggers / Observes)
                                        +-------------------+-------------------+
                                        |          GatewayViewModel             |
                                        +---------+-------------------+---------+
                                                  |                   |
                                    Observes Flow |                   | Launches / Binds
                                                  v                   v
+-----------------------+               +---------+---------+   +-----+-----------------+
|   GatewayRepository   |<--------------+   GatewayScreen   |   |   GatewayForeground   |
+-----------+-----------+               +-------------------+   |        Service        |
            |                                                   +-----------+-----------+
            | Reads/Writes                                                  | Runs / Manages
            v                                                               v
+-----------+-----------+                                       +-----------+-----------+
|     AppDatabase       |                                       |   ProxyServerManager  |
| - proxy_settings      |                                       +-----------+-----------+
| - gateway_logs        |                                                   |
| - model_down_states   |                                                   v Start / Stop
+-----------------------+                                       +-----------+-----------+
                                                                |   HttpGatewayServer   |
                                                                +-----------+-----------+
                                                                            |
                                                                            v Receives HTTP Requests
                                                                +-----------+-----------+
                                                                |      ModelRouter      |
                                                                +-----+-----------+-----+
                                                                      |           |
                                                       Local Inference|           | Cloud Proxy
                                                                      v           v
                                                  +-------------------+---+   +---+-------------------+
                                                  |    LiteRtLmEngine     |   |   GeminiCloudClient   |
                                                  |   (Simulated/Real *   |   |    (Real REST SSE     |
                                                  |   with fallback)      |   |       via OkHttp)     |
                                                  +-----------+-----------+   +-----------------------+
                                                              |
                                                              v Loads weights & validates
                                                  +-----------+-----------+
                                                  |  ModelDownloadManager |
                                                  | (OkHttp Resume, SHA)  |
                                                  +-----------------------+
```

#### **1. UI and State Components**
*   **`MainActivity.kt`**
    *   **Used For**: Entry-point of the Android OS application. Initializes dynamic window edge-to-edge configurations and hooks up the Compose layout container.
    *   **Used By**: Local Android OS launcher services.
*   **`GatewayScreen.kt`**
    *   **Used For**: High-fidelity interface displaying dashboard stats, interactive toggle controls, local package models downloading flows, and audit log analysis detailed bottom sheets.
    *   **Used By**: Main layout rendering pipeline within `MainActivity.kt`.
*   **`GatewayViewModel.kt`**
    *   **Used For**: Exposes state variables using read-only `StateFlow` structures, bridging view instructions to backend processes asynchronously.
    *   **Used By**: `GatewayScreen.kt` to update state and trigger model parameters modifications.

#### **2. Persistent Storage and Entities**
*   **`GatewayRepository.kt`**
    *   **Used For**: Mediates data access abstractions, wrapping transactional DB updates, cache clears, configuration overrides, cryptographic encryption/decryption, and log records creation.
    *   **Used By**: `GatewayViewModel.kt`, `HttpGatewayServer.kt`, and `GatewayForegroundService.kt`.
*   **`ProxySettingDao.kt`, `GatewayLogDao.kt` & `ModelDownloadStateDao.kt`**
    *   **Used For**: Room interfaces compiled into detailed SQL instructions executing reads, inserts, deletions, and counts.
    *   **Used By**: `GatewayRepository` database implementations.

#### **3. Server and Middleware Components**
*   **`GatewayForegroundService.kt`**
    *   **Used For**: Runs an Android Background Service utilizing a persistent Notification to shield the server process from OS reclamation, managing system resources like `WifiLock`.
    *   **Used By**: `GatewayViewModel.kt` via service start intents.
*   **`ProxyServerManager.kt`**
    *   **Used For**: Handles full lifecycle coordination of the HTTP routing process, managing active server port configurations.
    *   **Used By**: `GatewayForegroundService.kt` to safely activate or terminate network pipelines.
*   **`HttpGatewayServer.kt`**
    *   **Used For**: Spawns a background socket stream listener. Intercepts incoming TCP connections, parses HTTP methods, manages CORS/headers, validates Bearer tokens, limits sizes/timeouts, handles keep-alive connections, and maps request payloads.
    *   **Used By**: `ProxyServerManager.kt`.
*   **`OpenAiToGeminiTranslator.kt`**
    *   **Used For**: Translates incoming standard OpenAI `/v1/chat/completions` input payloads into equivalent Google Gemini API parameters, and maps downstream stream packets back to OpenAI specifications.
    *   **Used By**: `HttpGatewayServer.kt`.
*   **`ModelRouter.kt`**
    *   **Used For**: Analyzes input model requests and matches them dynamically to either cloud providers or the appropriate on-device inference engines.
    *   **Used By**: `HttpGatewayServer.kt`.

#### **4. Inference & Downloader Cores**
*   **`GeminiCloudClient.kt`**
    *   **Used For**: Assembles server-bound requests, manages system prompts, signs payloads with API keys, forwards JSON blocks to Google AI Studio cloud endpoints, and parses incoming Server-Sent Events (SSE) chunks on the stream.
    *   **Used By**: `HttpGatewayServer.kt`.
*   **`LiteRtLmEngine.kt` & `AiCoreEngine.kt`**
    *   **Used For**: Low-latency localized processing. Translates local parameters and interfaces with on-device AI runtimes.
    *   **Used By**: `HttpGatewayServer.kt`.
*   **`ModelDownloadManager.kt`**
    *   **Used For**: Pulls remote weights via chunked `Range` headers for resumption, calculates SHA-256 verifications, and writes a global metadata manifest.
    *   **Used By**: `GatewayViewModel.kt`.

---

### **Best Practice Design Patterns**

-   **Model-View-ViewModel (MVVM) Architecture**: Enforces deep data separations. The UI (`GatewayScreen`) is fully stateless and reactive, reacting solely to decoupled `StateFlow` vectors emitted by `GatewayViewModel`.
-   **Dependency Inversion**: Clean boundaries mapped via structural repository abstractions. UI layers depend on the interfaces provided by `GatewayRepository` rather than direct Room or SQLite database queries.
-   **Reactive State Flow with Lifecycle Awareness**: Utilizing `collectAsStateWithLifecycle()` to bind flows safely to Compose compositions. This automatically halts database listeners when the application goes to the background, preventing resource consumption.
-   **Singleton Structural Managers**: Elements like `ProxyServerManager` and `LiteRtLmEngine` run as globally unique instances to avoid resource duplication, socket allocation collisions, or memory leakage.
-   **Factory Method and Strategy Translators**: The server maps out inbound API requirements and applies the correct routing strategy dynamically, routing tasks as either cloud translation calls or localized in-memory completions.

---

## 3. Step-by-Step User Guide

Ready to get your localized AI gateway server running? Follow these detailed instructions.

### **Step 1: Launch and Start Proxy Server**
1. Open the **Juaravibecoding** application on your Android device.
2. In the **Dashboard** panel, check the **Launcher Button** reading "Launch Proxy Server".
3. Press **Launch Proxy Server**.
4. The status indicator immediately transitions into a pulsing neon turquoise icon indicating **GATEWAY ACTIVE**.
5. The designated access addresses are displayed directly below the pulsing indicator, mapping:
   * **Chat completions**: `http://localhost:8080/v1/chat/completions`
   * **Catalog models list**: `http://localhost:8080/v1/models`

### **Step 2: Manage On-Device LLM Weights (Simulated / Sandbox Prototype)**
*(Note: Weights downloading and offline edge-inference compilation are simulated mock features. They demonstrate real-time downloading indicators and provide mock local generation profiles).*

1. Tap on the **Models & Logs** tab at the top of the interface.
2. Underneath the selection layout, select the **Models Library** subtab.
3. Review the available models listed under **On-Device Inference Models**.
4. Select the model of choice and click **Install weights**.
5. Monitor downloading indicators updating in real-time. Once finished, the entry updates to show a checkmark.
6. To free space, you can immediately delete weights at any time by pressing **Clear Cache**.

### **Step 3: Configure API Key Secret Overrides**
1. On the top right of the application header bars, tap the **Settings Gear Icon**.
2. An overlay dialog panel reading **Google AI Studio API Secrets** will slide onto the layout screen.
3. If you want to use cloud-based models or override default app settings, paste your custom Google AI Studio Gemini API Key into the input box.
4. Press **Apply Override**. A conformation notification informs you that your variables are updated and saved securely locally.

### **Step 4: Connect Your Computer or IDE to the Local Server**
To route completions queries from development tools running on a computer (such as the **Cursor IDE** or custom **Python scripts**) direct to your Android phone, you need to bridge your laptop network with your phone's port using **Android Debug Bridge (ADB)**.

#### **Prerequisites**
- Install Android platform-tools (ADB commands) on your computer.
- Enable **USB Debugging** on your phone (via Settings > Developer Options).
- Connect your phone to your computer via USB.

#### **Command Setup**
Run the following terminal instructions on your computer:
```bash
# Verify link connection and authorizations
adb devices

# Bridge local communication ports (Forward computer port 8080 to your Android phone's gateway port 8080)
adb reverse tcp:8080 tcp:8080
```

#### **How to Configure External Tools**

##### **1. Curl Setup**
```bash
curl -X POST http://127.0.0.1:8080/v1/ \
  -H "Content-Type: application/json" \
  -H "Authorization: a" \
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

##### **2. Python Test Script (OpenAI SDK)**
Ensure you have the `openai` python library installed. Create a file named `test_gateway.py` with the following content:

```python
import openai

# Point client directly to your Android device reverse route
client = openai.OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="sk-local-android-gateway-override"
)

try:
    print("Sending completions request to Android local proxy...")
    response = client.chat.completions.create(
        model="gemini-3.5-flash",  # Reroutes intelligently
        messages=[
            {"role": "user", "content": "Explain the speed of light in one simple sentence."}
        ],
        temperature=0.7
    )
    print("\n[Android Gateway Response]:")
    print(response.choices[0].message.content)
except Exception as e:
    print(f"Error communicating with local Android server: {e}")
```

3. Return to the Android App's **Audit Logs** tab on your device.
4. You will instantly see your Python transaction listed in the list, showing actual latency metrics and latency timings. Press any log entry to view exact request and response JSON structures!

---

## 4. Pushing to GitHub & Developer Notes

### **Deploying and Pushing Your App to GitHub**
To sync this project with your personal GitHub account, use the platform tools inside the **Google AI Studio Build** environment:
1. Tap the **Settings/Project Menu** in the top-right corner of the development interface.
2. Select **Push to GitHub** from the dropdown option list.
3. Authenticate with your GitHub account when prompted to push the entire repository structure.
4. Alternatively, you can select **Export as ZIP** to download the Android Studio source archive directly onto your workstation for custom localized extensions.

### **Security Reminder**
The application uses secure local storage to keep your configured Google AI Studio keys override. However, never distribute production APK release files containing raw developer keys as Android packages can easily be decompiled. Always prefer user runtime key input configurations.
