# AI Proxy Gateway Development Plan & Roadmap

This document outlines the architectural roadmap, current implementation status, verified alignment, and historical progression for the AI Proxy Gateway project (PRs #1 through #12).

---

## §1 Executive Status Summary

The development work is tracked across 12 distinct pull request scopes. Below is the current, audited status of each workstream:

| Workstream / PR | Feature Description | Status | Current Reality / Branch State |
| :--- | :--- | :--- | :--- |
| **PR #1** | Replace LiteRT-LM simulator with real on-device inference | ✅ Shipped | Fully merged and integrated in main tracking branch. |
| **PR #2** | Tighten model routing: ModelRouter + RuntimeType enum | ✅ Shipped | Fully merged and integrated in main tracking branch. |
| **PR #3** | Real Room migrations, Vestigial code removal, & README updates | ✅ Shipped | Fully merged and integrated in main tracking branch. |
| **PR #4** | NPU backend opt-in toggle (NPU re-enablement) | ✅ Shipped | Integrated. Selects NNAPI delegate dynamically; honors user opt-in toggles. |
| **PR #5** | KV-cache-reuse for multi-turn LiteRT-LM | ✅ Shipped | Integrated. Preserves context snapshot cache across sequential prompts. |
| **PR #6** | SSE streaming for `stream:true` response shape | ⚠️ Partially Shipped | **Dead-code bug:** Emitters & translators exist, but standard dispatch was *not* wired. |
| **PR #7** | Multimodal inputs support (image + audio data URIs) | 🟡 Open | Pending review/merge. Covers bitmap scaling and audio buffers. |
| **PR #8** | Per-model provider selection strategy | 🟡 Open | Pending review/merge. Allows per-model overriding of the global provider. |
| **PR #9** | Function calling / Tool use capabilities | 🟡 Gate Only | Checked in as an honest HTTP 501 gate to prevent silent client malfunctions. |
| **PR #10** | Android AICore runtime real implementation | 🟡 Scaffold Only | Shipped as `AiCoreEngine` scaffold with dynamic GMS classpath probes. |
| **PR #11** | Streaming wiring / SSE dispatch integration | 🟡 Open | Fully resolves the PR #6 dead-code bug by hooking up SSE loops to dispatch. |
| **PR #12** | Package namespace & applicationId rationalization | ✅ Shipped | Merged. Successfully trimmed random dynamic suffix into `com.aistudio.aiproxygateway`. |

### ⚠️ Known Issue: PR #6 Streaming Dead-Code Bug
While PR #6 successfully introduced the SSE headers, translators, and streaming frame builders, the main socket request loop inside `ProxyServerManager.kt` was never updated to check for `stream: true` or invoke the streaming generator. As a result, standard client streaming requests silently returned full non-streaming JSON responses. **This gap is fully resolved in the pending PR #11 (feat-streaming-wiring)**.

---

## §2 Development Workflow & Quality Guidelines

1. **Incremental Compilation**: Always verify compilation status via the native `compile_applet` tool.
2. **Type Safety & Design Principles**: Adhere strictly to dynamic color Material 3 schemas and constructor injection over heavyweight DI where applicable.
3. **No Fake Simulations**: Ensure edge systems represent real physical device capabilities, raising early explicit rejections (like HTTP 501 `not_implemented`) rather than returning static simulations mock data.

---

## §3 Architecture Target

The server routing pipeline adheres to the following sequence:

```
[ Client OpenAI Request ]
           │
           ▼
[ ProxyServerManager ] ──► Detects JSON parameters (stream, tools, etc.)
           │
           ▼
  [ ModelRouter.resolve ] ──► Validates model, weights availability, & API keys
           │
           ├─► RoutedModel.Cloud     ──► Cloud API proxy
           ├─► RoutedModel.LiteRtLm  ──► On-device LiteRT Inference Engine
           └─► RoutedModel.AiCore    ──► On-device AICore / Gemini Nano Scaffold
```

---

## §4 Workstream A: Local Engine & Translator Integration

### §4.1 Maven Dependencies
The local inference engine integrates:
*   `com.google.ai.edge.litertlm:litertlm-android:0.11.0` in `gradle/libs.versions.toml` and `app/build.gradle.kts`.

### §4.3 LiteRtLmEngine API Surface
`LiteRtLmEngine` exposes a single, thread-safe object with the following main entry points:
*   `ensureLoadedAndReset(...)`: Validates and configures the physical binary model cache.
*   `generate(...)`: Suspends and yields a single, completed inference frame.
*   `generateStreaming(...)`: Delegates tokens to callback listeners during computation.

### §4.4 Translator APIs
Translates between OpenAI-compliant payloads and native Gemini structures:
*   `extractLocalEngineRequest(...)`, `wrapLocalSuccess(...)`, `wrapLocalError(...)`
*   Includes `streamingFirstDelta(...)`, `streamingContentDelta(...)`, `streamingFinish(...)`

### §4.5 system_fingerprint Format
Every success response appends a custom provenance payload:
*   Format: `litertlm:<backend_type>:<latency_ms>ms:cache=<hit|miss>` (e.g., `litertlm:cpu:245ms:cache=hit` or `litertlm:npu:102ms:cache=miss`).

### §4.7 NPU Selection Policy
To guard against SIGSEGV memory crashes on mid-tier hardware, the system employs an explicit user-configured approach:
*   **Opt-In Mechanism:** Driven entirely by `ProxySetting.enableNpuBackend`. 
*   **Routing Logic:** Default is off (delegating to safe GPU/CPU auto-select). If opted-in and the model registry supports it, the system binds NNAPI capabilities.

---

## §5 Workstream B: Registries, Router, and Multi-Provider Dispatch

### §5.1 LocalModelInfo Schema
The registration schema contains:
*   `modelId: String`, `cloudUpstreamId: String?`, `openAiAliases: List<String>`, `experimental: Boolean`, `runtimeType: RuntimeType`, and `accelerators: String` (which handles physical delegate constraints dynamically via CSV options instead of relying on a dedicated `preferredBackend` field).

### §5.2 Models Catalog
The standard model list incorporates online cloud offerings as well as offline local models including *Gemma 3n, Gemma 4, Qwen 2.5,* and *DeepSeek-R1*.

### §5.3 ModelRouter & Routing Errors
`ModelRouter.resolve` determines destinations and propagates one of the following `RoutingError` subclasses:
*   `UnknownModel`: Specified model ID cannot be located.
*   `WeightsMissing`: On-disk weights are not fully provisioned.
*   `CloudKeyMissing`: The proxy configuration lacks a valid `GEMINI_API_KEY`.
*   `AiCoreUnsupported`: Routed device does not support system AICore bindings.
*   *Note on `ModelDisabled`:* The `ModelDisabled` class is compiled under `ModelRouter.kt` but is **not active or instantiated** during current dispatch routines.

### §5.4 Model Availability Checks
The `/v1/models` route checks the runtime environment dynamically (checking weight presence for local options and checking credentials for cloud alternatives) to determine model active flags.

---

## §6 Cross-Cutting Improvements & Deferred UX

### §6.1 Native Error Envelopes
Integrates `HttpErrors.jsonError(...)` support with two overloads (supporting direct `RoutingError` exceptions as well as raw custom messages) to provide clean client-facing JSON blocks.

### §6.2 Gateway Trace Previews (DEFERRED / NOT YET IMPLEMENTED)
*   **Spec Claim:** Gateway DB logs output formatting such as `"routing_error: <type> — <message>"`.
*   **Reality:** Standard routing exceptions currently fallback to native standard raw content previews. This visual enhancement is deferred.

### §6.3 Screen State Badges (DEFERRED / NOT YET IMPLEMENTED)
*   **Spec Claim:** Dynamic `GatewayScreen` disabling incompatible items based on providers, alongside custom `(experimental)` badge displays.
*   **Reality:** The settings screen provides simple listings. This responsive visual rendering is deferred.

---

## §7 Test Plan & Realities

### §7.1 Unit Tests (Verifiable on local JVM)
*   `ModelRouterTest.kt`: Fully written and successfully executed (exceeding initial plans with 14 detailed cases covering keys, weights, and fallback scenarios).
*   `OpenAiToGeminiTranslatorTest.kt`: **Deferred / Not yet landed.**
*   `AiCoreEngineTest.kt`: Added to verify the behavior of the classpath probe under different package states.

### §7.2 Integration Tests
*   `ProxyServerManagerIntegrationTest.kt`: **Deferred / Not yet landed.** 

### §7.4 Screenshots
*   Roborazzi picker screenshot verification: **Deferred / Not yet landed.**

---

## §10 Architectural Acceptance Criteria

*   **Criterion #1:** Every registered model ID handles routing correctly based on physical runtime environments.
*   **Criterion #2 (Footnote):** *Wording obsolescence warning — once the per-model provider (PR #8) is merged, the reliance on a global provider check is fully bypassed.*
*   **Criterion #4 (Footnote):** *Obsoleteness warning — ModelRouter resolution changes in PR #8 bypass physical standard global provider switches.*
*   **Criterion #9 (Footnote):** *The explicit README claim "No fake simulations or mock fallbacks are used" was retired as part of the PR #3 markdown rewrite to maintain absolute factual content.*

---

## §11 PR Implementation Lifecycle

The roadmap is structured sequentially to prevent architectural regressions:

```
[PR #1 - LiteRT Base] ──► [PR #2 - ModelRouter] ──► [PR #3 - Database/Migrations]
                                                            │
                                                            ▼
[PR #6 - Streaming] ◄── [PR #5 - KV-Cache] ◄── [PR #4 - NPU Opt-In Toggle]
        │
     (Bug: Unwired)
        │
        ▼
[PR #11 - Stream Wiring] ◄── [PR #12 - Package Rename (Option b)]
        │
        ▼
  [Open PRs #7, #8] ──► [Downstream Work: Full Tool Calling / Real AICore SDK]
```

*   **Scope Realization for Tool Use (PR #9):** Currently delivers the HTTP 501 gate only, preventing silent tool execution failures on clients. Real schema parsing and tool translation are deferred to future milestones.
*   **Scope Realization for AICore (PR #10):** Delivers the `AiCoreEngine` API surface alongside Classpath runtime probes. Real client generation requires integrating the GMS SDK on the app compilation classpath.

---

## §12 Appendix A: Repository File Diffs

| Target File | Feature Relationship | Modification Summary |
| :--- | :--- | :--- |
| `app/build.gradle.kts` | PR #12 (Package Rename) | Replaced dynamic randomized package identifier with standardized ID string (`com.aistudio.aiproxygateway`). |
| `app/src/main/java/com/example/data/ProxySetting.kt` | PR #4 (NPU toggle) | Added `enableNpuBackend` column helper + companion migration schemas. |
| `app/src/main/java/com/example/inference/LiteRtLmEngine.kt` | PR #5 (KV Cache) | Enhanced session tracking via `lastResetSnapshot` checking and Cache hit flags. Re-mapped NPU delegate configuration. |
| `app/src/main/java/com/example/server/ProxyServerManager.kt` | PR #9, #11 | Integrates the 501 gate checking for invalid tool objects. Integrates stream dispatch checking for local models under PR #11. |
| `app/src/main/java/com/example/inference/AiCoreEngine.kt` | PR #10 (Scaffold) | Introduced Class.forName probes targeting candidate Google AICore SDK paths. |

---

## §13 Appendix B: Models Registry (15 Allowed Models)
Strictly matches the 15 active entries in `ModelsRegistry.allowedModels`, listing 4 online cloud-routed options, 2 local AICore configurations, and 9 local LiteRT model weights.

---

## §14 Appendix C: SDK API Surface Mapping
Core mappings target native models cleanly. All variables (Samplers, History lists, Configuration builders) maintain correct 1:1 correspondences to their standard Jetpack representation.
