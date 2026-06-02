# Juaravibecoding - Android AI Proxy Gateway & On-Device LiteRT Inference Engine

Juaravibecoding is an elite, high-performance local AI gateway and proxy application built for Android. It transforms your mobile hardware into a powerful local and cloud-orchestrated AI server by translating standard OpenAI-compatible API requests into secure proxy calls to Google's **Google AI Studio (Gemini)** cloud endpoints, alongside offering high-performance, real-time on-device **LiteRT (TensorFlow Lite / MediaPipe)** local inference execution with automated simulation fallbacks.

Through this application, developers can seamlessly route completions queries from development tools (such as AI code editors, agent frameworks, or Python automation scripts) directly to LLMs running either locally on-device or bridged securely to the cloud on their Android phones.

---

## 📖 Table of Contents
1. [Product Requirement Document (PRD)](#1-product-requirement-document-prd)
2. [Architectural Blueprint](#2-architectural-blueprint)
   - [Data Dictionary & Room Persistence Schemas](#data-dictionary--room-persistence-schemas)
   - [Use of Objects and Functions (Who Uses Whom)](#use-of-objects-and-functions-who-uses-whom)
   - [Best Practice Design Patterns](#best-practice-design-patterns)
3. [Step-by-Step User Guide](#3-step-by-step-user-guide)
   - [Step 1: Launch and Start Proxy Server](#step-1-launch-and-start-proxy-server)
   - [Step 2: Check System Intelligence & Recommend Models](#step-2-check-system-intelligence--recommend-models)
   - [Step 3: Manage On-Device Model Weights](#step-3-manage-on-device-model-weights)
   - [Step 4: Configure API Secret Overrides](#step-4-configure-api-secret-overrides)
   - [Step 5: Bridge Your Development Environment (ADB Setup)](#step-5-bridge-your-development-environment-adb-setup)
   - [Step 6: Query Gateway Using Client Tools (cURL / Python)](#step-6-query-gateway-using-client-tools-curl--python)
4. [Pushing to GitHub & Developer Notes](#4-pushing-to-github--developer-notes)

---

## 1. Product Requirement Document (PRD)

### **Objectives & Scope**
The target of Juaravibecoding is to bridge heavy cloud-dependent developer environments with local mobile-centric intelligence. Its primary goal is to expose an OpenAI-compatible API server running directly on an Android device's local network loopback (`localhost:8080`) or local area network (LAN wildcard `0.0.0.0`), orchestrating both cloud-bound and local completions pipelines. Samsung Galaxy S-series and modern Android devices are particularly optimized for local model execution.

### **Core Capabilities & Technical Features**
- **Robust TCP Socket Engine**: Spawns an embedded HTTP server directly within Android background threads, handling standard CORS preflights, Keep-Alive socket pooling, authentication token validation, and multi-tenant connection dispatching.
- **On-Device LiteRT-LM Core**: Full local integration with the modern **LiteRT (MediaPipe GenAI)** Android API, executing raw `.bin`/`.litertlm` local models directly on physical CPU, GPU, or hardware NPU backends.
- **System Memory Profiler**: Automatically profiles device RAM capability on startup, alerting of memory limits and suggesting custom models matching hardware specs (e.g. recommends Gemma 3 1b for lower-spec <3GB RAM devices; Gemma 4 E2B for heavier loads).
- **Synchronized Cloud Fallback & Simulations**: Includes automatic, seamless failover logic. If local model weights are missing or incomplete, the application utilizes optimized contextual simulations to let developers preview pipeline latency on mobile threads.
- **Audit Trails & Tracing**: Every transaction registers in a localized Room database, logging HTTP status, requested models, payload bytes, tokens count, latency in milliseconds, and potential network or model parsing errors.
- **Cosmic-Infused Material Design 3 UI**: Provides a visually stunning dark-slate and neon-teal dashboard containing real-time gateway status indicators, capability charts, detailed logs, and a central setting gear panel.

---

## 2. Architectural Blueprint

### **Data Dictionary (Local Room SQL Schemas)**

#### **Table Name: `proxy_settings`**
Stores global server parameters, cryptographic key overrides, and active backend routing instructions.

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

#### **Table Name: `gateway_logs`**
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

#### **Table Name: `model_download_states`**
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

### **Use of Objects and Functions (Who Uses Whom)**

The codebase isolates platform elements, UI state models, network routers, and raw on-device execution kernels:

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
                                                                |      ModelRouter      |
                                                                +-----+-----------+-----+
                                                                      |           |
                                                       Local Inference|           | Cloud Proxy
                                                                      v           v
                                                    +-----------------+---+   +---+-------------------+
                                                    |   LiteRtLmEngine    |   |   GeminiCloudClient   |
                                                    +-----------+---------+   +-----------------------+
                                                                |
                                                                v Uses (Initialize & Execute Stream)
                                                    +-----------+---------+
                                                    |  LiteRtLmRepository |
                                                    +-----------+---------+
                                                                |
                                                                v Instantiates
                                                    +-----------+---------+
                                                    |LiteRtLmEngineWrapper|
                                                    +---------------------+
```

#### **1. UI and State Components**
*   **`MainActivity.kt`**
    *   **Used For**: Entry-point of the Android application. Sets up dynamic window edge-to-edge configurations and displays the core Compose viewport.
    *   **Used By**: Local Android OS launcher.
*   **`GatewayScreen.kt`**
    *   **Used For**: Renders the complete futuristic black dashboard, real-time telemetry metrics, log details, and a memory profile optimization card that analyzes on-board memory.
    *   **Used By**: Main layout rendering pipeline within `MainActivity.kt`.
*   **`GatewayViewModel.kt`**
    *   **Used For**: Exposes thread-safe data vectors using read-only `StateFlow` types, letting the UI invoke repository instructions asynchronously.
    *   **Used By**: UI components in `GatewayScreen.kt` to display metrics, logs, and settings overlays.

#### **2. Local Inference & Pipeline Execution Core**
*   **`LiteRtLmRepository.kt`**
    *   **Used For**: Thread-safe manager for device-level models. Handles lazy-loaded on-device engine creations under `Dispatchers.IO`, and wraps the active generation pipeline into functional cold Kotlin flows.
    *   **Used By**: Coordinates on-device execution routes called from `LiteRtLmEngine.kt`.
*   **`LiteRtLmEngineWrapper.kt`**
    *   **Used For**: Wraps the underlying Google MediaPipe Tasks LLM inference instances. Implements Kotlin's `AutoCloseable` interface to ensure native execution memory arrays are safely cleaned up and released immediately upon execution completion or failure.
    *   **Used By**: Created dynamically within `LiteRtLmRepository.kt`.
*   **`LiteRtLmEngine.kt`**
    *   **Used For**: Primary local router. Attempts real LiteRT on-device inference via `LiteRtLmRepository`. If weight files are incomplete or the device lacks setup files, it seamlessly routes completions dynamically through an optimized local simulation layer.
    *   **Used By**: `ModelRouter.kt`.
*   **`ModelDownloadManager.kt`**
    *   **Used For**: Manages the download of local model parameters. Exposes `getDeviceRamGb` to verify hardware RAM boundaries, and `getRecommendedModel` to suggest the best model profiles.
    *   **Used By**: Renders configuration guidelines inside `GatewayScreen.kt`.

#### **3. Server and Routing Middleware**
*   **`GatewayForegroundService.kt`**
    *   **Used For**: Keeps the socket backend persistent using Android foreground states protected from reclamation by the OS.
    *   **Used By**: Launched via ViewModel user button binds.
*   **`ProxyServerManager.kt`**
    *   **Used For**: Initializes and stops the raw socket parameters and routes server activities to background threads.
    *   **Used By**: Bound inside `GatewayForegroundService.kt`.
*   **`HttpGatewayServer.kt`**
    *   **Used For**: Spawns an internal HTTP background socket listener, intercepting inbound TCP calls and managing CORS preflights, Keep-Alive frames, and routing flows.
    *   **Used By**: `ProxyServerManager.kt`.
*   **`ModelRouter.kt`**
    *   **Used For**: Maps incoming target models to their correct handlers, deciding between cloud proxy endpoints or local offline engines.
    *   **Used By**: Called by `HttpGatewayServer.kt` during endpoint routing.
*   **`OpenAiToGeminiTranslator.kt`**
    *   **Used For**: Dynamic bidirectional parameters mapping. Maps incoming standard OpenAI payload keys into appropriate Google Gemini attributes, translating downstream streams back to format-compatible chunks.
    *   **Used By**: `HttpGatewayServer.kt`.

---

### **Best Practice Design Patterns**

-   **Model-View-ViewModel (MVVM) Design Pattern**: Prevents UI coupling. `GatewayScreen` holds zero business logic, interacting with the system solely via decoupled state parameters exposed as `StateFlow` nodes.
-   **Resource Scoping (RAII / Auto-Closeable Pattern)**: Leverages Kotlin’s `.use {}` extensions on `LiteRtLmEngineWrapper`. Because LLM execution weights consume up to 2.5 GB of RAM, native memory handles are automatically released upon task finishing, eliminating native memory leak risks.
-   **Thread Concurrency Isolation Pattern**: Delegates heavy background initialization vectors strictly to `Dispatchers.IO`. This prevents slow engine spin-ups from blocking the Main UI Thread, avoiding Android Application Not Responding (ANR) warnings.
-   **Strategy Routing Pattern**: Maps generic endpoints to dynamic execution paths inside `ModelRouter`. The server redirects queries dynamically to cloud pipelines, real LiteRT hardware, or robust simulators without client-side restructuring.
-   **Gateway Singleton Pattern**: Central engines, database layers, and repositories are structured as unique Singleton patterns, preserving thread integrity across isolated socket threads.

---

## 3. Step-by-Step User Guide

Ready to get your localized AI gateway server running? Follow these steps to set up, profile, download, and test your gateway.

### **Step 1: Launch and Start Proxy Server**
1. Launch the **Juaravibecoding** application on your Android device.
2. Ensure you are on the **Dashboard** panel.
3. Tap the turquoise neon **Launch Proxy Server** button.
4. The system status indicator immediately transitions to a pulsing neon circle labeled **GATEWAY ACTIVE**.
5. The local access addresses are displayed under the status display:
   * **Chat completions**: `http://localhost:8080/v1/chat/completions`
   * **Catalog models list**: `http://localhost:8080/v1/models`

### **Step 2: Check System Intelligence & Recommend Models**
1. Look at the top of the **Dashboard** or **Models & Logs** page.
2. Locate the neon-framed **System Capability Profiler** card.
3. The app dynamically scans the system memory limits on startup. It displays your available RAM (e.g., `5.64 GB RAM` or `7.41 GB RAM`).
4. Read the recommendation:
   * **RAM under 3.0 GB**: Recommended Model is **Gemma3-1B-IT** (efficient footprint).
   * **RAM above 3.0 GB**: Recommended Model is **Gemma 4 E2B** (optimal intelligence).

### **Step 3: Manage On-Device Model Weights**
1. Navigate to the **Models & Logs** tab at the top of the console.
2. Underneath the header, ensure the **Models Library** subtab is selected.
3. Review the model registry list:
   - **Gemma3-1B-IT**: Highly lightweight fallback model.
   - **Gemma 4 E2B**: Maximum intelligence local model.
4. Tap the **Install weights** button of your chosen model.
5. Watch the download indicators update. Once download has successfully completed, the model entry shows a checkmark.
6. Want to free memory? Tap the **Clear Cache** button on any installed model to remove weights safely.

### **Step 4: Configure API Secret Overrides**
1. On the top-right corner of the application bar, tap the **Settings Gear Icon**.
2. An overlay dialog reading **Google AI Studio API Secrets** will slide onto the screen.
3. Paste your custom **Google AI Studio Gemini API Key** into the input box.
4. Tap the **Apply Override** button. A notification bar verifies your settings have been encrypted and saved securely locally.

### **Step 5: Bridge Your Development Environment (ADB Setup)**
To route requests from development tools (like VS Code, Cursor, Python CLI scripts, or terminals) to your Android device, you need to route ports using **Android Debug Bridge (ADB)**.

#### **Prerequisites**
- Install Android platform-tools containing ADB on your workstation.
- Enable **USB Debugging** on your phone (found in Settings > Developer Options).
- Connect the phone to your computer with a high-speed USB cable.

#### **Network Bridging Command**
Run the following instructions in your computer's terminal:
```bash
# Confirm the laptop correctly detects your authorized Android phone
adb devices

# Forward all outgoing laptop port 8080 requests directly to port 8080 on the Android device
adb reverse tcp:8080 tcp:8080
```
Once reverse forwarding is active, any request sent to `http://localhost:8080` on your laptop is securely bridged to your Android phone gateway!

### **Step 6: Query Gateway Using Client Tools**

#### **Option A: Simple shell test using cURL**
You can send requests via terminal on your computer using cURL to query your on-device engine:
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -d '{
    "model": "litert-community/gemma-4-E2B-it-litert-lm",
    "messages": [
      {
        "role": "user",
        "content": "Explain why gravity is a consequence of space-time curvature."
      }
    ],
    "temperature": 0.3
  }'
```

#### **Option B: Run a Python script utilizing standard OpenAI SDK**
Create a Python file named `local_ai_test.py` with the following:
```python
import openai

# Bind OpenAI client directly to the ADB-forwarded port
client = openai.OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="your_android_local_bearer_token"
)

try:
    print("Dispatching request to local Android LiteRT/Gemini Gateway...")
    response = client.chat.completions.create(
        model="gemini-3.5-flash",  # Reroutes automatically to Gemini
        messages=[
            {"role": "user", "content": "Tell me a highly unique programming pun."}
        ],
        temperature=0.7
    )
    print("\n[Android Gateway Server Response]:")
    print(response.choices[0].message.content)
except Exception as e:
    print(f"Failed to query local Android Gateway server: {e}")
```

#### **Option C: Stream audit logs on the device screen**
1. Switch to the **Audit Logs** tab under **Models & Logs** inside the app.
2. You will instantly see your query listed in the telemetry stream!
3. Tap on the log entry card to slide up a detail viewer. You can inspect exact payload snippets, latency timings, and status codes.

---

## 4. Pushing to GitHub & Developer Notes

### **Sync and Push Your App to GitHub**
To sync this project with your personal GitHub account, use the platform tools inside the **Google AI Studio Build** environment:
1. Tap the **Settings/Project Menu** in the top-right corner of the development interface.
2. Select **Push to GitHub** from the dropdown option list.
3. Authenticate with your GitHub account when prompted to push the entire repository structure.
4. Alternatively, select **Export as ZIP** to download the Android Studio source archive directly onto your workstation for custom localized extensions.

### **Security Best Practice**
The application uses secure SQLite encryption to store your custom API keys. However, never distribute production APK release files containing hardcoded key values. Always prefer system environment injection or explicit runtime user configuration screens.
