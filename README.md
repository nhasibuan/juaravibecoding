# ATS AI Proxy Gateway Documentation

Welcome to the **ATS AI Proxy Gateway**, a high-performance Android middleware built specifically for modern **Applicant Tracking Systems (ATS)**. This applet bridges the gap between traditional enterprise applicant processing systems—which strictly require OpenAI-compatible REST formats—and high-speed, cost-effective on-device simulation or cloud-hosted **Google Gemini API** models.

Below is the complete engineering and product documentation, including the Product Requirement Document (PRD), Architecture Blueprint, Data Schema/Dictionary, and a Step-by-Step User Guide.

---

## 🔗 Platform Links & Live Resources

In accordance with visual accessibility standards, the active deployment and preview URLs are listed below. Click any of the links to interact with or review the live Gateway emulator:

*   **Development App URL:** [https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-dev-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Shared App Preview URL:** [https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app](https://ais-pre-vmxiqlf5b4ebx5bxc5vziy-172365368647.asia-east1.run.app)
*   **Official Google Gemini API Reference:** [https://ai.google.dev/gemini-api/docs](https://ai.google.dev/gemini-api/docs)

---

## 1. Detail Product Requirement Document (PRD)

### 1.1 Product Vision & Business Context
For contemporary Human Resource and Recruiting departments, an **Applicant Tracking System (ATS)** is critical for handling resumes, analyzing qualifications, grading code challenges, and scheduling interviews. However, processing thousands of pages of resumes through proprietary cloud models triggers two major friction points:
1.  **Astronomical LLM API Costs:** Direct-to-consumer standard completion billing quickly balloons.
2.  **Strict Data Privacy Regulations (GDPR/APEC/CCPA):** Transmitting raw personal data, containing names, emails, addresses, and compensation histories, over generic cloud pipelines introduces severe compliance risks.

The **ATS AI Proxy Gateway** resolves this by operating as a local, on-office-device proxy server. By embedding this lightweight server on a dedicated Android device (or emulator) within the HR local network, external ATS software can send standardized OpenAI `POST /v1/chat/completions` screening requests directly to the Android Local IP. The gateway then either translates and routes these securely to official **Google Gemini API** models, or processes them locally via a deterministic mock simulation engine at absolutely zero cost.

### 1.2 Core Product Capabilities
*   **Zero-Configuration OpenAI Emulation:** Out-of-the-box support for the standard `POST /v1/chat/completions` request. Any existing ATS client (e.g., GreenHouse, Lever, or self-hosted wrappers) only needs a simple baseURL update.
*   **Intelligent Two-Way Payload Translation:** 
    *   **Inbound Translate:** Parses inbound OpenAI-formatted body keys (`model`, `messages`, `temperature`, `max_tokens`) and automatically converts them to structured Google Gemini content blocks (`contents`, `parts`, `text`, `generationConfig`).
    *   **Outbound Translate:** Translates raw JSON stream responses from the Gemini REST API back into standard OpenAI JSON layout, including `id`, `object`, `created`, `model`, `choices` list, and `usage` statistics.
*   **Dual Operation Modes:**
    1.  **Offline Local Simulator Mode:** Returns instantaneous mock applicant screens, resume qualification scoring, and mock interview grades entirely on-device with zero network requests. Useful for development and disconnected screening centers.
    2.  **Cloud Gemini Proxy Mode:** Securely forwards payloads to Gemini 2.5 Flash and Gemini 2.5 Pro endpoints using API keys injected securely via the **AI Studio Secrets** environment.
*   **Interoffice LAN Server Lifecycle Control:** Simple, interactive UI to start, edit background port parameters, and completely shutdown the HTTP/HTTPS Socket socket service cleanly with a click.
*   **Real-time Traffic Auditing Console:** A granular log history inspector displaying HTTP methods, path queries, target models, caller client IP addresses, millisecond round-trip times, and raw response body previews to audit candidate rating criteria.

---

## 2. Architecture Blueprint & Data Dictionary

### 2.1 Technical Architecture Overview
The application follows a clean MVVM structure with Jetpack Compose, Room for database persistence, OkHttp for proxy connection pooling, and low-overhead on-thread server sockets to bypass heavy system JVM dependencies.

```
+-------------------------------------------------------------+
|                     External ATS Client                      |
|           (Send OpenAI POST /v1/chat/completions)           |
+------------------------------+------------------------------+
                               | (Local Network IP : Port)
                               v
+-------------------------------------------------------------+
|             [ProxyServerManager] Server Socket              |
+------------------------------+------------------------------+
                               |
            +------------------+------------------+
            | (Dual Routing Scheme)              |
            v                                     v
+-----------------------+             +-----------------------+
|  Local Mock Simulator |             |   Cloud Gemini Proxy  |
| (Interactive Resume   |             | (OpenAI -> Gemini     |
|  Matches & Scores)    |             |  Payload Translation) |
+-----------------------+             +-----------+-----------+
                                                  | (OkHttp SSL)
                                                  v
                                      +-----------------------+
                                      |   Google Gemini API   |
                                      +-----------------------+
```

### 2.2 Data Dictionary (Local Settings & Logging DB Schema)

The persistent storage is facilitated by **Room Database** (`AppDatabase`) over SQLite. It maintains two system tables for maintaining gateway behavior:

#### Table 1: `proxy_settings`
This table holds the active configuration of the AI gateway. It strictly contains only one row (id = 1) to bypass multiple active listener overlaps.

| Field Name | Data Type | Default Value | Description / Validation Constraints |
| :--- | :--- | :--- | :--- |
| `id` *(Primary Key)* | `INTEGER` | `1` | Strictly configured to `1` to serve as a single-record configuration block. |
| `port` | `INTEGER` | `8080` | The network port the `ServerSocket` listens to. System ports (< 1024) are blocked for safety. |
| `proxyApiKey` | `TEXT` | `""` | Optional authorization Bearer token. If configured, clients must include `Authorization: Bearer <key>`. |
| `activeModelId` | `TEXT` | `"litert-community/gemma-4-E2B-it-litert-lm"` | Active model identifier key. |
| `targetProvider` | `TEXT` | `"CLOUD_GEMINI"` | Enumerated string routing: `"CLOUD_GEMINI"`, `"LOCAL_VAL"`, or `"MOCK"`. |

#### Table 2: `gateway_logs`
Chronologically stores transactional details of incoming evaluation calls made to the ATS gateway.

| Field Name | Data Type | Default Value | Description / Validation Constraints |
| :--- | :--- | :--- | :--- |
| `id` *(Primary Key)* | `INTEGER` | *(Auto-generated)* | Unique auto-incrementing ID of the log record. |
| `timestamp` | `INTEGER` | `System.currentTimeMillis()` | Ephemeral epoch timestamp indicating when the request was parsed. |
| `method` | `TEXT` | *(Non-Null)* | HTTP Method used by the client (e.g., `"POST"`, `"OPTIONS"`, `"SYSTEM"`). |
| `path` | `TEXT` | *(Non-Null)* | API Path requested (e.g., `"/v1/chat/completions"`). |
| `requestModel` | `TEXT` | `"unknown-model"` | The requested model parsed from the client's payload. |
| `clientIp` | `TEXT` | `"unknown"` | IPv4 address of the evaluating client device on the network. |
| `status` | `INTEGER` | `200` | Processed HTTP status code returned to the client (e.g., `200`, `401`, `404`, `500`). |
| `durationMs` | `INTEGER` | `0` | Full parse-and-callback round-trip duration measured in milliseconds. |
| `responsePreview` | `TEXT` | `""` | Extracted textual answer or preview of the applicant evaluation. |
| `isAuthorized` | `BOOLEAN` | `true` | Security auditing check: `true` if passed security token or if no key restriction exists. |

---

## 3. Step-by-Step User Guide (How to Integrate with ATS Systems)

To utilize this server locally or over cloud bridges, follow these setup stages:

### Step 1: Secure API Key Provisioning (Cloud Mode Only)
To route requests to high-accuracy Gemini models, configure your primary Gemini Web token:
1.  Navigate to your Google AI Studio Workspace.
2.  Add your token under the **Secrets panel in AI Studio** using the variable name: `GEMINI_API_KEY`.
3.  The runtime compiler handles binding to `BuildConfig.GEMINI_API_KEY` transparently. At no point should any secure tokens be hardcoded in application files.

### Step 2: Initialize & Configure the Gateway
1.  Launch the **AI Proxy Gateway** on your Android device/emulator.
2.  View the status card. Under the **PROVIDER** select parameter, toggle between:
    *   **CLOUD_GEMINI:** Routes translation to Google's online endpoints.
    *   **MOCK / SIMULATOR:** Runs offline matches instantly.
3.  Enter your desired port (default `8080`) and an optional **Gateway Auth API Key** (e.g., `SecretATSKey55`) to block external unauthorized sniffing in dynamic local area networks.
4.  Toggle the server status by pressing the floating action **START** icon button. The status panel turns bright cyan, and lists the current active base endpoints.

### Step 3: Copy Access URLs
*   Verify the localized host addresses. Real-time dynamic network detectors automatically populate your corporate LAN address (e.g., `http://192.168.1.45:8080`).
*   Tap **COPY** directly beside the **Base Connection URL** or the specific **OpenAI Endpoint** (`/v1/chat/completions`) inside the UI cards. The system copies the string to the clipboard with an interactive toast notification.
*   *(Optional)* If local models are registered, tap the target `🔗 LINK` button on the registered model list card to copy the direct model download page.

### Step 4: Validate via Client Test Command
From a workstation on the same local network, open a terminal shell and fire an applicant screening test package:

```bash
curl -X POST http://192.168.1.45:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SecretATSKey55" \
  -d '{
    "model": "gemini-2.5-flash",
    "messages": [
      {
        "role": "system",
        "content": "You are a professional ATS resume screening assistant. Match applicant profiles to roles with scores."
      },
      {
        "role": "user",
        "content": "Review Applicant John Doe. 5 Years Java Experience, Spring Boot, Android SDK. Rating?"
      }
    ],
    "temperature": 0.7
  }'
```

### Step 5: Real-time Audit Inspection & Traffic Monitor
1.  Return to the Android application UI interface.
2.  The traffic terminal logs refresh instantly. You will see a `POST` request with the client's LAN IP, showing a green checkmark indicating a `200` successful classification.
3.  Tap on any dynamic log item. An expansive bottom sheet presents the full request body metrics, duration, models used, and the text evaluation preview.
4.  To release server resources, tap **CLOSE** to stop the server listeners completely.

---
*Documentation compiled on 2026-05-22 UTC for AI Studio ATS Deployment.*
