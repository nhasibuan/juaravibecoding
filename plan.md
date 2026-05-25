# Engineering Plan — Real LiteRT-LM Inference + Model-Routing Correctness

**Target repo:** `nhasibuan/juaravibecoding`
**Scope:** Two coupled corrections to `OpenAiToGeminiTranslator` / `ProxyServerManager` / `ModelsRegistry`.
**Goal:** Make the gateway honestly do what its UI and `/v1/models` advertise.
**Author of plan:** Kiro (review-driven; see `Verify ; review` exchange).
**Non-goals:** README rewrite, security hardening of empty-key auth, scoped-storage UX. These are tracked separately.

---

## Table of contents

1. [Problem statement (why this plan exists)](#1-problem-statement)
2. [Guiding principles](#2-guiding-principles)
3. [Architecture target](#3-architecture-target)
4. [Workstream A — Replace `generateLiteRtLmResponse` with real LiteRT-LM inference](#4-workstream-a)
5. [Workstream B — Tighten model routing so `/v1/models` matches dispatch](#5-workstream-b)
6. [Cross-cutting changes](#6-cross-cutting-changes)
7. [Test plan](#7-test-plan)
8. [Rollout / phasing](#8-rollout--phasing)
9. [Risks and mitigations](#9-risks-and-mitigations)
10. [Acceptance criteria](#10-acceptance-criteria)
11. [Out-of-scope follow-ups](#11-out-of-scope-follow-ups)
12. [Appendix A — File-by-file diff summary](#12-appendix-a--file-by-file-diff-summary)
13. [Appendix B — Reference: model-id mapping table](#13-appendix-b--reference-model-id-mapping-table)

---

## 1. Problem statement

### 1.1 Local mode is a simulator pretending to be inference

`OpenAiToGeminiTranslator.generateLiteRtLmResponse(...)` (`app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`):

- Verifies the `.litertlm` file exists on disk, then **does not load it**.
- Builds a hardcoded banner that claims `45.2 tokens/second` and `Time-to-first-token: 120ms`.
- Routes all prompts through `solveSimplePrompt(...)` — a regex/keyword matcher (math operator detection, hardcoded HR text for "candidate"/"resume"/"alice smith", date/time formatting, greetings, generic fallback string).
- For "thinking" models (`gemma-4*`, `DeepSeek-R1*`) prepends a fabricated `<think>...</think>` block.

The README contradicts this with: *"No fake simulations or mock fallbacks are used."* The file is mislabeled, mismetered, and misadvertised. **This is the central correctness defect.**

### 1.2 Model routing is incoherent across the three layers

| Layer | What it claims | What actually happens |
|---|---|---|
| `/v1/models` (`ProxyServerManager.handleClient`) | Lists 4 cloud Gemini ids + every available local LiteRT model + 2 AICore entries | Listing is honest about local availability |
| Cloud dispatch (`isCloudMode` branch) | Honors the requested `model` field | Coerces to `gemini-2.5-pro` if the string contains `"pro"`, else `gemini-2.5-flash`. `1.5-flash` / `1.5-pro` advertised but unreachable. AICore entries ignored entirely. |
| Local dispatch (`isLocalVal` branch) | Uses the requested model | Falls back to `settings.activeModelId` if the requested id isn't in `allowedModels`, then `getModelById(...)` *silently* returns `allowedModels[1]` (Gemma-4-E2B-it) for unknown ids. |
| `ProxySetting.targetProvider` comment | Lists `"CLOUD_GEMINI"`, `"LOCAL_VAL"`, `"MOCK"` | `MOCK` is unwired — falls through to a 400 `unsupported_provider`. |

A request can be advertised, accepted, mapped to a different model, and reported back under the original name — without any error.

### 1.3 Why fix together

The honest LiteRT path needs an honest model registry: which `.litertlm` files we actually load, with what backend, and which ids are reachable. Splitting the work would force `/v1/models` to lie twice — once during transition, once after.

---

## 2. Guiding principles

1. **No silent fallbacks.** Unknown or unavailable models return a real `400 model_not_found` with a structured error.
2. **Advertise = dispatch.** A model id appears in `/v1/models` only if a request naming that id will actually be routed to it.
3. **Prove before mock.** The LiteRT-LM path must produce real inference output on at least one supported model on a real device before merge. If a model is registered but unproven on hardware, mark it `experimental = true` and gate it behind a setting.
4. **Single source of truth for model metadata.** `ModelsRegistry` owns ids, file paths, runtime types, capabilities, and cloud-id mappings. `ProxyServerManager` and the UI only read from it.
5. **Backwards-compatible schema bumps.** New fields on `LocalModelInfo` get safe defaults. `ProxySetting` columns added through a Room migration, not destructive fallback (this is a soft constraint — the repo currently uses `fallbackToDestructiveMigration`; we will keep that for now and flag the migration switch as out-of-scope).

---

## 3. Architecture target

```
Client
  │  POST /v1/chat/completions { model: "<openai-id>", messages: [...] }
  ▼
ProxyServerManager.handleClient
  │
  ├─ ModelRouter.resolve(requestedId, settings)
  │     ├─ returns RoutedModel { runtime: CLOUD | LITERT | AICORE, modelInfo, upstreamId }
  │     └─ throws ModelNotFoundException with explicit reason
  │
  ├─ runtime == CLOUD     → CloudGeminiClient.complete(...)
  ├─ runtime == LITERT    → LiteRtLmEngine.generate(...)
  └─ runtime == AICORE    → 501 not_implemented  (stays out of scope; documented)

LiteRtLmEngine (new, singleton)
  │
  ├─ EngineHandle cache keyed by (modelId, backend)
  ├─ load() under mutex; closes previous engine if model changes
  ├─ generate(prompt, params) on Dispatchers.Default with timeout
  └─ close() on app shutdown / settings change
```

Key new abstractions:

- `ModelRouter` — pure logic, fully unit-testable.
- `LiteRtLmEngine` — thin wrapper over the LiteRT-LM Kotlin API.
- `RoutedModel` — sealed class describing the resolved destination.
- `ModelNotFoundException` / `EngineNotReadyException` — typed errors mapped to OpenAI-shaped JSON.

---

## 4. Workstream A — Replace `generateLiteRtLmResponse` with real LiteRT-LM inference

### 4.1 Choose the integration

Use the **LiteRT-LM Kotlin API** (the official Google AI Edge SDK from `github.com/google-ai-edge/LiteRT-LM`, documented at `https://ai.google.dev/edge/litert-lm/android`). Rationale:

- The model files already in the registry are `.litertlm` — the native format of this SDK. MediaPipe `tasks-genai` expects `.task` files and is officially deprecated in favor of LiteRT-LM.
- Multi-backend support (CPU, GPU, NPU) matches the `accelerators` field already on `LocalModelInfo`.
- The repo's existing `EngineConfig` data class in `ModelsRegistry.kt` is already shaped like the SDK's options. It will be replaced with the real one.

### 4.2 Add the dependency

Update `gradle/libs.versions.toml`:

```toml
[versions]
litertLm = "<pin to current stable release at implementation time>"

[libraries]
litert-lm = { group = "com.google.ai.edge.litert-lm", name = "litert-lm", version.ref = "litertLm" }
```

> Pin the exact version when implementing — do not float. Verify the Maven coordinate against `https://central.sonatype.com/search?q=litert-lm` at the time of work; the group id has shifted historically.

Update `app/build.gradle.kts`:

```kotlin
implementation(libs.litert.lm)
```

If the artifact ships a native `.so` per ABI, also confirm `ndk.abiFilters` and `packaging.jniLibs.useLegacyPackaging = false`.

### 4.3 New file: `app/src/main/java/com/example/inference/LiteRtLmEngine.kt`

Responsibilities:

1. Hold at most **one** loaded engine instance at a time. Loading a 2.5GB model twice will OOM mid-tier devices.
2. Reload only when the resolved `(modelId, backendPreference)` changes.
3. Run `generate(...)` on `Dispatchers.Default` with a configurable timeout (default 60s, surfaced in `ProxySetting`).
4. Surface streaming token callbacks (used later by SSE; for v1, collect to a single string).
5. Close cleanly on `stopServer()` and on configuration change.

Sketch:

```kotlin
package com.example.inference

import android.content.Context
import com.example.data.LocalModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
// import com.google.ai.edge.litertlm.* // resolve actual package at impl time

class LiteRtLmEngine(private val appContext: Context) {

    data class GenerationParams(
        val maxOutputTokens: Int = 1024,
        val temperature: Float = 1.0f,
        val topP: Float? = null,
        val timeoutMs: Long = 60_000
    )

    sealed class Result {
        data class Ok(
            val text: String,
            val promptTokens: Int,
            val completionTokens: Int,
            val firstTokenLatencyMs: Long,
            val totalLatencyMs: Long,
            val backendUsed: String
        ) : Result()
        data class Err(val type: String, val message: String, val cause: Throwable? = null) : Result()
    }

    private val mutex = Mutex()
    private var loadedKey: String? = null
    private var engine: AutoCloseable? = null  // replace with real LlmEngine type

    suspend fun ensureLoaded(model: LocalModelInfo): Result.Err? = mutex.withLock {
        val file = model.getResolvedTargetFile(appContext)
        if (!file.exists() || file.length() == 0L) {
            return Result.Err(
                type = "model_not_found",
                message = "LiteRT-LM weight file missing for ${model.modelId} at ${file.absolutePath}"
            )
        }
        val key = "${model.modelId}|${model.preferredBackend()}"
        if (loadedKey == key) return null
        // close previous
        try { engine?.close() } catch (_: Throwable) { /* swallow */ }
        engine = null
        loadedKey = null
        // load new — wrap SDK exceptions and translate
        try {
            engine = buildEngine(file, model.preferredBackend())
            loadedKey = key
            null
        } catch (t: Throwable) {
            Result.Err("engine_load_failed", "LiteRT-LM failed to load ${model.modelId}: ${t.message}", t)
        }
    }

    suspend fun generate(model: LocalModelInfo, prompt: String, params: GenerationParams): Result {
        val loadErr = ensureLoaded(model)
        if (loadErr != null) return loadErr
        val started = System.currentTimeMillis()
        return withContext(Dispatchers.Default) {
            try {
                withTimeout(params.timeoutMs) {
                    runGenerationOnEngine(prompt, params, started)
                }
            } catch (t: Throwable) {
                Result.Err("inference_failed", t.message ?: "unknown", t)
            }
        }
    }

    suspend fun close() = mutex.withLock {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        loadedKey = null
    }

    // --- private helpers; bind to the real SDK at implementation time ---
    private fun buildEngine(file: java.io.File, backend: String): AutoCloseable { TODO() }
    private fun runGenerationOnEngine(
        prompt: String, params: GenerationParams, startedAt: Long
    ): Result.Ok { TODO() }
}
```

The `TODO()`s map directly onto the SDK's `LlmEngine.create(EngineConfig(...))` and `engine.generateContent(...)` calls. The wrapper is intentionally thin — all OpenAI/Gemini shaping stays in the translator.

### 4.4 Replace the simulator in `OpenAiToGeminiTranslator`

In `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`:

1. **Delete** `solveSimplePrompt(...)`, `generateSimulatedResponse(...)`, and `generateLiteRtLmResponse(...)`. They have no honest use.
2. **Add** a small helper that converts an OpenAI chat-completions request into a single textual prompt suitable for the LiteRT-LM engine. For the v1 cut, concatenate `system\n\n[user] ...\n[assistant] ...` in order and let the model handle role boundaries via its own chat template. Document this is naive and tracked in §11.
3. **Add** a helper that wraps a `LiteRtLmEngine.Result.Ok` into the standard OpenAI Chat Completion JSON, reusing the existing `chatcmpl-...` id and `usage` shape.
4. **Add** a helper that wraps a `LiteRtLmEngine.Result.Err` into an OpenAI-shaped error JSON with `type`, `message`, and an HTTP status code.

Function signatures (final, public):

```kotlin
object OpenAiToGeminiTranslator {
    fun translateRequest(openAiJson: String): String  // unchanged — cloud path
    fun translateResponse(geminiJson: String, openAiModel: String): String  // unchanged

    fun extractPromptForLocalEngine(openAiJson: String): LocalPrompt
    fun wrapLocalSuccess(result: LiteRtLmEngine.Result.Ok, openAiModel: String, prompt: LocalPrompt): String
    fun wrapLocalError(err: LiteRtLmEngine.Result.Err, openAiModel: String): Pair<Int, String>
}

data class LocalPrompt(val text: String, val approxTokenCount: Int)
```

### 4.5 Wire `ProxyServerManager` to the engine

In the `else` (local) branch of `handleClient` in `ProxyServerManager.kt`:

Before:
```kotlin
val liteRtResponse = OpenAiToGeminiTranslator.generateLiteRtLmResponse(
    rawBody, requestModel, modelFile.absolutePath
)
```

After:
```kotlin
val prompt = OpenAiToGeminiTranslator.extractPromptForLocalEngine(rawBody)
val genParams = LiteRtLmEngine.GenerationParams(
    maxOutputTokens = parseMaxTokens(rawBody),
    temperature = parseTemperature(rawBody),
    topP = parseTopP(rawBody)
)
when (val r = liteRtEngine.generate(activeModel, prompt.text, genParams)) {
    is LiteRtLmEngine.Result.Ok -> {
        outputResponseText = OpenAiToGeminiTranslator.wrapLocalSuccess(r, requestModel, prompt)
        sendJsonResponse(outputStream, 200, outputResponseText)
    }
    is LiteRtLmEngine.Result.Err -> {
        val (code, body) = OpenAiToGeminiTranslator.wrapLocalError(r, requestModel)
        httpStatus = code
        outputResponseText = body
        sendJsonResponse(outputStream, code, body)
    }
}
```

`liteRtEngine` is a constructor-injected `LiteRtLmEngine` so it can be swapped in tests.

### 4.6 Lifecycle

- Construct `LiteRtLmEngine` once in the Application or DI container; pass it to `ProxyServerManager` and to `GatewayViewModel`.
- Call `liteRtEngine.close()` from `ProxyServerManager.stopServer()` (after the socket is closed) and from the ViewModel's `onCleared()`.
- On settings change that switches `activeModelId` while the server is running, do **not** preload — the next request will trigger `ensureLoaded`. This avoids long blocking from the UI thread.

### 4.7 Honest metrics

Replace the fabricated banner with values from `LiteRtLmEngine.Result.Ok`. Either:

- Embed a `system_fingerprint` field in the OpenAI response: `litertlm:<modelId>:<backend>:<firstTokenMs>ms`.
- Or attach as `x_local_metrics` extension in `usage` (non-standard but harmless to OpenAI clients).

Decision: embed as `system_fingerprint` because it's part of the official OpenAI schema.

---

## 5. Workstream B — Tighten model routing

### 5.1 Extend `LocalModelInfo`

In `app/src/main/java/com/example/data/ModelsRegistry.kt`, add fields:

```kotlin
data class LocalModelInfo(
    // ...existing fields...
    val cloudUpstreamId: String? = null,   // for runtimeType == "cloud"
    val openAiAliases: List<String> = emptyList(), // accept these client-facing ids
    val experimental: Boolean = false,
    val preferredBackend: String = "cpu"   // "cpu", "gpu", "npu"
)
```

`runtimeType` becomes a sealed enum:

```kotlin
enum class RuntimeType { CLOUD, LITERT_LM, AICORE }
```

Migration: add a string→enum adapter so existing serialized values still parse.

### 5.2 Promote cloud Gemini models into the registry

The hardcoded `geminiCloudModels` list inside `handleClient` disappears. Instead the registry contains:

```kotlin
LocalModelInfo(
    name = "Gemini 2.5 Flash",
    modelId = "gemini-2.5-flash",
    modelFile = "system-managed",
    runtimeType = RuntimeType.CLOUD,
    cloudUpstreamId = "gemini-2.5-flash",
    openAiAliases = listOf("gpt-4o-mini", "gpt-3.5-turbo"), // optional convenience aliases
    description = "..."
),
LocalModelInfo(
    name = "Gemini 2.5 Pro",
    modelId = "gemini-2.5-pro",
    cloudUpstreamId = "gemini-2.5-pro",
    // ...
),
LocalModelInfo(
    name = "Gemini 1.5 Flash",
    modelId = "gemini-1.5-flash",
    cloudUpstreamId = "gemini-1.5-flash",
    // ...
),
LocalModelInfo(
    name = "Gemini 1.5 Pro",
    modelId = "gemini-1.5-pro",
    cloudUpstreamId = "gemini-1.5-pro",
    // ...
),
```

Now `/v1/models` and dispatch share one table — see §5.4.

### 5.3 New file: `app/src/main/java/com/example/server/ModelRouter.kt`

Pure logic, no Android imports. Fully unit-testable.

```kotlin
package com.example.server

import com.example.data.LocalModelInfo
import com.example.data.ModelsRegistry
import com.example.data.ProxySetting
import com.example.data.RuntimeType

sealed class RoutedModel {
    data class Cloud(val info: LocalModelInfo) : RoutedModel()
    data class LiteRtLm(val info: LocalModelInfo) : RoutedModel()
    data class AiCore(val info: LocalModelInfo) : RoutedModel()
}

sealed class RoutingError(val httpStatus: Int, val type: String, val message: String) {
    class UnknownModel(id: String) : RoutingError(400, "model_not_found",
        "Unknown model id: '$id'. Call GET /v1/models to list available ids.")
    class ModelDisabled(id: String, reason: String) : RoutingError(400, "model_disabled",
        "Model '$id' is registered but disabled: $reason")
    class ProviderMismatch(id: String, requested: RuntimeType, configured: String) :
        RoutingError(400, "provider_mismatch",
            "Model '$id' has runtime $requested but server is configured as $configured. " +
            "Either switch the gateway to the matching provider or pick a compatible model.")
    class WeightsMissing(id: String, path: String) : RoutingError(400, "model_not_found",
        "LiteRT-LM weights for '$id' are not on device. Expected at: $path")
    class CloudKeyMissing(id: String) : RoutingError(500, "gateway_setup_error",
        "Cloud Gemini key not configured but model '$id' requires it.")
    class AiCoreUnsupported(id: String) : RoutingError(501, "not_implemented",
        "AICore runtime is not yet implemented. Use a LiteRT-LM or Gemini cloud model.")
}

object ModelRouter {

    fun resolve(
        requestedId: String,
        settings: ProxySetting,
        weightsAvailable: (LocalModelInfo) -> Boolean,
        hasCloudKey: () -> Boolean
    ): Result<RoutedModel> {

        val info = ModelsRegistry.findStrict(requestedId)
            ?: return Result.failure(RoutingError.UnknownModel(requestedId).asException())

        return when (info.runtimeType) {
            RuntimeType.CLOUD -> {
                if (settings.targetProvider != "CLOUD_GEMINI")
                    Result.failure(RoutingError.ProviderMismatch(
                        info.modelId, info.runtimeType, settings.targetProvider).asException())
                else if (!hasCloudKey())
                    Result.failure(RoutingError.CloudKeyMissing(info.modelId).asException())
                else Result.success(RoutedModel.Cloud(info))
            }
            RuntimeType.LITERT_LM -> {
                if (settings.targetProvider != "LOCAL_VAL")
                    Result.failure(RoutingError.ProviderMismatch(
                        info.modelId, info.runtimeType, settings.targetProvider).asException())
                else if (!weightsAvailable(info))
                    Result.failure(RoutingError.WeightsMissing(
                        info.modelId, info.targetFilePath).asException())
                else Result.success(RoutedModel.LiteRtLm(info))
            }
            RuntimeType.AICORE -> Result.failure(RoutingError.AiCoreUnsupported(info.modelId).asException())
        }
    }
}
```

`ModelsRegistry.findStrict(id)` is a new function that:

1. Returns the entry whose `modelId == id`.
2. If none, returns the entry where `id in openAiAliases`.
3. Otherwise returns `null`. **No silent fallback.**

`ModelsRegistry.getModelById(...)` keeps existing signature but is **deprecated** with `@Deprecated(message = "...", replaceWith = ...)` so callers migrate, and it now throws instead of silently returning `allowedModels[1]`.

### 5.4 Rewrite `/v1/models`

In `ProxyServerManager.handleClient`, replace the `if (isModelsEndpoint)` block with one that iterates the registry once:

```kotlin
val list = JSONArray()
ModelsRegistry.allowedModels.forEach { m ->
    val available = when (m.runtimeType) {
        RuntimeType.CLOUD -> hasCloudKey()       // adjust ownership label
        RuntimeType.LITERT_LM -> {
            val f = m.getResolvedTargetFile(context)
            f.exists() && f.length() > 0
        }
        RuntimeType.AICORE -> false  // honest: not implemented
    }
    if (!available) return@forEach

    list.put(JSONObject()
        .put("id", m.modelId)
        .put("object", "model")
        .put("created", 1710000000)
        .put("owned_by", when (m.runtimeType) {
            RuntimeType.CLOUD -> "google-cloud"
            RuntimeType.LITERT_LM -> "gateway-local"
            RuntimeType.AICORE -> "android-aicore"
        })
        .put("x_runtime", m.runtimeType.name)
        .put("x_experimental", m.experimental)
    )
}
```

This guarantees: **every id listed here will route successfully if `targetProvider` matches.**

### 5.5 Rewrite cloud dispatch

In the cloud branch:

```kotlin
val routed = ModelRouter.resolve(
    requestedId = requestModel,
    settings = settings ?: ProxySetting(),
    weightsAvailable = { it.getResolvedTargetFile(context).let { f -> f.exists() && f.length() > 0 } },
    hasCloudKey = { resolvedGeminiKey().isNotEmpty() }
).getOrElse { err ->
    val re = err as RoutingErrorException
    sendJsonResponse(outputStream, re.error.httpStatus, jsonError(re.error))
    return@withContext
}

when (routed) {
    is RoutedModel.Cloud -> {
        val upstream = routed.info.cloudUpstreamId!!
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$upstream:generateContent?key=${resolvedGeminiKey()}"
        // ... existing OkHttp call, unchanged
    }
    is RoutedModel.LiteRtLm -> error("router gave LiteRT in CLOUD branch")
    is RoutedModel.AiCore -> error("router gave AiCore — should have failed earlier")
}
```

The substring-`pro` heuristic is gone. `requestModel` flows through unchanged into the OpenAI response so clients see the id they asked for.

### 5.6 Local dispatch — same shape, different destination

```kotlin
when (routed) {
    is RoutedModel.LiteRtLm -> {
        val (status, body) = runLiteRtLmAndShape(routed.info, rawBody, requestModel)
        httpStatus = status
        outputResponseText = body
        sendJsonResponse(outputStream, status, body)
    }
    // ...
}
```

### 5.7 Retire `MOCK`

`ProxySetting.targetProvider`'s comment lists `"MOCK"` but no code path handles it. Remove `"MOCK"` from the comment in this PR. (A real "mock for tests" provider would only ever live in a debug build flavor — out of scope here.)

---

## 6. Cross-cutting changes

### 6.1 Errors as a first-class concern

Add `app/src/main/java/com/example/server/HttpErrors.kt`:

```kotlin
fun jsonError(e: RoutingError): String =
    JSONObject().put("error", JSONObject()
        .put("message", e.message)
        .put("type", e.type)
        .put("code", e.httpStatus)).toString()
```

Use this everywhere the server returns non-200 to keep the wire format consistent.

### 6.2 Logging

`GatewayLog` already captures `requestModel`, `status`, `responsePreview`. Add to `responsePreview` the routing decision when an error is returned:

> `"routing_error: model_not_found — Unknown model id: 'gpt-5'."`

This makes the dashboard immediately diagnose mis-routes.

### 6.3 Settings UI

`GatewayScreen` model-selection row should:

- Disable cloud-only ids when `targetProvider == LOCAL_VAL` and vice versa, instead of allowing the user to pick a combo that will only error at request time.
- Show a `(experimental)` badge when `experimental = true`.

### 6.4 Backwards compatibility for clients

Clients that send `"gpt-4o"` or other non-mapped ids must now get a clear `400 model_not_found`. Mitigate by:

- Adding common OpenAI ids to `openAiAliases` on `gemini-2.5-flash` (e.g., `gpt-4o-mini`, `gpt-3.5-turbo`) so old integrations don't break.
- Documenting the change in `CHANGELOG.md`.

---

## 7. Test plan

### 7.1 Unit tests (Robolectric, no device)

`app/src/test/java/com/example/server/ModelRouterTest.kt`:

- Unknown id → `UnknownModel`.
- Known cloud id with `LOCAL_VAL` provider → `ProviderMismatch`.
- Known cloud id with empty key → `CloudKeyMissing`.
- Known LiteRT id with `weightsAvailable = false` → `WeightsMissing`.
- Alias resolution: `"gpt-4o-mini"` → `gemini-2.5-flash`.
- AICore id → `AiCoreUnsupported`.

`OpenAiToGeminiTranslatorTest.kt`:

- `extractPromptForLocalEngine` produces ordered text from system + user + assistant turns.
- `wrapLocalSuccess` produces a strictly OpenAI-compliant JSON: required keys present, `usage` numeric, `choices[0].finish_reason` ∈ {stop,length,content_filter}.
- `wrapLocalError` maps each `Result.Err.type` to the documented HTTP code.

### 7.2 Integration tests

`ProxyServerManagerIntegrationTest.kt` (Robolectric + a fake `LiteRtLmEngine`):

- `GET /v1/models` returns only entries whose runtime is reachable given current settings.
- `POST /v1/chat/completions` with the model id of every advertised entry returns 200 (using a fake engine that echoes).
- Asking for a non-listed id returns 400 with the documented body.

### 7.3 On-device smoke test (manual)

Ship a debug-only `LocalEngineSmokeTest` Activity (or hidden gesture) that:

1. Loads `Gemma3-1B-IT` (smallest weights, ~580MB).
2. Runs three fixed prompts: math, "Translate to French: hello world", a long-form summarization prompt.
3. Reports first-token latency and tokens/sec.

Acceptance: the math prompt returns a number, the translation contains "bonjour", the summarization is non-empty and not equal to the prompt. **Without this passing once on real hardware, the LiteRT path stays behind a `BuildConfig.ENABLE_LITERT_LM = false` flag.**

### 7.4 Regression — Roborazzi screenshots

`GreetingScreenshotTest.kt` style: add a screenshot for the model picker disabled-state when provider is `LOCAL_VAL`, to lock §6.3.

---

## 8. Rollout / phasing

Two PRs, sequenced:

**PR 1 — "Routing correctness"** (Workstream B + cross-cutting):

- Adds `RuntimeType` enum, registry fields, `ModelRouter`, `findStrict`, deprecates `getModelById`.
- Rewrites `/v1/models` and dispatch to use the router.
- Keeps the simulator in `generateLiteRtLmResponse` *unchanged*, but renames it to `generateLiteRtLmStub` and surrounds it with a `BuildConfig.LITERT_LM_STUB` feature flag default-on, with a runtime warning header in the response: `system_fingerprint = "stub:not-real-inference"`.
- Result: `/v1/models` and dispatch are now coherent. Local mode is *labeled* as a stub instead of lying about it.

**PR 2 — "Real LiteRT-LM inference"** (Workstream A):

- Adds `LiteRtLmEngine`, the Gradle dependency, the lifecycle wiring.
- Replaces `generateLiteRtLmStub` with the real engine path behind `BuildConfig.LITERT_LM_REAL`.
- Default: `LITERT_LM_REAL = true` for debug, `false` for release until the on-device smoke test passes for at least one model on the target hardware.
- Removes `generateSimulatedResponse` and `solveSimplePrompt`.

This split lets the routing fix ship without waiting for SDK integration to clear device QA.

---

## 9. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| LiteRT-LM SDK API drift between plan and implementation | Medium | Med | Pin the version in `libs.versions.toml`; isolate SDK calls behind `LiteRtLmEngine`'s two private functions. |
| OOM loading a 2.5–4.9GB model on low-RAM devices | High for E4B; low for 1B | High | Enforce `minDeviceMemoryInGb` check before `ensureLoaded`. Surface a `device_underspecced` error rather than crashing. |
| GPU/NPU backend unavailable on the target device, falling back to CPU silently | Medium | Low | `Result.Ok.backendUsed` is captured and surfaced in `system_fingerprint`. |
| Adding cloud Gemini ids to the registry forces a Room migration if anything persists `runtimeType` | Low | Med | Only `activeModelId` is persisted as a string in `ProxySetting`. The enum lives in code only. No DB migration needed. |
| Existing clients break because `gpt-4o` no longer routes | Medium | Med | Alias to `gemini-2.5-flash`. Document in CHANGELOG. |
| `targetProvider` coupling: a single setting can't allow mixed local/cloud usage based on requested model | Inherent | Med | Future work; flagged in §11. For now, `ProviderMismatch` returns a clear error message. |
| LiteRT-LM dependency increases APK size meaningfully | High | Low–Med | Acceptable; LiteRT-LM is the product's reason for existing. Confirm with `apkanalyzer` before/after. |
| Streaming (SSE) clients expect `text/event-stream`; we currently return single JSON | N/A v1 | N/A | Out of scope; tracked in §11. |

---

## 10. Acceptance criteria

A reviewer can verify each of the following from the code and CI alone:

1. `OpenAiToGeminiTranslator` contains **no** function that fabricates token counts, latency numbers, or hardcoded canned responses.
2. Every model id returned by `GET /v1/models` resolves to a successful route in `ModelRouter` under the current `targetProvider` and key/weight availability.
3. Requesting an unknown model id returns HTTP 400 with `{"error":{"type":"model_not_found", ...}}`.
4. Requesting a known model id whose runtime doesn't match the configured provider returns HTTP 400 with `type = "provider_mismatch"`.
5. `LiteRtLmEngine.generate(...)` runs against a real loaded `.litertlm` file (verified by the on-device smoke test with logged first-token latency).
6. `ModelRouterTest` passes with ≥ 6 cases covering each `RoutingError` subclass.
7. `getModelById` either: (a) is deleted, or (b) is `@Deprecated` and throws on unknown id — never silently returns `allowedModels[1]`.
8. The "MOCK" provider literal is gone from `ProxySetting.kt`.
9. `README.md` claim *"No fake simulations or mock fallbacks are used"* is now true (or the README is updated in a follow-up PR — track the README delta as a checklist item).

---

## 11. Out-of-scope follow-ups

Tracked as separate issues; *not* in this plan's PRs:

- Streaming (SSE) responses for `/v1/chat/completions` with `stream: true`.
- Function calling / tool use translation.
- Multi-turn KV cache reuse across requests in the LiteRT engine (currently every request is stateless).
- AICore runtime implementation.
- Replacing `fallbackToDestructiveMigration()` in `AppDatabase` with real migrations.
- README correctness pass (secrets-plugin path, `local.properties` references, scoped-storage caveat, simulator → real LiteRT).
- Rationalizing the namespace `com.example` vs applicationId `com.aistudio.aiproxygateway.jxrqtm`.
- Per-model independent provider selection (so a LiteRT model and a cloud model can both be reachable from the same gateway without flipping `targetProvider`).
- Image / audio modality input plumbing in the prompt extractor (`gemma-4-*` and `gemma-3n-*` advertise these but `extractPromptForLocalEngine` v1 ignores non-text parts).

---

## 12. Appendix A — File-by-file diff summary

| File | PR 1 (routing) | PR 2 (real engine) |
|---|---|---|
| `gradle/libs.versions.toml` | — | + `litert-lm` |
| `app/build.gradle.kts` | — | + `implementation(libs.litert.lm)` |
| `app/src/main/java/com/example/data/ModelsRegistry.kt` | + `RuntimeType` enum, +cloud Gemini entries, +`findStrict`, deprecate `getModelById`, +`cloudUpstreamId`/`openAiAliases`/`experimental`/`preferredBackend` | — |
| `app/src/main/java/com/example/data/ProxySetting.kt` | Comment cleanup (drop `"MOCK"`) | — |
| `app/src/main/java/com/example/server/ModelRouter.kt` | **new** | — |
| `app/src/main/java/com/example/server/HttpErrors.kt` | **new** | — |
| `app/src/main/java/com/example/server/ProxyServerManager.kt` | Replace `/v1/models` block, replace dispatch to use `ModelRouter`, remove substring-`pro` mapping | Wire `LiteRtLmEngine`, replace stub call |
| `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt` | Rename `generateLiteRtLmResponse` → `generateLiteRtLmStub`, gate by `BuildConfig` | Delete stub + `generateSimulatedResponse` + `solveSimplePrompt`; add `extractPromptForLocalEngine` / `wrapLocalSuccess` / `wrapLocalError` |
| `app/src/main/java/com/example/inference/LiteRtLmEngine.kt` | — | **new** |
| `app/src/main/java/com/example/ui/GatewayScreen.kt` | Disable incompatible models in picker; show experimental badge | — |
| `app/src/main/java/com/example/MainActivity.kt` or DI host | Pass router/engine via constructor | Pass engine; close on shutdown |
| `app/src/test/java/com/example/server/ModelRouterTest.kt` | **new** | — |
| `app/src/test/java/com/example/server/OpenAiToGeminiTranslatorTest.kt` | + tests for error mapping (using stub) | + tests for `extractPromptForLocalEngine`, `wrapLocalSuccess` |
| `CHANGELOG.md` | Document model-id breaking changes + alias list | Document real LiteRT enablement and feature flag |

---

## 13. Appendix B — Reference: model-id mapping table

After PR 1, the following table is the **single source of truth**. `ModelRouter` enforces it; `/v1/models` reflects it; the UI reads it.

| Client-facing id (from request) | Runtime | Upstream / on-device target | Notes |
|---|---|---|---|
| `gemini-2.5-flash` | CLOUD | `gemini-2.5-flash` | Default cloud target |
| `gemini-2.5-pro` | CLOUD | `gemini-2.5-pro` | |
| `gemini-1.5-flash` | CLOUD | `gemini-1.5-flash` | Now actually reachable |
| `gemini-1.5-pro` | CLOUD | `gemini-1.5-pro` | Now actually reachable |
| `gpt-4o-mini` (alias) | CLOUD | `gemini-2.5-flash` | OpenAI-client convenience |
| `gpt-3.5-turbo` (alias) | CLOUD | `gemini-2.5-flash` | OpenAI-client convenience |
| `litert-community/gemma-4-E2B-it-litert-lm` | LITERT_LM | `gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm` | thinking, image, audio |
| `litert-community/gemma-4-E4B-it-litert-lm` | LITERT_LM | `gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm` | thinking, image, audio |
| `google/gemma-3n-E2B-it-litert-lm` | LITERT_LM | `gemma-3n-E2B-it-int4.litertlm` | image, audio |
| `google/gemma-3n-E4B-it-litert-lm` | LITERT_LM | `gemma-3n-E4B-it-int4.litertlm` | image, audio |
| `litert-community/Gemma3-1B-IT` | LITERT_LM | `gemma3-1b-it-int4.litertlm` | smallest, used for smoke test |
| `litert-community/Qwen2.5-1.5B-Instruct` | LITERT_LM | `Qwen2.5-1.5B-Instruct_..._q8_ekv4096.litertlm` | |
| `litert-community/DeepSeek-R1-Distill-Qwen-1.5B` | LITERT_LM | `DeepSeek-R1-Distill-Qwen-1.5B_..._q8_ekv4096.litertlm` | thinking |
| `litert-community/functiongemma-270m-ft-tiny-garden` | LITERT_LM | `tiny_garden.litertlm` | CPU-only |
| `litert-community/functiongemma-270m-ft-mobile-actions` | LITERT_LM | `mobile_actions.litertlm` | CPU-only |
| `aicore-gemma-4-e2b` | AICORE | — | 501 not_implemented (until §11 lands) |
| `aicore-gemma-4-e4b` | AICORE | — | 501 not_implemented |
| anything else | — | — | 400 `model_not_found` |

---

*End of plan.*
