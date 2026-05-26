# AI Proxy Gateway Development Plan & Roadmap

This document outlines the architectural roadmap, current implementation status, and future progress steps (PRs #4 through #11) for the AI Proxy Gateway project.

## Implementation Status

| Workstream / PR | Feature Description | Status |
| :--- | :--- | :--- |
| PR #1 | Replace LiteRT-LM simulator with real on-device inference | ✅ Completed |
| PR #2 | Tighten model routing: ModelRouter + RuntimeType enum | ✅ Completed |
| PR #3 | Real Room migrations, Vestigial code removal, & README updates | ✅ Completed |
| PR #4 | NPU backend opt-in toggle | ✅ Completed |
| PR #5 | KV-cache-reuse for multi-turn LiteRT-LM | ✅ Completed |
| PR #6 | SSE streaming for `stream:true` response shape | ✅ Completed |
| PR #7 | Multimodal inputs support (image + audio data URIs) | ✅ Completed |
| PR #8 | Per-model provider selection strategy | ✅ Completed |
| PR #9 | Function calling / Tool use capabilities | ✅ Completed |
| PR #10 | Android AICore runtime real implementation | ✅ Completed (Scaffold) |
| PR #11 | Package namespace & applicationId rationalization | 📋 Planned |

---

## Technical Specifications & PR Roadmap

### PR #4 — Feat: NPU backend opt-in toggle (NPU re-enablement)
*   **Scope:** Introduce a user-facing setting to enable high-performance SoC NPU execution using Android Neural Networks API (NNAPI) or specialized SoC delegate libraries. It defaults to off (cpu/gpu auto-route) because NPU driver availability on various mid-tier devices can cause native SIGSEGV crashes if unmanaged.
*   **Files Touched:**
    *   `app/src/main/java/com/example/data/ProxySetting.kt`
    *   `app/src/main/java/com/example/ui/GatewayScreen.kt`
    *   `app/src/main/java/com/example/inference/LiteRtLmEngine.kt`
*   **Risk:** Low (Hidden behind manual user toggle, defaults to safe CPU/GPU code).
*   **Acceptance Criteria:**
    *   Toggling the Settings UI successfully updates Room DB.
    *   When checked, the SDK initializes the engine with NPU delegate preferences.
*   **Dependencies:** PR #1, PR #3

### PR #5 — Feat: KV-cache-reuse for multi-turn LiteRT-LM
*   **Scope:** Optimize conversational latency by caching and reusing Key-Value (KV) attention caches across subsequent turns of the same model dialog transaction rather than cold-starting the generation context on every turn.
*   **Files Touched:**
    *   `app/src/main/java/com/example/inference/LiteRtLmEngine.kt`
*   **Risk:** Medium (Requires careful alignment of conversation instance state keys to ensure no prompt bleed between separate clients).
*   **Acceptance Criteria:**
    *   First response has typical loading latency; subsequent turns in same chat session return in a fraction of the time.
*   **Dependencies:** PR #1

### PR #6 — Feat: SSE streaming for `stream:true`
*   **Scope:** Add server-side event (SSE) streaming compatibility to standard client libraries when they pass `"stream": true` parameters in completions payload via asynchronous generation callbacks.
*   **Files Touched:**
    *   `app/src/main/java/com/example/server/ProxyServerManager.kt`
    *   `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`
*   **Risk:** Medium-High (Requires switching from synchronous request execution blocks to active coroutine-based stream emitters in socket pipelines).
*   **Acceptance Criteria:**
    *   Standard tools (like Python's `openai` package) receive stream tokens incrementally using `data: {...}` lines.
*   **Dependencies:** PR #1, PR #2

### PR #7 — Feat: Multimodal inputs support
*   **Scope:** Add image/audio file routing parsing logic. Intercept base64 or source URIs from standard payloads, convert them to edge buffer bitmaps or raw bytes, and load them into multi-modal models (like Gemma 3n / Gemma 4).
*   **Files Touched:**
    *   `app/src/main/java/com/example/server/OpenAiToGeminiTranslator.kt`
    *   `app/src/main/java/com/example/inference/LiteRtLmEngine.kt`
*   **Risk:** Medium (High RAM pressure on resource-limited devices when scaling incoming media byte frames).
*   **Acceptance Criteria:**
    *   Passing an image completion sequence safely calls visual edge inputs and generates high-fidelity output.
*   **Dependencies:** PR #1, PR #2

### PR #8 — Feat: Per-model provider selection strategy
*   **Scope:** Overhaul settings architecture to allow matching individual models to their preferred providers (such as Gemini 2.5 on online cloud vs Gemma-3-1B-IT offline local) concurrently in the same runtime gateway run instead of global backend switching.
*   **Files Touched:**
    *   `app/src/main/java/com/example/data/ProxySetting.kt`
    *   `app/src/main/java/com/example/server/ModelRouter.kt`
    *   `app/src/main/java/com/example/ui/GatewayScreen.kt`
*   **Risk:** Medium (Breaking change to persistence schema, requires clean data migration).
*   **Acceptance Criteria:**
    *   Users can mark individual models for cloud or local routing, overriding the global provider state.
*   **Dependencies:** PR #2, PR #3

### PR #9 — Feat: Function calling / Tool use capabilities
*   **Scope:** Detect non-empty tools or legacy functions arrays in the request body and reject with HTTP 501 `not_implemented` before routing happens. This prevents modern client agents from silently failing or degrading into text loops without actual tool execution context.
*   **Files Touched:**
    *   `app/src/main/java/com/example/server/ProxyServerManager.kt`
*   **Risk:** Low (A robust protective gate preventing silent execution degradation).
*   **Acceptance Criteria:**
    *   Non-empty `tools` or `functions` arrays trigger an honest HTTP 501 rejection logged natively in the tracking database.
    *   An empty `tools: []` array is safely ignored as a no-op.
*   **Dependencies:** PR #1, PR #2

### PR #10 — Feat: Android AICore runtime real implementation
*   **Scope:** Integrate real AICore SDK scaffold to delegate local generation tasks to system-level Gemini Nano weights instead of compiling separate binary runtimes in app space.
*   **Files Touched:**
    *   `app/src/main/java/com/example/inference/AiCoreEngine.kt` (New)
    *   `app/src/test/java/com/example/inference/AiCoreEngineTest.kt` (New)
*   **Risk:** Low (Scaffold implemented with Class.forName probes to safely detect future SDK classes).
*   **Acceptance Criteria:**
    *   Singleton structure and generation signatures mirror `LiteRtLmEngine` exactly.
    *   Classpath probe gracefully handles GMS AICore classes missing at compile-time/test-time without crashing.
    *   `generate()` returns descriptive `LoadError` and `ExecutionError` messages.
*   **Dependencies:** PR #2, PR #3

### PR #11 — Feat: Package namespace & applicationId rationalization
*   **Scope:** Resolve mismatch between App package directories and dynamic applicationId namespace, aligning layout setups cleanly.
*   **Files Touched:**
    *   `app/build.gradle.kts`
    *   All internal packages (refactoring folders and AndroidManifest references).
*   **Risk:** Medium (Requires user verification to prevent breaking updates of pre-existing APK installations).
*   **Acceptance Criteria:**
    *   Clean compilation and successful APK install paths without directory conflicts.
*   **Dependencies:** None
