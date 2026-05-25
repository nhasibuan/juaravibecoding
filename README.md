# AI Proxy Gateway (Documentation & System Guide)

The **AI Proxy Gateway** is an Android app that runs a local HTTP server speaking the OpenAI Chat Completions protocol and forwarding requests to either the Google Gemini cloud REST API or to an on-device LiteRT-LM engine. Existing OpenAI-compatible clients can change their base URL to the device's IP and port (e.g. `http://192.168.1.144:8080/v1`) and use Gemini or local models with no other code changes.

---

## 🔗 Reference links

*   **Google Gemini API:** [https://ai.google.dev/gemini-api/docs](https://ai.google.dev/gemini-api/docs)
*   **OpenAI Chat Completions reference:** [https://platform.openai.com/docs/api-reference/chat](https://platform.openai.com/docs/api-reference/chat)
*   **Google AI Edge — LiteRT:** [https://ai.google.dev/edge/litert](https://ai.google.dev/edge/litert)
*   **LiteRT-LM developer portal:** [https://ai.google.dev/edge/litert-lm](https://ai.google.dev/edge/litert-lm)
*   **LiteRT-LM repository:** [https://github.com/google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
*   **Reference Android implementation (gallery):** [https://github.com/google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)
*   **LiteRT model hosting:** [https://huggingface.co/litert-community](https://huggingface.co/litert-community)

---

## 1. Product overview

### 1.1 Why this exists

A lot of code, tooling, and SDKs target OpenAI's `POST /v1/chat/completions`. Switching that work to Gemini usually requires touching every client. The gateway sits on a phone or emulator, listens on a local port, and:

*   accepts standard OpenAI requests verbatim;
*   translates them to Gemini cloud REST or to the LiteRT-LM Kotlin SDK;
*   returns the answer in the OpenAI response shape, so callers see no difference.

Switching cloud → local is a single tap in the app — no client redeploy, no server restart. Local mode keeps prompts on the device.

### 1.2 Core capabilities

*   **OpenAI-compatible HTTP endpoints**
    *   `POST /v1/chat/completions` — full chat-completion shape, including `messages`, `model`, `temperature`, `max_tokens` / `max_completion_tokens`, `top_p`, `top_k`.
    *   `GET /v1/models` — lists only the model ids that will actually route successfully under the current settings (cloud entries appear when a Gemini key is configured; LiteRT entries appear when their `.litertlm` weight file is on disk).
*   **Two routing destinations**
    *   `CLOUD_GEMINI` — proxies to `https://generativelanguage.googleapis.com/v1beta/models/{id}:generateContent` over OkHttp.
    *   `LOCAL_VAL` — runs `com.google.ai.edge.litertlm:litertlm-android` on the device's CPU or GPU. Mirrors the canonical lifecycle from `google-ai-edge/gallery`'s `LlmChatModelHelper`.
*   **Honest error envelopes.** Unknown model id → `400 model_not_found`. Cloud model with no key → `500 gateway_setup_error`. LiteRT model whose weights aren't on the device → `400 model_not_found` with the expected file path. Provider/runtime mismatch → `400 provider_mismatch`. AICore entries are listed but not yet implemented and return `501 not_implemented`.
*   **Strict model-id resolution.** No silent fallback to a "nearest" model. Aliases (`gpt-4o-mini`, `gpt-3.5-turbo` → `gemini-2.5-flash`) are matched verbatim and their resolution is documented.
*   **Local model downloader** with live progress for `*.litertlm` weights. The download lands in the app's own external files directory (`getExternalFilesDir(null)`), not in `/sdcard/Android/data/com.google.ai.edge.gallery/files/...` on Android 11+ — see §4.3.
*   **Live traffic log** persisted to Room SQLite: method, path, requesting model, client IP, status, duration, response preview.
*   **Bearer-token gating.** Setting a non-empty *Gateway API key* in the UI requires every client to send `Authorization: Bearer <key>`. Leaving it blank disables auth (LAN-trusted mode).
*   **CORS preflight handling** for browser clients hitting the LAN endpoint directly.
*   **Request size cap** of 10 MB (returns `413 Content Too Large`).

### 1.3 Cloud vs local — when to pick which

| | `CLOUD_GEMINI` | `LOCAL_VAL` (LiteRT-LM) |
|---|---|---|
| Where it runs | Google's Gemini API servers | On the device, in-process |
| Internet required | Yes | No |
| Hardware load | Just network | CPU and/or GPU; 580 MB – 4.9 GB of RAM depending on model |
| Cost model | Per-token Gemini billing | Free after model download |
| Where prompts go | To Google over TLS | Stays on the device |
| Best for | Frontier-quality answers, long context | Privacy-sensitive workloads, offline operation, no per-call billing |
| Latency profile | Network-bound (typically 200 ms – several seconds) | Compute-bound; first call is slow because weights must be loaded once (10–60 s) |

### 1.4 Supported model ids

These are the ids that `GET /v1/models` may advertise and that `POST /v1/chat/completions` will accept. Anything else returns `400 model_not_found`.

| Client-facing id | Runtime | Upstream / file | Notes |
|---|---|---|---|
| `gemini-2.5-flash` | CLOUD | `gemini-2.5-flash` | Default cloud target |
| `gemini-2.5-pro` | CLOUD | `gemini-2.5-pro` | |
| `gemini-1.5-flash` | CLOUD | `gemini-1.5-flash` | |
| `gemini-1.5-pro` | CLOUD | `gemini-1.5-pro` | |
| `gpt-4o-mini` *(alias)* | CLOUD | `gemini-2.5-flash` | Convenience for OpenAI clients |
| `gpt-3.5-turbo` *(alias)* | CLOUD | `gemini-2.5-flash` | Convenience for OpenAI clients |
| `litert-community/gemma-4-E2B-it-litert-lm` | LITERT_LM | `gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm` | thinking |
| `litert-community/gemma-4-E4B-it-litert-lm` | LITERT_LM | `gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm` | thinking |
| `google/gemma-3n-E2B-it-litert-lm` | LITERT_LM | `gemma-3n-E2B-it-int4.litertlm` | image, audio capable |
| `google/gemma-3n-E4B-it-litert-lm` | LITERT_LM | `gemma-3n-E4B-it-int4.litertlm` | image, audio capable |
| `litert-community/Gemma3-1B-IT` | LITERT_LM | `gemma3-1b-it-int4.litertlm` | smallest weights (~580 MB), best smoke-test target |
| `litert-community/Qwen2.5-1.5B-Instruct` | LITERT_LM | `Qwen2.5-1.5B-Instruct_..._q8_ekv4096.litertlm` | |
| `litert-community/DeepSeek-R1-Distill-Qwen-1.5B` | LITERT_LM | `DeepSeek-R1-Distill-Qwen-1.5B_..._q8_ekv4096.litertlm` | thinking |
| `litert-community/functiongemma-270m-ft-tiny-garden` | LITERT_LM | `tiny_garden.litertlm` | CPU-only |
| `litert-community/functiongemma-270m-ft-mobile-actions` | LITERT_LM | `mobile_actions.litertlm` | CPU-only |
| `aicore-gemma-4-e2b`, `aicore-gemma-4-e4b` | AICORE | — | Registered but **not yet implemented**. Returns `501 not_implemented`. |

> **Note on multimodal input.** `image` / `audio` capable rows above advertise the *model* capability. The gateway's request translator currently only forwards text content; image and audio in request bodies are dropped. See `plan.md` §11 for the tracked work to plumb `Content.ImageBytes` / `Content.AudioBytes` through `extractLocalEngineRequest`.

---

## 2. Architecture

### 2.1 Big-picture flow

```
Client (curl, OpenAI SDK, anything OpenAI-compatible)
        │  POST /v1/chat/completions   { model: <openai-id>, messages: [...] }
        ▼
ProxyServerManager.handleClient            (raw socket; OkHttp for cloud egress)
        │
        ├─ ModelRouter.resolve(requestedId, settings, ...)
        │     ├─ findStrict — exact id or alias; null otherwise
        │     ├─ provider ⇄ runtime cross-check
        │     ├─ weights existence check (LiteRT only)
        │     └─ cloud-key presence check (Cloud only)
        │
        ├─ RoutedModel.Cloud      → OkHttp → Gemini REST
        ├─ RoutedModel.LiteRtLm   → LiteRtLmEngine.ensureLoadedAndReset() + .generate()
        └─ RoutedModel.AiCore     → 501 not_implemented
```

The full design rationale and remaining roadmap is in [`plan.md`](plan.md).

### 2.2 MVVM stack

*   **Compose UI** (`GatewayScreen`) reads `StateFlow`s from `GatewayViewModel`.
*   **`GatewayViewModel`** orchestrates server lifecycle, model downloads (with byte-accurate progress), and exposes settings + logs.
*   **`GatewayRepository` + Room** persists exactly one `proxy_settings` row (PK = 1) and a rolling 100-row `gateway_logs` window.
*   **`ProxyServerManager`** holds the server socket, accept loop, per-connection coroutine, and dispatches via `ModelRouter`.
*   **`OpenAiToGeminiTranslator`** maps OpenAI ⇄ Gemini for cloud, and OpenAI ⇄ LiteRT-LM SDK shapes for local.
*   **`LiteRtLmEngine`** singleton holds at most one `Engine` + `Conversation` from `com.google.ai.edge.litertlm:litertlm-android:0.11.0`. The lifecycle (capability probe, NPU/TPU sampler-null rule, single-load-at-a-time keying on `(modelId, backend, maxTokens)`) mirrors gallery's `LlmChatModelHelper`.

### 2.3 Persistence

#### Table: `proxy_settings` (schema v2)

Singleton row (`id = 1`). Schema version bumps are migrated, not wiped — see `AppDatabase.MIGRATION_1_2`. Earlier builds used `fallbackToDestructiveMigration()` and lost user settings on upgrade; that is no longer the case.

| Column | Type | Default | Purpose |
|---|---|---|---|
| `id` | `INTEGER PRIMARY KEY` | `1` | Singleton constraint. |
| `port` | `INTEGER NOT NULL` | `8080` | Bind port for the server socket. |
| `proxyApiKey` | `TEXT NOT NULL` | `""` | Optional bearer-token gate. Empty = LAN-trusted mode. |
| `activeModelId` | `TEXT NOT NULL` | `litert-community/gemma-4-E2B-it-litert-lm` | Default selected model in the UI. **Not** used to coerce request routing — strict model ids only. |
| `targetProvider` | `TEXT NOT NULL` | `CLOUD_GEMINI` | Either `CLOUD_GEMINI` or `LOCAL_VAL`. |
| `geminiApiKey` | `TEXT NOT NULL` | `""` | Per-device override of the BuildConfig key (added in v2). |

#### Table: `gateway_logs`

Rolling audit. The DAO returns the most recent 100 rows for the live UI list.

| Column | Type | Notes |
|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | |
| `timestamp` | `INTEGER NOT NULL` | Epoch ms |
| `method` | `TEXT NOT NULL` | `GET`, `POST`, `OPTIONS`, or `SYSTEM` for internal events |
| `path` | `TEXT NOT NULL` | The raw request path (pre-normalization) |
| `requestModel` | `TEXT NOT NULL` | Parsed from JSON body, falls back to `unknown-model` |
| `clientIp` | `TEXT NOT NULL` | IPv4 or IPv6 of the caller |
| `status` | `INTEGER NOT NULL` | HTTP status returned |
| `durationMs` | `INTEGER NOT NULL` | Wall-clock |
| `responsePreview` | `TEXT NOT NULL` | First 120 chars of `choices[0].message.content`, or the raw body for non-200s |
| `isAuthorized` | `INTEGER NOT NULL` | 0/1 |

### 2.4 Source map (one-line per file)

```
AndroidManifest.xml                    declares INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE; usesCleartextTraffic="true"
MainActivity.kt                        Compose host; constructs GatewayViewModel and renders GatewayScreen
data/AppDatabase.kt                    Room v2; MIGRATION_1_2 (geminiApiKey)
data/ProxySetting.kt                   singleton settings entity (port, keys, activeModelId, targetProvider)
data/ProxySettingDao.kt                Room DAO with reactive Flow + upsert
data/GatewayLog.kt                     audit-log entity
data/GatewayLogDao.kt                  rolling 100-row reactive query + insert/clear
data/GatewayRepository.kt              repository over the two DAOs
data/ModelsRegistry.kt                 RuntimeType enum, LocalModelInfo, allowedModels, findStrict, deprecated getModelById
ui/GatewayViewModel.kt                 server lifecycle + model downloads + StateFlows
ui/GatewayScreen.kt                    Material 3 dashboard
server/ProxyServerManager.kt           server socket, request parser, dispatch through ModelRouter, OkHttp egress
server/ModelRouter.kt                  pure routing logic; returns Result<RoutedModel> with typed RoutingError
server/HttpErrors.kt                   OpenAI-shaped {error:{message,type,code}} renderer
server/OpenAiToGeminiTranslator.kt     OpenAI ⇄ Gemini (cloud); OpenAI ⇄ LiteRT-LM SDK shapes (local)
inference/LiteRtLmEngine.kt            LiteRT-LM Engine + Conversation lifecycle (mirrors gallery)
test/.../ExampleRobolectricTest.kt     small Robolectric smoke
test/.../GreetingScreenshotTest.kt     Roborazzi snapshot
test/.../server/ModelRouterTest.kt     14 plain-JUnit cases over the routing surface
```

---

## 3. Setup

### 3.1 Build prerequisites

*   Android Studio Iguana or newer (or a system Gradle 8.x with the Android Gradle Plugin 9.1.x).
*   JDK 17 or 21 on the build machine.
*   Android SDK with API 36 platform installed and `local.properties` pointing at it (`sdk.dir=...`).
*   Internet access to Maven Central — needed to fetch `com.google.ai.edge.litertlm:litertlm-android:0.11.0` plus the rest of the AGP / Compose / Room artifacts.

### 3.2 Gemini API key — two ways to provide it

The cloud path needs a Gemini API key. Either method works; the in-app key wins if both are set.

#### Method A — In-app (recommended for end users)

1.  Launch the app on the device.
2.  In **Server parameters**, paste the key into **Device-Stored Gemini API Key**.
3.  Tap **Save & detach socket**. The key is written to the local SQLite DB (`proxy_settings.geminiApiKey`).

#### Method B — Compile-time (for developers building from source)

This project uses the [Secrets Gradle Plugin](https://github.com/google/secrets-gradle-plugin), configured in `app/build.gradle.kts` to read from `.env` (primary) and `.env.example` (fallback):

```kotlin
secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
}
```

So:

*   Create `.env` at the repository root (it is git-ignored):
    ```properties
    GEMINI_API_KEY=AIzaSy...your_key_here...
    ```
*   The plugin injects this into `BuildConfig.GEMINI_API_KEY` at build time.
*   `.env.example` already contains a placeholder (`MY_GEMINI_API_KEY`) so the build never fails for missing input — the gateway treats that placeholder as "no key configured" and returns `gateway_setup_error` until you supply a real one (either via `.env` or via Method A).

> **`local.properties` is *not* read for `GEMINI_API_KEY`.** If you have older docs or scripts that put the key there, they won't work. The plugin's `propertiesFileName` is `.env`.

### 3.3 Signing

*   Debug builds are signed with the checked-in `debug.keystore` (alias `androiddebugkey`, password `android`). Convenient for local installs; do not ship a release with it.
*   Release builds read `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` from environment variables. The keystore file referenced by `KEYSTORE_PATH` is *not* checked in.

---

## 4. Operating the gateway

### 4.1 Start the server

1.  Open the app.
2.  In **Server parameters**, set:
    *   **Listening Port** (e.g. `8080`).
    *   **OAuth / Client Proxy API Key** — optional bearer-token gate. Anything non-empty enforces `Authorization: Bearer <key>` on every request.
    *   **Routing Model Provider Strategy** — `Cloud Gemini API` or `LiteRT-LM`.
    *   **Device-Stored Gemini API Key** — required for the cloud path unless you supplied one at build time (§3.2 Method B).
3.  Tap **Save & detach socket** to persist.
4.  Tap **Start Gateway Server**. The status banner turns green and the **Compatible Base URL** field shows e.g. `http://192.168.1.144:8080`.

### 4.2 Download LiteRT-LM weights

Local mode needs weights on disk. In the **On-device gated models** section:

1.  Find the row for the model you want (e.g. `Gemma3-1B-IT`, the smallest practical option at ~580 MB).
2.  Tap **Download file**. Live percentage and bytes-per-second are reported.
3.  When done the row shows **Downloaded & ready** and the storage path. The badge is real — `GET /v1/models` will now include this id.

> **Where do downloads land?** §4.3.

### 4.3 Storage paths and Android scoped storage

The model registry advertises paths under `/sdcard/Android/data/com.google.ai.edge.gallery/files/...`. On Android 11+ (API 30+), scoped storage means **this app cannot read another app's external private directory**. To avoid silent breakage:

*   The download lands in **this app's own** external files directory: `Context.getExternalFilesDir(null)/<sub-path>`.
*   `LocalModelInfo.getResolvedTargetFile(context)` first probes the gallery-style path (in case it exists from another mechanism) and otherwise falls back to the app's own dir.
*   The path you see in the UI's **Storage target path** row is the actual resolved file path on your device, not the advertised `/sdcard/...` constant.

If you side-load weights via `adb push` from a workstation, push them to the resolved path the UI shows — not the `/sdcard/...` one.

### 4.4 Test it

From any machine on the same Wi-Fi:

```bash
curl -X POST http://192.168.1.144:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <gateway-api-key-or-omit-if-blank>' \
  -d '{
    "model": "litert-community/Gemma3-1B-IT",
    "messages": [
      { "role": "system", "content": "You are a helpful assistant." },
      { "role": "user",   "content": "What is 2+2?" }
    ],
    "temperature": 0.3
  }'
```

Successful response (LiteRT-LM example):

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1779515541,
  "model": "litert-community/Gemma3-1B-IT",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "4" },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 12, "completion_tokens": 1, "total_tokens": 13 },
  "system_fingerprint": "litertlm:gpu:312ms"
}
```

`system_fingerprint` carries the real backend used (`cpu` or `gpu`) and the measured total latency in milliseconds. Token counts from LiteRT-LM are 4-chars-per-token approximations (the SDK does not surface token counts in 0.11.0).

If you ask for an unknown model id:

```json
{
  "error": {
    "message": "Unknown model id: 'gpt-99'. Call GET /v1/models to list available ids.",
    "type": "model_not_found",
    "code": 400
  }
}
```

If you select `LiteRT-LM` mode but the `.litertlm` file isn't downloaded:

```json
{
  "error": {
    "message": "LiteRT-LM weights for 'litert-community/Gemma3-1B-IT' are not on this device. Expected at: /storage/emulated/0/Android/data/<your-app-id>/files/.../gemma3-1b-it-int4.litertlm",
    "type": "model_not_found",
    "code": 400
  }
}
```

### 4.5 Watching traffic

The **Gateway inward traffic logs** panel at the bottom shows the most recent 100 requests with method, path, status code, request model, client IP, duration, and a 120-character preview of the response. Failed routing decisions surface with their `RoutingError` type (`model_not_found`, `provider_mismatch`, `gateway_setup_error`, `not_implemented`).

---

## 5. Tests

The repository ships with:

*   `app/src/test/java/com/example/ExampleUnitTest.kt` — placeholder JUnit.
*   `app/src/test/java/com/example/ExampleRobolectricTest.kt` — Robolectric smoke.
*   `app/src/test/java/com/example/GreetingScreenshotTest.kt` — Roborazzi snapshot.
*   `app/src/test/java/com/example/server/ModelRouterTest.kt` — 14 plain-JUnit cases covering the routing surface (`UnknownModel`, `ProviderMismatch`, `WeightsMissing`, `CloudKeyMissing`, `AiCoreUnsupported`, alias resolution forward + reverse, both happy paths, callback laziness).

### 5.1 Running tests

> **Gradle wrapper.** The repo does not currently check in a `gradlew` wrapper script. Run from Android Studio (which provides one), or use a system Gradle 8.x with the AGP 9.1.x plugin loaded.

From Android Studio: open the **Gradle** tool window → `app` → `Tasks` → `verification` → `testDebugUnitTest`.

From a system Gradle:

```bash
gradle :app:testDebugUnitTest          # JUnit + Robolectric
gradle :app:verifyRoborazziDebug       # screenshot regression check
gradle :app:recordRoborazziDebug       # update screenshot baselines
```

### 5.2 On-device smoke recipe

A smoke test for the real LiteRT-LM path (which can't be exercised from CI without device hardware):

1.  Install the debug APK on a device with ≥ 4 GB RAM (Pixel 7+ class).
2.  Download `litert-community/Gemma3-1B-IT` from the model list (~580 MB).
3.  Set provider to `LOCAL_VAL`, start the server.
4.  Run the curl in §4.4. First call: 10–60 s (engine load). Subsequent calls: < 5 s.
5.  Verify `system_fingerprint` matches `litertlm:gpu:<n>ms` or `litertlm:cpu:<n>ms` with non-trivial `<n>`. The math prompt should return a number, not a canned acknowledgement.

---

## 6. Diagnostics

### 6.1 `InputDispatcher: Channel is unrecoverably broken`

Visible in Logcat during dev iteration:

```
E/InputDispatcher: channel '... com.aistudio.aiproxygateway.jxrqtm/com.example.MainActivity' ~ Channel is unrecoverably broken and will be disposed!
```

Normal. Android's package manager kills the previous process when you re-deploy; the OS prints this when it tears down the dead process's window. Not a crash and not a leak.

### 6.2 Socket lifecycle invariants

*   `GatewayViewModel.onCleared()` calls `serverManager.stopServer()`, so screen rotations and process death close the listening socket and release the LiteRT-LM engine's native memory.
*   `ProxyServerManager.startServer` / `rebootServer` / `stopServer` serialize through a `serverMutex` so concurrent UI taps cannot cause `BindException`.
*   The accept loop runs in a separate child coroutine from the lock-held one, so a slow `accept()` does not pin the mutex.

### 6.3 Why the package id has a random suffix

`applicationId = "com.aistudio.aiproxygateway.jxrqtm"` — the suffix was added by Google AI Studio's web scaffolder. Functionally harmless but cosmetically odd. Renaming it is a backwards-incompatible change for any installed copy and is tracked in `plan.md` §11.

---

## 7. What's next

See [`plan.md`](plan.md) for the full status table and the ordered list of remaining work — streaming (SSE), function calling, multimodal input, AICore, KV cache reuse, per-model provider selection, and the namespace cleanup.

---

*AI Proxy Gateway — local OpenAI-compatible HTTP front-end for Google Gemini cloud and on-device LiteRT-LM.*
