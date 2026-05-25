# Engineering Plan — Real LiteRT-LM Inference + Model-Routing Correctness

**Target repo:** `nhasibuan/juaravibecoding`
**Goal:** Make the gateway honestly do what its UI and `/v1/models` advertise.
**Author of plan:** Kiro (review-driven; verified against `google-ai-edge/gallery`).
**Non-goals:** README rewrite, security hardening of empty-key auth, scoped-storage UX. These are tracked separately.

---

## Status (latest)

| Workstream | Status | Where |
|---|---|---|
| A. Replace `generateLiteRtLmResponse` with real LiteRT-LM inference | **✅ Implemented** | [PR #1 `litert-lm-real-inference`](https://github.com/nhasibuan/juaravibecoding/pull/1) |
| B. Tighten model routing so `/v1/models` matches dispatch | ⏳ Queued — to be opened after PR #1 merges | _TBD_ |

**Evidence that A is done in PR #1:**

- New `app/src/main/java/com/example/inference/LiteRtLmEngine.kt` — singleton mirroring gallery's `LlmChatModelHelper` (`Engine` + `Conversation` lifecycle, capability probe, single-load-at-a-time, NPU/TPU sampler-null rule).
- `OpenAiToGeminiTranslator` no longer contains `solveSimplePrompt`, `generateSimulatedResponse`, or `generateLiteRtLmResponse`. Replaced by `extractLocalEngineRequest`, `wrapLocalSuccess`, `wrapLocalError`. The success wrapper sets `system_fingerprint = "litertlm:<backend>:<latencyMs>ms"` for honest provenance — no fabricated `tokens/second` numbers.
- `ProxyServerManager.handleClient` LOCAL_VAL branch calls `LiteRtLmEngine.ensureLoadedAndReset(...)` then `LiteRtLmEngine.generate(...)`. `stopServer()` also tears down the engine to release native memory.
- Dependency added: `com.google.ai.edge.litertlm:litertlm-android:0.11.0` (coordinate verified against gallery's `Android/src/gradle/libs.versions.toml`).

> The acceptance criteria in §10 below are annotated with each item's current status.

---

## Table of contents

1. [Problem statement (why this plan exists)](#1-problem-statement)
2. [Guiding principles](#2-guiding-principles)
3. [Architecture target](#3-architecture-target)
4. [Workstream A — Replace simulator with real LiteRT-LM (✅ landed in PR #1)](#4-workstream-a)
5. [Workstream B — Tighten model routing (⏳ queued)](#5-workstream-b)
6. [Cross-cutting changes](#6-cross-cutting-changes)
7. [Test plan](#7-test-plan)
8. [Rollout / phasing](#8-rollout--phasing)
9. [Risks and mitigations](#9-risks-and-mitigations)
10. [Acceptance criteria](#10-acceptance-criteria)
11. [Out-of-scope follow-ups](#11-out-of-scope-follow-ups)
12. [Appendix A — File-by-file diff summary](#12-appendix-a--file-by-file-diff-summary)
13. [Appendix B — Reference: model-id mapping table](#13-appendix-b--reference-model-id-mapping-table)
14. [Appendix C — Verified LiteRT-LM Kotlin API surface (from gallery)](#14-appendix-c--verified-litert-lm-kotlin-api-surface-from-gallery)

---

## 1. Problem statement

### 1.1 Local mode was a simulator pretending to be inference *(historical, fixed in PR #1)*

The old `OpenAiToGeminiTranslator.generateLiteRtLmResponse(...)`:

- Verified the `.litertlm` file existed on disk, then **never loaded it**.
- Built a hardcoded banner claiming `45.2 tokens/second` and `Time-to-first-token: 120ms`.
- Routed all prompts through `solveSimplePrompt(...)` — a regex/keyword matcher (math operator detection, hardcoded HR text for "candidate"/"resume"/"alice smith", date/time formatting, greetings, generic fallback string).
- For "thinking" models (`gemma-4*`, `DeepSeek-R1*`) prepended a fabricated `<think>...</think>` block.

The README claimed *"No fake simulations or mock fallbacks are used."* This was false. PR #1 makes that claim true by routing through a real `Engine` + `Conversation` from the LiteRT-LM Kotlin SDK.

### 1.2 Model routing is incoherent across the three layers *(still TODO — Workstream B)*

| Layer | What it claims | What actually happens |
|---|---|---|
| `/v1/models` (`ProxyServerManager.handleClient`) | Lists 4 cloud Gemini ids + every available local LiteRT model + 2 AICore entries | Listing is honest about local availability |
| Cloud dispatch (`isCloudMode` branch) | Honors the requested `model` field | Coerces to `gemini-2.5-pro` if the string contains `"pro"`, else `gemini-2.5-flash`. `1.5-flash` / `1.5-pro` advertised but unreachable. AICore entries ignored entirely. |
| Local dispatch (`isLocalVal` branch) | Uses the requested model | Falls back to `settings.activeModelId` if the requested id isn't in `allowedModels`, then `getModelById(...)` *silently* returns `allowedModels[1]` (Gemma-4-E2B-it) for unknown ids. |
| `ProxySetting.targetProvider` comment | Lists `"CLOUD_GEMINI"`, `"LOCAL_VAL"`, `"MOCK"` | `MOCK` is unwired — falls through to a 400 `unsupported_provider`. |

A request can be advertised, accepted, mapped to a different model, and reported back under the original name — without any error.

### 1.3 Why the order changed

The original plan sequenced **routing first, real engine second** because the routing fix was supposed to be SDK-independent. After studying gallery, the canonical engine-init pattern was crisp enough to land first as a self-contained change with no routing prerequisites — and the higher-correctness fix (no more fabricated metrics) goes out the door immediately. The routing fix is now PR #2.

---

## 2. Guiding principles

1. **No silent fallbacks.** Unknown or unavailable models return a real `400 model_not_found` with a structured error.
2. **Advertise = dispatch.** A model id appears in `/v1/models` only if a request naming that id will actually be routed to it.
3. **Prove before mock.** The LiteRT-LM path must produce real inference output on at least one supported model on a real device before merge. If a model is registered but unproven on hardware, mark it `experimental = true` and gate it behind a setting.
4. **Single source of truth for model metadata.** `ModelsRegistry` owns ids, file paths, runtime types, capabilities, and cloud-id mappings. `ProxyServerManager` and the UI only read from it.
5. **Mirror canonical patterns.** Where Google ships a reference implementation (gallery's `LlmChatModelHelper`), follow its lifecycle exactly. Don't invent.

---

## 3. Architecture target

```
Client
  │  POST /v1/chat/completions { model: "<openai-id>", messages: [...] }
  ▼
ProxyServerManager.handleClient
  │
  ├─ ModelRouter.resolve(requestedId, settings)            ◄── Workstream B
  │     ├─ returns RoutedModel { runtime: CLOUD | LITERT | AICORE, modelInfo, upstreamId }
  │     └─ throws ModelNotFoundException with explicit reason
  │
  ├─ runtime == CLOUD     → CloudGeminiClient.complete(...)
  ├─ runtime == LITERT    → LiteRtLmEngine.ensureLoadedAndReset() + .generate()  ◄── Workstream A (DONE)
  └─ runtime == AICORE    → 501 not_implemented  (stays out of scope; documented)

LiteRtLmEngine (DONE: app/src/main/java/com/example/inference/LiteRtLmEngine.kt)
  │
  ├─ Singleton: at most one Engine + Conversation in memory
  ├─ LoadKey(modelId, backend, maxTokens) — reload only when this changes
  ├─ ensureLoadedAndReset(): loads engine + (re)creates Conversation per request
  ├─ generate(prompt) on Dispatchers.Default
  └─ close() on stopServer()
```

Key abstractions:

- ✅ `LiteRtLmEngine` — thin wrapper over the LiteRT-LM Kotlin SDK (DONE in PR #1).
- ⏳ `ModelRouter` — pure routing logic, fully unit-testable (Workstream B).
- ⏳ `RoutedModel` — sealed class describing the resolved destination (Workstream B).
- ⏳ Typed `RoutingError` mapped to OpenAI-shaped JSON (Workstream B).

---

## 4. Workstream A — Replace simulator with real LiteRT-LM (✅ landed in PR #1)

### 4.1 SDK choice — verified

Use the **LiteRT-LM Kotlin API**: `com.google.ai.edge.litertlm:litertlm-android:0.11.0`.

Verification: this is the exact group/artifact/version used by `google-ai-edge/gallery` (see `Android/src/gradle/libs.versions.toml` in the gallery repo). The `.litertlm` model files already in our registry are the SDK's native format. MediaPipe's `tasks-genai` is officially deprecated in favor of LiteRT-LM and expects a different file format anyway.

### 4.2 Dependency wiring

`gradle/libs.versions.toml`:

```toml
[versions]
litertlm = "0.11.0"

[libraries]
litertlm = { group = "com.google.ai.edge.litertlm", name = "litertlm-android", version.ref = "litertlm" }
```

`app/build.gradle.kts`:

```kotlin
implementation(libs.litertlm)
```

### 4.3 New file: `app/src/main/java/com/example/inference/LiteRtLmEngine.kt`

Responsibilities (as built):

1. Hold at most **one** loaded `Engine` instance at a time, keyed by `(modelId, backend, maxTokens)`. Loading a multi-GB model twice would OOM mid-tier devices.
2. Reload only when the key changes; otherwise reuse the engine and just reset the conversation.
3. Run `generate(...)` on `Dispatchers.Default`.
4. Capability-probe the model file once (via `Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }`) and toggle `ExperimentalFlags.enableSpeculativeDecoding` around `Engine(...)` construction — exactly as gallery does.
5. Reset the conversation on every request. The gateway is stateless from OpenAI's perspective; clients send full history every call.
6. Close cleanly on `stopServer()` and on configuration change.

Public surface:

```kotlin
object LiteRtLmEngine {
    data class GenerationParams(maxOutputTokens, temperature, topK, topP)
    sealed class Result { data class Ok(...); data class Err(...) }
    enum class HistoryRole { USER, ASSISTANT }
    data class HistoryTurn(role, text)

    suspend fun ensureLoadedAndReset(
        context, model: LocalModelInfo, params, systemInstruction: String?,
        history: List<HistoryTurn>
    ): Result.Err?      // null on success

    suspend fun generate(userText: String): Result
    fun cancel()
    suspend fun close()
}
```

The full implementation is in the file; appendix C records the exact SDK calls used.

### 4.4 `OpenAiToGeminiTranslator` — simulator removed

Deleted: `solveSimplePrompt(...)`, `generateSimulatedResponse(...)`, `generateLiteRtLmResponse(...)`.

Added:

- `extractLocalEngineRequest(openAiJson) → LocalEngineRequest` — splits the OpenAI body into:
  - System instruction (concatenated from all `system` role messages)
  - History turns (every `assistant` message and every `user` message except the trailing one)
  - Latest user prompt (the trailing `user` message)
  - Generation params (`max_tokens` / `max_completion_tokens`, `temperature`, `top_p`, `top_k`)
- `wrapLocalSuccess(Result.Ok, openAiModel) → String` — produces a strictly OpenAI-compliant chat-completion JSON. If the model exposes a thought channel (`message.channels["thought"]`), it is rendered as `<think>...</think>` ahead of the answer. The `system_fingerprint` field carries `litertlm:<backend>:<latencyMs>ms`.
- `wrapLocalError(Result.Err, openAiModel) → Pair<Int, String>` — maps each error type to an HTTP status and an OpenAI-shaped `{ "error": { ... } }` body.

### 4.5 `ProxyServerManager` wiring

In the LOCAL_VAL branch (after the model-file existence check):

```kotlin
val req = OpenAiToGeminiTranslator.extractLocalEngineRequest(rawBody)
val params = LiteRtLmEngine.GenerationParams(
    maxOutputTokens = req.maxTokens,
    temperature = req.temperature,
    topK = req.topK,
    topP = req.topP
)
val loadErr = LiteRtLmEngine.ensureLoadedAndReset(
    context = context,
    model = activeModel,
    params = params,
    systemInstruction = req.systemInstruction,
    history = req.history
)
if (loadErr != null) {
    val (code, body) = OpenAiToGeminiTranslator.wrapLocalError(loadErr, requestModel)
    sendJsonResponse(outputStream, code, body)
} else {
    when (val r = LiteRtLmEngine.generate(req.latestUserText)) {
        is LiteRtLmEngine.Result.Ok ->
            sendJsonResponse(outputStream, 200, OpenAiToGeminiTranslator.wrapLocalSuccess(r, requestModel))
        is LiteRtLmEngine.Result.Err -> {
            val (code, body) = OpenAiToGeminiTranslator.wrapLocalError(r, requestModel)
            sendJsonResponse(outputStream, code, body)
        }
    }
}
```

`stopServer()` additionally calls `LiteRtLmEngine.close()` outside the server-socket mutex so a slow native shutdown doesn't block subsequent server starts.

### 4.6 Honest metrics (replaces fabricated banner)

Real values from `Result.Ok` are surfaced via the OpenAI-standard `system_fingerprint` field: `"litertlm:<backendUsed>:<totalLatencyMs>ms"`. No `45.2 tokens/second` claim. Token counts in `usage` are honest approximations (4-chars-per-token heuristic, since `Message` does not surface counts in 0.11.0); they are clearly approximations rather than fabricated GPU metrics.

### 4.7 Backend selection — conservative default

`LocalModelInfo.accelerators` is a CSV like `"cpu,gpu"` or `"npu"`. `LiteRtLmEngine.pickBackend` prefers `gpu`, falls back to `cpu`, and intentionally **downgrades NPU to CPU** for now: NPU requires the app's `nativeLibraryDir` plus a vendor-shipped plug-in that isn't reliably available across devices. This is a deliberate safety choice; lifting it later is tracked in §11.

### 4.8 Deviations from gallery — documented

| Aspect | Gallery (`LlmChatModelHelper`) | Our `LiteRtLmEngine` | Why |
|---|---|---|---|
| Generation API | `Conversation.sendMessageAsync` (streaming, `MessageCallback`) | `Conversation.sendMessage` (synchronous) | We return one OpenAI JSON, not SSE. |
| Conversation lifetime | Persists across user turns within one chat session | Reset on every request | Stateless gateway; OpenAI clients re-send history. |
| Multimodality | Image + audio backends configurable | `visionBackend = null`, `audioBackend = null` | v1 = text-only; tracked in §11. |
| NPU | `Backend.NPU(nativeLibraryDir = ...)` available | Downgraded to CPU silently | Vendor-plugin fragility; opt-in later. |

---

## 5. Workstream B — Tighten model routing (⏳ queued, opens after PR #1 merges)

### 5.1 Extend `LocalModelInfo`

In `app/src/main/java/com/example/data/ModelsRegistry.kt`, add fields:

```kotlin
data class LocalModelInfo(
    // ...existing fields...
    val cloudUpstreamId: String? = null,   // for runtimeType == CLOUD
    val openAiAliases: List<String> = emptyList(), // accept these client-facing ids
    val experimental: Boolean = false,
    val preferredBackend: String = "cpu"   // "cpu", "gpu", "npu"
)
```

`runtimeType` becomes a typed enum (currently a free-form string `"litert-lm"` / `"aicore"` / unset for cloud):

```kotlin
enum class RuntimeType { CLOUD, LITERT_LM, AICORE }
```

Migration: add a string→enum adapter so existing serialized values still parse. (No DB migration needed because nothing persists `runtimeType`.)

### 5.2 Promote cloud Gemini models into the registry

The hardcoded `geminiCloudModels` list inside `handleClient` disappears. Instead the registry contains:

```kotlin
LocalModelInfo(
    name = "Gemini 2.5 Flash",
    modelId = "gemini-2.5-flash",
    modelFile = "system-managed",
    runtimeType = RuntimeType.CLOUD,
    cloudUpstreamId = "gemini-2.5-flash",
    openAiAliases = listOf("gpt-4o-mini", "gpt-3.5-turbo"),
    description = "..."
),
// ...gemini-2.5-pro, gemini-1.5-flash, gemini-1.5-pro
```

Now `/v1/models` and dispatch share one table.

### 5.3 New file: `app/src/main/java/com/example/server/ModelRouter.kt`

Pure logic, no Android imports. Fully unit-testable.

```kotlin
sealed class RoutedModel {
    data class Cloud(val info: LocalModelInfo) : RoutedModel()
    data class LiteRtLm(val info: LocalModelInfo) : RoutedModel()
    data class AiCore(val info: LocalModelInfo) : RoutedModel()
}

sealed class RoutingError(val httpStatus: Int, val type: String, val message: String) {
    class UnknownModel(id) : RoutingError(400, "model_not_found", ...)
    class ModelDisabled(id, reason) : RoutingError(400, "model_disabled", ...)
    class ProviderMismatch(id, requested, configured) : RoutingError(400, "provider_mismatch", ...)
    class WeightsMissing(id, path) : RoutingError(400, "model_not_found", ...)
    class CloudKeyMissing(id) : RoutingError(500, "gateway_setup_error", ...)
    class AiCoreUnsupported(id) : RoutingError(501, "not_implemented", ...)
}

object ModelRouter {
    fun resolve(
        requestedId: String,
        settings: ProxySetting,
        weightsAvailable: (LocalModelInfo) -> Boolean,
        hasCloudKey: () -> Boolean
    ): Result<RoutedModel>
}
```

`ModelsRegistry.findStrict(id)`:

1. Returns the entry whose `modelId == id`.
2. If none, returns the entry where `id in openAiAliases`.
3. Otherwise returns `null`. **No silent fallback to `allowedModels[1]`.**

`ModelsRegistry.getModelById(...)` is `@Deprecated` and now throws on unknown id.

### 5.4 Rewrite `/v1/models`

Iterate the registry once, include only entries whose runtime is reachable in the current `targetProvider`:

```kotlin
ModelsRegistry.allowedModels.forEach { m ->
    val available = when (m.runtimeType) {
        RuntimeType.CLOUD -> hasCloudKey()
        RuntimeType.LITERT_LM -> m.getResolvedTargetFile(context).let { it.exists() && it.length() > 0 }
        RuntimeType.AICORE -> false  // honest: not implemented
    }
    if (available) list.put(JSONObject().put("id", m.modelId)...put("x_runtime", m.runtimeType.name))
}
```

Guarantees: **every id listed here will route successfully if `targetProvider` matches.**

### 5.5 Rewrite cloud dispatch

Substring-`pro` heuristic is gone. Routes through `ModelRouter`, then uses `routed.info.cloudUpstreamId!!` to build the Gemini URL. `requestModel` flows through unchanged into the OpenAI response so clients see the id they asked for.

### 5.6 Local dispatch — same shape, different destination

```kotlin
when (routed) {
    is RoutedModel.LiteRtLm -> runLiteRtLmAndShape(routed.info, rawBody, requestModel)
    // already implemented in PR #1; just swap the model resolution to use the router
}
```

### 5.7 Retire `MOCK`

Remove `"MOCK"` from `ProxySetting.targetProvider`'s comment. A real "mock for tests" provider would only ever live in a debug build flavor — out of scope here.

---

## 6. Cross-cutting changes

### 6.1 Errors as a first-class concern *(applied partially in PR #1)*

PR #1 introduced `wrapLocalError` for the local engine path. Workstream B adds a single helper:

```kotlin
fun jsonError(e: RoutingError): String =
    JSONObject().put("error", JSONObject()
        .put("message", e.message)
        .put("type", e.type)
        .put("code", e.httpStatus)).toString()
```

Used everywhere the server returns non-200 to keep wire format consistent.

### 6.2 Logging

`GatewayLog` already captures `requestModel`, `status`, `responsePreview`. Add to `responsePreview` the routing decision when an error is returned:

> `"routing_error: model_not_found — Unknown model id: 'gpt-5'."`

This makes the dashboard immediately diagnose mis-routes.

### 6.3 Settings UI

`GatewayScreen` model-selection row should:

- Disable cloud-only ids when `targetProvider == LOCAL_VAL` and vice versa, instead of allowing the user to pick a combo that errors only at request time.
- Show a `(experimental)` badge when `experimental = true`.

### 6.4 Backwards compatibility for clients

Clients that send `"gpt-4o"` or other non-mapped ids will get a clear `400 model_not_found` after Workstream B. Mitigations:

- Add common OpenAI ids to `openAiAliases` on `gemini-2.5-flash` (e.g., `gpt-4o-mini`, `gpt-3.5-turbo`) so old integrations don't break.
- Document the change in `CHANGELOG.md`.

---

## 7. Test plan

### 7.1 Unit tests (Robolectric, no device)

`app/src/test/java/com/example/server/ModelRouterTest.kt` (Workstream B):

- Unknown id → `UnknownModel`.
- Known cloud id with `LOCAL_VAL` provider → `ProviderMismatch`.
- Known cloud id with empty key → `CloudKeyMissing`.
- Known LiteRT id with `weightsAvailable = false` → `WeightsMissing`.
- Alias resolution: `"gpt-4o-mini"` → `gemini-2.5-flash`.
- AICore id → `AiCoreUnsupported`.

`OpenAiToGeminiTranslatorTest.kt` (can land any time):

- `extractLocalEngineRequest` produces ordered text from system + user + assistant turns; trailing user is peeled out as `latestUserText`.
- `wrapLocalSuccess` produces strictly OpenAI-compliant JSON: required keys present, `usage` numeric, `choices[0].finish_reason ∈ {stop, length, content_filter}`, `system_fingerprint` matches `litertlm:<backend>:<n>ms`.
- `wrapLocalError` maps each `Result.Err.type` to the documented HTTP code.

### 7.2 Integration tests (Workstream B)

`ProxyServerManagerIntegrationTest.kt` (Robolectric + a fake `LiteRtLmEngine`):

- `GET /v1/models` returns only entries whose runtime is reachable given current settings.
- `POST /v1/chat/completions` with the model id of every advertised entry returns 200 (using a fake engine that echoes).
- Asking for a non-listed id returns 400 with the documented body.

### 7.3 On-device smoke test (manual) — required to validate PR #1

Without this passing once on real hardware, `LiteRtLmEngine` should remain behind a debug-only entry point. Recommended recipe:

1. Build and install the debug APK on a device with ≥ 4 GB RAM (Pixel 7+, Galaxy S22+, similar).
2. Open the app, select **`litert-community/Gemma3-1B-IT`** (smallest weights, ~580 MB), download.
3. Set provider to `LOCAL_VAL`, start the server.
4. From a laptop on the same Wi-Fi:
   ```bash
   curl http://<phone-ip>:8080/v1/chat/completions \
     -H 'content-type: application/json' \
     -d '{"model":"litert-community/Gemma3-1B-IT","messages":[{"role":"user","content":"What is 2+2?"}]}'
   ```
5. First call: expect 10–60 s while weights load. Subsequent calls: < 5 s.

Acceptance:

- The math prompt returns a number (not "I have successfully processed your prompt...").
- A "Translate to French: hello world" prompt contains "bonjour".
- `system_fingerprint` matches `litertlm:gpu:<n>ms` or `litertlm:cpu:<n>ms` with `<n>` non-trivial.

### 7.4 Regression — Roborazzi screenshots

`GreetingScreenshotTest.kt` style: add a screenshot for the model picker disabled-state when provider is `LOCAL_VAL` (Workstream B / §6.3).

---

## 8. Rollout / phasing

Two PRs, sequenced. **Order swapped from the original plan:**

**PR #1 — "Real LiteRT-LM inference" (Workstream A) — ✅ open**

- Adds `LiteRtLmEngine`, the Gradle dependency, the lifecycle wiring.
- Replaces `generateLiteRtLmResponse` (and removes the supporting simulator helpers) with the real engine path.
- Routing logic is **unchanged**; the gateway still has the substring-`pro` heuristic and silent `getModelById` fallback. That is intentional — landing the inference fix first means the response stops lying about what's executing, even if model selection remains crude.

**PR #2 — "Routing correctness" (Workstream B) — ⏳ queued**

- Adds `RuntimeType` enum, registry fields, `ModelRouter`, `findStrict`, deprecates `getModelById`.
- Rewrites `/v1/models` and dispatch to use the router.
- Removes the substring-`pro` mapping; cloud Gemini ids get promoted into the registry.

This split lets the highest-correctness fix (no fabricated metrics) ship without waiting for the routing refactor's broader review surface.

---

## 9. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| LiteRT-LM SDK API drift in 0.11.x | Low (verified against gallery main as of Nov 2026) | Med | Pin exact version `0.11.0` in `libs.versions.toml`; isolate SDK calls behind `LiteRtLmEngine`. |
| OOM loading a 2.5–4.9 GB model on low-RAM devices | High for E4B; low for 1B | High | Single-engine cache prevents double-loading. Recommend `Gemma3-1B-IT` for the smoke test. Future: add `minDeviceMemoryInGb` check before `ensureLoadedAndReset`. |
| GPU/NPU backend silently falling back to CPU | Medium | Low | `Result.Ok.backendUsed` is captured and surfaced in `system_fingerprint` so the ground truth is visible per response. |
| `Message.channels["thought"]` API surface differs in 0.11.0 from gallery main | Low | Low | If absent, the `<think>` block simply won't render; no crash. Can be guarded with `runCatching` if needed. |
| `Capabilities` not `AutoCloseable` in shipped 0.11.0 | Low | Low | Already wrapped in `try { ... } catch (_: Throwable) {}`; speculative-decoding probe is best-effort. |
| Maven Central artifact resolution from corporate networks | Medium | Med | Document mirror configuration in CONTRIBUTING.md if it bites. Sandbox build will fail without OPEN_INTERNET. |
| Existing clients break because `gpt-4o` no longer routes (Workstream B) | Medium | Med | Alias to `gemini-2.5-flash`. Document in CHANGELOG. |
| LiteRT-LM dependency increases APK size meaningfully | High | Low–Med | Acceptable; LiteRT-LM is the product's reason for existing. Confirm with `apkanalyzer` before/after. |
| Streaming (SSE) clients expect `text/event-stream` | N/A v1 | N/A | Out of scope; tracked in §11. |

---

## 10. Acceptance criteria

A reviewer can verify each of the following from the code and CI alone:

| # | Criterion | PR | Status |
|---|---|---|---|
| 1 | `OpenAiToGeminiTranslator` contains **no** function that fabricates token counts, latency numbers, or hardcoded canned responses. | #1 | ✅ Met (simulator deleted; `system_fingerprint` carries real `<latencyMs>ms`) |
| 2 | Every model id returned by `GET /v1/models` resolves to a successful route in `ModelRouter` under the current `targetProvider`. | #2 | ⏳ |
| 3 | Requesting an unknown model id returns HTTP 400 with `{"error":{"type":"model_not_found", ...}}`. | #2 | ⏳ |
| 4 | Requesting a known model id whose runtime doesn't match the configured provider returns HTTP 400 with `type = "provider_mismatch"`. | #2 | ⏳ |
| 5 | `LiteRtLmEngine.generate(...)` runs against a real loaded `.litertlm` file. | #1 | ✅ Code path is real; pending on-device smoke verification (§7.3) |
| 6 | `ModelRouterTest` passes with ≥ 6 cases covering each `RoutingError` subclass. | #2 | ⏳ |
| 7 | `getModelById` either: (a) is deleted, or (b) is `@Deprecated` and throws on unknown id — never silently returns `allowedModels[1]`. | #2 | ⏳ (still silent in `main` and in #1; intentional for #1's minimal scope) |
| 8 | The `"MOCK"` provider literal is gone from `ProxySetting.kt`. | #2 | ⏳ |
| 9 | `README.md` claim *"No fake simulations or mock fallbacks are used"* is now true. | #1 | ✅ True after #1 merges (a dedicated README cleanup PR is still tracked in §11). |

---

## 11. Out-of-scope follow-ups

Tracked separately; *not* in this plan's PRs:

- Streaming (SSE) responses for `/v1/chat/completions` with `stream: true` (would use `Conversation.sendMessageAsync` + `MessageCallback` per gallery's pattern).
- Function calling / tool use translation (gallery uses LiteRT-LM `ToolProvider`).
- Multi-turn KV cache reuse across requests in the LiteRT engine (currently every request rebuilds the conversation).
- AICore runtime implementation.
- Replacing `fallbackToDestructiveMigration()` in `AppDatabase` with real migrations.
- README correctness pass (secrets-plugin path, `local.properties` references, scoped-storage caveat, simulator → real LiteRT).
- Rationalizing the namespace `com.example` vs applicationId `com.aistudio.aiproxygateway.jxrqtm`.
- Per-model independent provider selection (so a LiteRT model and a cloud model can both be reachable from the same gateway without flipping `targetProvider`).
- Image / audio modality input plumbing in `extractLocalEngineRequest` (`gemma-4-*` and `gemma-3n-*` advertise these but v1 ignores non-text parts).
- NPU backend re-enablement once `nativeLibraryDir` plumbing is verified across vendor plug-ins.
- Removing the unused local `EngineConfig` data class in `ModelsRegistry.kt` (vestigial; left in PR #1 to keep the diff minimal).

---

## 12. Appendix A — File-by-file diff summary

| File | PR #1 (real engine) | PR #2 (routing) |
|---|---|---|
| `gradle/libs.versions.toml` | + `litertlm = "0.11.0"`, + library entry | — |
| `app/build.gradle.kts` | + `implementation(libs.litertlm)` | — |
| `app/src/main/java/com/example/data/ModelsRegistry.kt` | — | + `RuntimeType` enum, +cloud Gemini entries, +`findStrict`, deprecate `getModelById`, +`cloudUpstreamId`/`openAiAliases`/`experimental`/`preferredBackend` |
| `app/src/main/java/com/example/data/ProxySetting.kt` | — | Comment cleanup (drop `"MOCK"`) |
| `app/src/main/java/com/example/server/ModelRouter.kt` | — | **new** |
| `app/src/main/java/com/example/server/HttpErrors.kt` | — | **new** |
| `app/src/main/java/com/example/server/ProxyServerManager.kt` | LOCAL_VAL branch routes through `LiteRtLmEngine`; `stopServer()` closes engine | Replace `/v1/models` block, replace dispatch to use `ModelRouter`, remove substring-`pro` mapping |
| `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt` | Delete simulator (`solveSimplePrompt` + `generateSimulatedResponse` + `generateLiteRtLmResponse`); add `extractLocalEngineRequest` / `wrapLocalSuccess` / `wrapLocalError` | — |
| `app/src/main/java/com/example/inference/LiteRtLmEngine.kt` | **new** (singleton mirroring gallery's `LlmChatModelHelper`) | — |
| `app/src/main/java/com/example/ui/GatewayScreen.kt` | — | Disable incompatible models in picker; show experimental badge |
| `app/src/main/java/com/example/MainActivity.kt` or DI host | — | Pass router via constructor |
| `app/src/test/java/com/example/server/ModelRouterTest.kt` | — | **new** |
| `app/src/test/java/com/example/server/OpenAiToGeminiTranslatorTest.kt` | + tests for `extractLocalEngineRequest`, `wrapLocalSuccess` | + tests for error mapping |
| `CHANGELOG.md` | Document real LiteRT enablement and conservative-NPU choice | Document model-id breaking changes + alias list |
| `plan.md` | Plan recorded (this document) | Update Status table to mark Workstream B done |

---

## 13. Appendix B — Reference: model-id mapping table

After PR #2 lands, this table is the **single source of truth**. `ModelRouter` enforces it; `/v1/models` reflects it; the UI reads it.

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

## 14. Appendix C — Verified LiteRT-LM Kotlin API surface (from gallery)

Verified against `google-ai-edge/gallery@main` — files `Android/src/app/src/main/java/com/google/ai/edge/gallery/{runtime/LlmModelHelper.kt, ui/llmchat/LlmChatModelHelper.kt, customtasks/tinygarden/TinyGardenViewModel.kt}` — and used as the basis for `LiteRtLmEngine`.

### Maven coordinate

```
com.google.ai.edge.litertlm:litertlm-android:0.11.0
```

### Imports (every type used in our wrapper)

```kotlin
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
```

### Backends

```kotlin
Backend.CPU()
Backend.GPU()
Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
```

### Engine lifecycle

```kotlin
val engine = Engine(EngineConfig(
    modelPath = file.absolutePath,
    backend = backend,
    visionBackend = null,        // text-only → null
    audioBackend = null,         // text-only → null
    maxNumTokens = 1024,
    cacheDir = null              // null unless model is in /data/local/tmp
))
engine.initialize()              // explicit; not done by constructor
// ... use ...
engine.close()                   // releases native memory
```

### Capability probe (used to gate speculative decoding)

```kotlin
var supportsSpeculative = false
try {
    Capabilities(file.absolutePath).use {       // AutoCloseable
        supportsSpeculative = it.hasSpeculativeDecodingSupport()
    }
} catch (_: Throwable) { /* assume not supported */ }
```

### Experimental flags (toggle around Engine construction)

```kotlin
@OptIn(ExperimentalApi::class)
ExperimentalFlags.enableSpeculativeDecoding = supportsSpeculative
val engine = Engine(engineConfig).apply { initialize() }
ExperimentalFlags.enableSpeculativeDecoding = false   // reset
```

### Conversation creation

```kotlin
val sampler = if (backendName == "npu" || backendName == "tpu") {
    null   // SDK requires null for NPU/TPU
} else {
    SamplerConfig(
        topK = 64,                  // Int
        topP = 0.95,                // Double, NOT Float
        temperature = 1.0           // Double, NOT Float
    )
}

val conversation = engine.createConversation(ConversationConfig(
    samplerConfig = sampler,
    systemInstruction = systemContents,         // Contents? — null OK
    tools = emptyList(),                        // List<ToolProvider>
    initialMessages = history.map { turn ->
        when (turn.role) {
            HistoryRole.USER -> Message.user(turn.text)
            HistoryRole.ASSISTANT -> Message.model(turn.text)
        }
    }
))
```

### Inference — synchronous

```kotlin
val reply: Message = conversation.sendMessage(
    Contents.of(listOf(Content.Text(userText)))
)
val replyText: String = reply.toString()
val thinkingText: String? = reply.channels["thought"]   // for thinking models
```

### Inference — streaming (NOT used in PR #1; future SSE work)

```kotlin
conversation.sendMessageAsync(
    Contents.of(listOf(Content.Text(userText))),
    object : MessageCallback {
        override fun onMessage(message: Message) { /* partial */ }
        override fun onDone() { /* completion */ }
        override fun onError(t: Throwable) { /* error or CancellationException */ }
    },
    extraContext = emptyMap()
)
```

### Cancellation

```kotlin
conversation.cancelProcess()   // safe from any thread
```

### Cleanup order

```kotlin
try { conversation.close() } catch (_: Throwable) {}
try { engine.close() } catch (_: Throwable) {}
```

### Multimodal content (out of scope for v1, but documented)

```kotlin
Contents.of(listOf(
    Content.ImageBytes(byteArr),    // image first
    Content.AudioBytes(byteArr),    // audio next
    Content.Text(prompt)            // text last
))
// AND set EngineConfig.visionBackend / audioBackend to non-null backends.
```

---

*End of plan.*
