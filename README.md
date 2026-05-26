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
*   **HuggingFace LiteRT Models Library:** [https://huggingface.co/litert-community](https://huggingface.co/litert-community)

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
    *   `GET /v1/models` (dynamically queries system preferences, list of supported models, and outputs available local/cloud options under runtime-based availability).
*   **Dual-Route Execution Pipelines:**
    *   **Cloud Gemini Pipeline:** Forwards incoming requests to Gemini models using API keys stored securely on the database or injected via `BuildConfig`.
    *   **On-Device LiteRT-LM Pipeline:** Integrates the official Google **`com.google.ai.edge.litertlm:litertlm-android`** SDK to execute local inference using downloaded models.
    *   **Honest Provenance vs. Simulation:** This gateway implements *honest, evidence-based on-device execution* instead of fake simulated metrics. Successful outputs output standard tokens labeled with a `system_fingerprint` containing the real execution backend (e.g., `litertlm:gpu` or `litertlm:cpu`) and the factual latency metric in milliseconds.
    *   **Missing Models Protection:** If local weights are not present or cannot be parsed, the gateway promptly rejects requests with a clean HTTP 400 Bad Request detailing missing elements rather than simulating a fake mock fallback.
*   **On-Demand Model UI Bindings:** Clicking any model card under the **ON-DEVICE MODEL DIRECTORY** overrides and binds the active model in system preferences, rendering a visual color boundary and an elegant **"ACTIVE"** badge.
*   **Adaptive Background Downloader & Storage Fallbacks:** Downloads models from remote hostings directly into Android scoped files, calculates fractional downloading speeds (Mb/s), and launches the model dynamically once downloaded.
*   **Real-Time Audit Console:** A localized Room-backed logger archiving caller IP addresses, API methods, latency speeds, response summaries, and authentication checks.

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

### 2.2 Data Dictionary & Storage Configurations

The persistence engine utilizes **Room Database** to store local structures, query definitions, and structural schemas.

#### Table 1: `proxy_settings` (Schema Version 2)
Stores configuration details. Encapsulates a row constraint (`id = 1`) to operate strictly as a single active configuration record in the system.

| Column Name | Storage Type | Nullability | Constraints | Default Value | Functional Role & Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `NOT NULL` | `PRIMARY KEY` (Must equal `1`) | `1` | Forces a singleton master settings record constraint. |
| `port` | `INTEGER` | `NOT NULL` | Range: `1024` - `65535` | `8080` | Port assigned to start the background proxy server socket. |
| `proxyApiKey` | `TEXT` | `NOT NULL` | None | `""` | Restricts client API access. Rejects requests lacking bearer matching. |
| `activeModelId` | `TEXT` | `NOT NULL` | None | `"litert-community/gemma-4-E2B-it-litert-lm"` | Active AI model target selection ID. |
| `targetProvider` | `TEXT` | `NOT NULL` | One of: `"CLOUD_GEMINI"`, `"LOCAL_VAL"` | `"CLOUD_GEMINI"` | Active routing provider strategy. |
| `geminiApiKey` | `TEXT` | `NOT NULL` | None | `""` | Device-stored custom Gemini API Key. Overrides BuildConfig keys. |
| `bypassGpu` | `INTEGER` | `NOT NULL` | `0` (false) / `1` (true) | `0` | Disables GPU acceleration to fall back safely to CPU delegates. |

#### Database Migration Strategy
Previously, the database used `fallbackToDestructiveMigration()`, which wiped every user's saved port, provider, and API secrets on schema updates. Replaced with an explicit **`MIGRATION_1_2`** database migration step that safely applies:
```sql
ALTER TABLE proxy_settings ADD COLUMN geminiApiKey TEXT NOT NULL DEFAULT ''
```
This preserves existing user-entered gateway parameters while applying the schema bump dynamically.

---

### 2.3 Model Routing Directory & Capability Mappings

Our unified **`ModelRouter`** uses an exact registry database to resolve requested API model IDs rather than loose, error-prone substrings.

| Model ID (Standard & OpenAI Aliases) | Native Runtime Type (`RuntimeType`) | Upstream Target Cloud Version | Reasoning Thought Channels | Status & Capabilities |
| :--- | :--- | :--- | :--- | :--- |
| `gemini-2.5-flash` | `CLOUD` | `gemini-2.5-flash` | No | Cloud routing via Google GenAI REST API |
| `gemini-2.5-pro` | `CLOUD` | `gemini-2.5-pro` | No | Cloud routing via Google GenAI REST API |
| `gpt-4o-mini` *(alias)* | `CLOUD` | `gemini-2.5-flash` | No | Seamless compatibility drop-in alias |
| `gpt-3.5-turbo` *(alias)* | `CLOUD` | `gemini-2.5-flash` | No | Seamless compatibility drop-in alias |
| `litert-community/gemma-4-E2B-it-litert-lm` | `LITERT_LM` | - | Yes | Local inference; compiles system thought logs |
| `litert-community/Gemma3-1B-IT` | `LITERT_LM` | - | No | Local inference (Recommended 584 MB startup) |
| `aicore-gemma-4-e2b` | `AICORE` | - | No | Returns `501 NotImplemented` (Planned Android system fallback) |

---

### 2.4 Android Scoped Storage Warning (Android 11+)
Due to Scoped Storage constraints on modern Android versions (API 30+), directories owned by other target packages (such as `com.google.ai.edge.gallery` under `/sdcard/Android/data/com.google.ai.edge.gallery/files/`) are usually **unreadable** by this application. 
Consequently, this proxy app operates with a built-in fallback target hierarchy:
1. It attempts to read and run model weights downloaded directly to the app's own private directory: `/sdcard/Android/data/<this_app_applicationId>/files/` (resolving from `context.getExternalFilesDir(null)`).
2. If absent from private folders, it falls back to checking the shared paths under external explorer folders. 
*Recommendation: Always use the in-app Model Directory panel to download models directly into the app's secure files directory.*

---

## 3. Step-by-Step User Guide

### Step 1: Configure Gemini Cloud API Credentials
If you intend to route request traffic online to Google's public cloud servers (`CLOUD_GEMINI`), configure your cloud secret using one of two secure methods:

#### Method A: Direct In-App Settings Form (Recommended)
1. Launch the **AI Proxy Gateway** on your Android device.
2. Scroll to the **GATEWAY CONFIGURATION** section.
3. Locate the input card: **Device-Stored Gemini API Key (Optional)**.
4. Paste your Gemini API Developer Token directly into this field.
5. Click **APPLY SETTINGS** to store the key in the encrypted local Room database. The gateway will prioritize this custom key.

#### Method B: Project Workspace Environment Config
Alternatively, you can compile and bundle development credentials directly into the application build using a local environment file. The Maps Platform Secrets Gradle Plugin is configured to read from `.env` in this workspace:
```kotlin
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}
```
1. Create a file named `.env` in the root folder of the project.
2. Write your secret matching the key-value variable:
```properties
GEMINI_API_KEY=AIzaSy...your_gemini_api_key_here...
```

---

### Step 2: Configure & Launch the Proxy Server
1.  Open the application on your Android target device.
2.  Adjust host server specifications under the **GATEWAY CONFIGURATION** panel:
    *   **PORT:** Enter a dynamic listening port (e.g., `8080`).
    *   **GATEWAY API KEY:** Establish a password to secure incoming requests and block unauthorized access from your local Wi-Fi network. Leaving this field blank skips client authorization checking.
    *   **PROVIDER:** Choose your active execution engine:
        *   `Cloud Gemini API`: Intercepts and routes OpenAI REST API requests to Gemini's cloud servers.
        *   `LiteRT-LM`: Runs on-device AI models offline with 100% data privacy.
3.  Click **APPLY SETTINGS** to apply parameters instantly inside the Room database.
4.  Click **START SERVER**. The server status card will instantly display a green **"RUNNING"** state and show host IP details (e.g., `http://192.168.1.144:8080`).

---

### Step 3: Manage and Download On-Device LiteRT Models
To run local inference offline via LiteRT-LM, you can download model weight files directly:
1. Navigate to the **ON-DEVICE MODEL DIRECTORY** section.
2. Tap to expand the desired model's card to access weight location paths and status flags.
3. Click the **DOWNLOAD FILE** action button on the expanded card.
4. A progress tracker will show the percentage progress and current download rate in real time.
5. Tap **COPY PATH** to copy the target weight destination path to your clipboard.

---

### Step 4: Dispatch Integration Calls
Verify correct routing using a curl call from any workstation on the same network:

```bash
curl -X POST http://192.168.1.144:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SecureProxyKey99" \
  -d '{
    "model": "litert-community/Gemma3-1B-IT",
    "messages": [
      {
        "role": "user",
        "content": "Compose a short sentence describing the gravity of planet Earth."
      }
    ],
    "temperature": 0.5
  }'
```

---

### Step 5: Engineering Local Verification & Tests
Because this repository does not include pre-bundled standalone Gradle binaries, you should execute standard tests or visual regressions utilizing a system-installed Gradle package (8.x recommendation) or Android Studio's bundled Gradle executor:

*   **Run Standard Unit & ModelRouter JVM Tests:**
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

## 4. Source Map & Design Guidelines

### Core Source Directories

*   `com.example.server.ModelRouter`: Responsible for pure, exact-registry routing, resolving incoming request models to Cloud, LiteRT, or AICore pipelines.
*   `com.example.server.HttpErrors`: Formulates structured JSON error payloads utilizing OpenAI's standard Error specification.
*   `com.example.server.ProxyServerManager`: Background server executor managing local socket lifecycle.
*   `com.example.inference.LiteRtLmEngine`: Singleton LiteRT-LM runner caching generation parameters and Native C++ resources.
*   `com.example.data.AppDatabase`: Relational SQLite schema holding persistent configuration tables and `MIGRATION_1_2` logic.
*   `com.example.ui.GatewayScreen`: Polished Material 3 monitoring UI utilizing deep slate themes, status cards, and responsive badges.

*AI Proxy Gateway - Low Latency, Compliant, and High-Performance Android AI Middlewares.*
