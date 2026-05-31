# Android AI Proxy Gateway: Architectural Blueprint & Target Design

**Author:** AI Coding Agent  
**Date:** May 31, 2026  
**Status:** Approved & Committed

---

## 1. Executive Summary
The Android AI Proxy Gateway (*Juaravibecoding*) acts as a bridge between heavy desktop toolings (e.g. IDEs like Cursor, agent frameworks) and mobile-centric intelligence by exposing an OpenAI-compatible HTTP server directly on an Android device's local network.

This document details:
1. **As-Is State vs. To-Be Target Design**
2. **Current Reality & Intentional Simulation Boundaries**
3. **P0 Quick-Wins & Architecture Updates**
4. **Phased Integration Roadmap (M1 → M4)**

---

## 2. As-Is vs. To-Be Architecture

### AS-IS (Current Implementation)
* **Cloud Gemini Proxy**: 100% genuine and operational. Intercepts incoming requests on `/v1/chat/completions` and redirects them dynamically to the Google AI Studio Cloud endpoints (`generativelanguage.googleapis.com`) using `BuildConfig.GEMINI_API_KEY` or custom-provided credentials.
* **On-Device Inference**: Currently simulated. Classes like `LiteRtLmEngine` and `AiCoreEngine` return mocked successful generation results under the hood to preserve system resources and prevent heavy dependencies bloat.
* **Model Download States**: Weight downloads and file downloading UI in `ModelDownloadManager` operate over simulated time triggers without pulling actual multi-gigabyte `.litertlm` binary blobs.

### TO-BE (Target Phased Architecture)
A standardized interface pattern that will allow hot-swapping simulated backends with real LiteRT LM / AICore SDK calls as the platform library support matures.

```
                  ┌───────────────────────────────┐
                  │      Server Socket Input      │
                  └───────────────┬───────────────┘
                                  ▼
                  ┌───────────────────────────────┐
                  │       ModelRouter Engine      │
                  └───────────────┬───────────────┘
                                  ▼
                     [ InferenceEngine Strategy ]
                                  ├──────────────────────────────┐
                                  ▼                              ▼
                      ┌───────────────────────┐      ┌───────────────────────┐
                      │  GeminiCloudClient    │      │    LocalLiteRtEngine  │
                      │  (Direct HTTP REST)   │      │ (LiteRT Task Wrapper) │
                      └───────────────────────┘      └───────────────────────┘
```

---

## 3. P0 Quick-Wins Implemented

To transition the proxy from a baseline playground to a resilient, high-craftsmanship developer utility, the following quick-wins have been integrated:

### 1. Registry-Driven `/v1/models`
* Handshakes are routed directly to return list structures containing registered model metadata, separating **Local Edge** (simulated LiteRT/AICore) and **Google Cloud** options seamlessly.

### 2. Guarding Multi-Byte Charsets (UTF-8 Body Fix)
* Restructured byte buffer reading to dynamically aggregate content and write Content-Length headers using `payload.toByteArray(Charsets.UTF_8).size`. This guarantees that non-ASCII Unicode strings do not cause response truncation or socket-corrupted framing.

### 3. Bulletproof CORS Handshake
* Added standard `OPTIONS` interceptors returning `204 No Content` alongside relaxed origin configurations:
  * `Access-Control-Allow-Origin: *`
  * `Access-Control-Allow-Methods: GET, POST, OPTIONS`
  * `Access-Control-Allow-Headers: Content-Type, Authorization, X-Gemini-API-Key, X-Gemini-Key, *`

### 4. Spec-Compliant Stream Transmission (`alt=sse`)
* Built streaming pipelines utilizing correct SSE (`text/event-stream; charset=UTF-8`) encoding, chunk-based formatting, and standard `data: [DONE]\n\n` finalization structures.

### 5. Resilient Cloud Passthrough
* Allowed any model identifier starting with `gemini-` or containing `google/` to bypass localized constraints and map directly to corresponding upstream Gemini APIs.

### 6. Authentic Gateway Auth Tokens
* Ensured local gateway traffic is locked down behind Bearer authentication keys (`gateway_xxxx`), guarding against third-party network tampering in multi-tenant local environments.

---

## 4. Phase-by-Phase Roadmap

### Milestone 1 (M1): Baseline Correctness & Reliable Bridge
* **Goal**: Perfecting the OpenAI-to-Gemini bridge.
* **Scope**: Clean up UTF-8, lock down authentic tokens, and map `gemini-3.5-flash` or `gemini-3.1-pro-preview` as production-grade fallback pathways.
* **Dependencies**: Zero new packages. Rely entirely on stable `OkHttp` and core Room repositories.

### Milestone 2 (M2): Background Resilience & WorkManager Downloads
* **Goal**: Robust download persistence and foreground state safety.
* **Scope**: Wrap weights downloads in Android `WorkManager` tasks to prevent UI interruptions during network hops, and transition HTTP proxy sockets into a dedicated Android foreground service.

### Milestone 3 (M3): Android AICore Integration
* **Goal**: Local edge hardware compilation.
* **Scope**: Swap out `AiCoreEngine` mocks for actual Android AICore bindings to utilize Google's on-device Gemini Nano when running on supported devices.

### Milestone 4 (M4): LiteRT Custom Weights Proving Ground
* **Goal**: Custom edge weights executing natively in CPU/GPU.
* **Scope**: Integrate the Google LiteRT Task LLM libraries natively, enabling full execution of quantization-compressed `.litertlm` weights locally.
