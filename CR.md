# Change Request: Juaravibecoding — Android AI Proxy Gateway

| Field | Value |
| :--- | :--- |
| **CR Title** | Make the local AI gateway real, reliable, and secure |
| **Repository** | `nhasibuan/juaravibecoding` |
| **Status** | Draft — for review |
| **Author** | Kiro (analysis) |
| **Date** | 2026-05-31 |
| **Scope** | Inference engines, model downloads, persistence/migrations, security, docs |

> This document follows a four-part flow: **(1) AS-IS** (current state brainstorm), **(2) TO-BE** (proposed change/design), **(3) Verify** (evidence + validation plan), **(4) Review** (risks, trade-offs, recommendation).

---

## 0. Executive Summary

The project has matured substantially. It is a Kotlin/Compose/Room Android app that exposes an **OpenAI-compatible API** (`/v1/chat/completions`, `/v1/models`) from a phone, routing each request to either **Google Gemini cloud** or an **on-device engine**. Since the previous iteration, the team has already shipped most prior recommendations: a **foreground service**, **registry-driven routing**, **CORS/keep-alive**, a **gateway auth token**, **`x-goog-api-key` header usage**, **`alt=sse` cloud streaming**, and a **resumable downloader with checksums**.

The **cloud path is genuinely functional**. The remaining gap is the headline feature: **on-device inference is still simulated** (canned text + `delay()`), and a few **correctness/security defects** would bite in production — notably a **broken checksum table**, a **missing Room migration (v4→v5) masked by destructive fallback**, and **placeholder weight files that make models falsely report "ready."**

This CR catalogs the current state, proposes a prioritized change plan, provides verification evidence, and reviews risk.

---

## 1. AS-IS — Current System (Brainstorm)

### 1.1 Runtime architecture (as actually wired today)

```
MainActivity ─ Compose ─ GatewayScreen ◄─StateFlow─ GatewayViewModel
                                                        │ startService(port)
                                              GatewayForegroundService  (notification, START_STICKY, WifiLock)
                                                        │
                                              ProxyServerManager  (lifecycle + WakeLock/WifiLock)
                                                        │
                                              HttpGatewayServer  (ServerSocket; parse, CORS, keep-alive,
                                                        │          auth token, size/time limits)
                                          ModelRouter.getEngineForModel(resolvedId)
              ┌──────────────────────────────┼─────────────────────────────────┐
              ▼ LOCAL_LITERT                 ▼ LOCAL_AICORE                     ▼ CLOUD
   LiteRtLmEngine (SIMULATED)      AiCoreEngine (SIMULATED, w/ fallback)   GeminiCloudClient (REAL OkHttp + SSE)
                                                        │
                              GatewayRepository → Room (proxy_settings, gateway_logs, model_download_states)
   ModelDownloadManager (OkHttp, Range-resume, SHA-256, verified_manifest.json) — invoked from viewModelScope
```

### 1.2 What now works well (resolved since last review)

| Area | Current behavior | File |
| :--- | :--- | :--- |
| Foreground service | `START_STICKY`, notification + stop action, `specialUse/dataSync` type with fallbacks | `server/GatewayForegroundService.kt` |
| Routing by data | `ModelBackend` enum drives engine selection (no string-guessing) | `server/ModelRouter.kt`, `data/ModelsRegistry.kt` |
| Models endpoint | `/v1/models` generated from the registry (advertised == routable) | `server/OpenAiToGeminiTranslator.kt` |
| CORS / preflight | `OPTIONS` 204 + `Access-Control-*` on all responses | `server/HttpGatewayServer.kt` |
| HTTP body read | Reads **bytes** by `Content-Length` into `ByteArray`, decodes UTF-8 once | `server/HttpGatewayServer.kt` |
| Keep-alive | `Connection` parsed, request loop, cap of 100 | `server/HttpGatewayServer.kt` |
| Hardening | 15s socket read timeout (anti-slowloris), 50 MB payload cap | `server/HttpGatewayServer.kt` |
| Gateway auth | Bearer token validated against `gatewayAuthToken` | `server/HttpGatewayServer.kt`, `data/GatewayRepository.kt` |
| Upstream key | Sent via `x-goog-api-key` header (not URL query); client may override via `X-Gemini-API-Key` | `inference/GeminiCloudClient.kt` |
| Cloud streaming | `:streamGenerateContent?alt=sse` + spec-compliant SSE parse | `inference/GeminiCloudClient.kt` |
| Downloads | Resumable (`Range`), 64 KB buffer, SHA-256 + `verified_manifest.json`, pause/cancel | `inference/ModelDownloadManager.kt` |
| Persistence | `ModelDownloadState` table + DAO; `MIGRATION_3_4` for `tokensCount` | `data/*`, `data/AppDatabase.kt` |
| Engine contract | `InferenceEngine` interface + `InferenceResult` sealed types | `inference/InferenceEngine.kt` |
| Observability | Crash handler + file log (`gateway.log`); CSV export | `server/LogUtility.kt`, `MainActivity.kt`, `ui/GatewayViewModel.kt` |
| Docs honesty | README explicitly labels local inference as simulated | `README.md` |

### 1.3 Remaining gaps & defects (the substance of this CR)

**P1 — Critical**

1. **On-device inference is still simulated.** `LiteRtLmEngine.generate/generateStreaming` return canned strings after `delay(1200)`/`delay(40)`; `AiCoreEngine` returns a fixed "Gemini Nano" string. No LiteRT/MediaPipe runtime is even a dependency. `resolveBackend()` only returns a **label string** ("Local NPU (Hardware Accelerated)", etc.); the `try/catch` around "delegate init" wraps no real initialization, so the `catch` is dead code. **The core product promise is unmet.**

2. **Broken SHA-256 verification.** `ModelDownloadManager.modelSHA256Map` maps `litert-community/Gemma3-1B-IT` to `e3b0c442…b855` — the well-known hash of an **empty string** — and `…gemma-4-E2B…` to an obviously hand-typed value. A *real* download of either model will fail verification → the temp file is deleted → error. Models **without** map entries get **no** verification at all. Net: checksum logic is both counterproductive (for the 2 listed) and incomplete (for the rest).

3. **Missing Room migration 4→5 (masked by destructive fallback).** `@Database(version = 5)` but only `MIGRATION_3_4` exists. The v4→v5 delta (new `model_download_states` table + `gatewayAuthToken`/`preferredBackend` columns on `proxy_settings`) has no migration, so upgrades fall through to `fallbackToDestructiveMigration()` → **all user settings, logs, and download state are wiped** on app update.

**P2 — High**

4. **Placeholder weights fake "readiness."** `GatewayViewModel.refreshDownloadedModels()` writes two `.litertlm` **text** files on every launch if missing; `LiteRtLmEngine.isAvailable()` and `ModelDownloadManager.isModelDownloaded()` (manifest-absent fallback) treat "file exists & non-empty" as **downloaded & ready**. Result: models report installed/operational when they are not, and real downloads are unnecessary for the demo path.

5. **WorkManager dependency unused; downloads tied to the VM.** `androidx.work` is declared in `build.gradle.kts` but never used. `ModelDownloadManager.downloadModel(...)` is collected in `viewModelScope` (lifecycle of the ViewModel, not the foreground service), so a long download is not robust to process death (Range only helps on a manual restart).

6. **Security posture gaps.** `usesCleartextTraffic="true"` globally; the server binds the wildcard address (`InetSocketAddress(port)` → reachable on LAN, not just loopback); the auto-generated default `gatewayAuthToken` is only **8 hex chars** (~32 bits); `geminiApiKey` and the token are stored **plaintext** in Room (no `security-crypto`/Keystore); `BuildConfig.GEMINI_API_KEY` is baked from `.env` into the APK.

7. **Misleading AICore capability check.** `AiCoreEngine.isSupported()` returns `true` when the device model contains `sdk`/`droid`/`emulator`, so emulators and many dev devices take the **AICore mock** path ("Gemini Nano …") instead of the intended LiteRT/cloud fallback.

**P3 — Medium**

8. **Cloud preview model-ID durability.** Default cloud model `gemini-3.5-flash` is current/valid, but `gemini-3.1-pro-preview` is a **preview** alias; Google retires preview IDs on a schedule, so pinning the "Pro" default to a stable GA name avoids future 404s.

9. **System role dropped in cloud payload.** `GeminiCloudClient.buildGeminiPayload()` maps `assistant→model` and everything else (including `system`) to `user`, instead of using Gemini `systemInstruction`. Reduces multi-turn/system-prompt fidelity.

10. **Duplicate lock ownership.** Both `ProxyServerManager` and `GatewayForegroundService` acquire a `WifiLock` (and `ProxyServerManager` also a `WakeLock`); ownership is ambiguous and redundant.

11. **Token usage is estimated** (`text.length / 4`), not real — `usage` numbers are cosmetic.

12. **README architecture/data-dictionary is stale.** It still describes `ProxyServerManager` performing translation and omits `HttpGatewayServer`, `ModelRouter`, `GeminiCloudClient`, `GatewayForegroundService`, `ModelDownloadManager`; the data dictionary lacks `gatewayAuthToken`, `preferredBackend`, `tokensCount`, and the `model_download_states` table.

---

## 2. TO-BE — Proposed Change / Design

Design principle: **keep the (good) architecture; close the correctness/security gaps first, then make local inference real.** Cloud remains the always-available fallback.

### CR-1 (P1): Fix data-integrity & verification defects — *small, no new SDKs*
- **Add `MIGRATION_4_5`**: `CREATE TABLE IF NOT EXISTS model_download_states(...)` + `ALTER TABLE proxy_settings ADD COLUMN gatewayAuthToken TEXT NOT NULL DEFAULT ''` + `... preferredBackend TEXT NOT NULL DEFAULT 'AUTO'`. Remove (or keep only as a last resort) `fallbackToDestructiveMigration()` so missing migrations fail loudly in debug.
- **Fix checksums**: populate `modelSHA256Map` with the **real** published SHA-256 for each `.litertlm`, or gate verification on "entry present AND non-placeholder"; never compare against the empty-string hash. Treat missing checksum as "unverified" (warn), not "verified."
- **Strengthen default token**: generate a full 128-bit token (e.g., 32 hex chars) for `gatewayAuthToken`.

### CR-2 (P1/P2): Separate "demo mode" from "real readiness"
- Stop writing placeholder `.litertlm` files in `refreshDownloadedModels()` by default; put any demo seeding behind an explicit `BuildConfig.DEMO_MODE` flag.
- Make "available/downloaded" derive **only** from a `VERIFIED` manifest entry, not bare file presence. Surface a clear `SIMULATED` badge in the UI when demo mode is on.

### CR-3 (P2): Make downloads durable
- Move `downloadModel(...)` execution into a **`CoroutineWorker`** (the `androidx.work` dep is already present) or run it under the foreground service scope, persisting progress to `model_download_states`. This survives backgrounding/VM death and gives true resume.

### CR-4 (P2/P3): Harden security
- Add a **network-security-config** that allows cleartext only for `localhost`/loopback; drop the global `usesCleartextTraffic`.
- Bind the server to **loopback by default**, with an explicit opt-in toggle for LAN exposure.
- Store `geminiApiKey`/token via **`androidx.security:security-crypto`** (EncryptedSharedPreferences) or Keystore; remove the baked `.env` key from **release** builds.

### CR-5 (P3): Cloud correctness
- Pin the "Pro" default to a **stable GA** model id; keep arbitrary `gemini-*` pass-through (already supported).
- Use Gemini **`systemInstruction`** for `system` messages in `buildGeminiPayload()`.

### CR-6 (P1, the differentiator): Real on-device inference — *spike → integrate*
- Add **MediaPipe LLM Inference / Google AI Edge LiteRT** (`com.google.mediapipe:tasks-genai` or equivalent) as a dependency.
- Implement `LiteRtLmEngine` against the real runtime: load the downloaded `.litertlm`, honor `InferenceParams` (temp/topP/topK), and select an actual **NPU→GPU→CPU** delegate from `preferredBackend`/`enableNpuBackend`/`bypassGpu` with real fallback (turning today's label strings into real backend selection).
- Implement `AiCoreEngine` against the on-device GenerativeModel/AICore API for `*-aicore` entries, with **honest** capability detection (remove the emulator allow-list), falling back to LiteRT then cloud.
- Treat as a **device-validated spike first** (APK size + per-SoC behavior are the main unknowns).

### CR-7 (P3): Documentation
- Update README architecture diagram + data dictionary to match the current code (new components, columns, and the `model_download_states` table). Keep the honest "simulated" note until CR-6 lands.

### Sequencing
```
CR-1  ──►  CR-2  ──►  CR-3
  └────►  CR-4  ──►  CR-5  ──►  CR-7         (all low-risk, no heavy SDKs)
                                   └──►  CR-6 (heavy; device-gated spike, last)
```

---

## 3. Verify

### 3.1 Evidence (claim → source)

| # | Finding | Evidence in code |
| :-- | :--- | :--- |
| 1 | Local inference simulated | `LiteRtLmEngine.generate()` → `delay(1200)` + `getLogicalModelText()`; `AiCoreEngine.generate()` → fixed "Gemini Nano …" string; no ML runtime in `app/build.gradle.kts` |
| 1 | Backend selection is cosmetic | `LiteRtLmEngine.resolveBackend()` returns label strings; empty `try` bodies before `return` |
| 2 | Checksum table broken | `ModelDownloadManager.modelSHA256Map` value `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` = SHA-256("") |
| 3 | Migration gap | `AppDatabase`: `version = 5`, only `MIGRATION_3_4` registered, plus `fallbackToDestructiveMigration()` |
| 4 | Placeholder readiness | `GatewayViewModel.refreshDownloadedModels()` `writeText(...)`; `LiteRtLmEngine.isAvailable()` = `file.exists() && length>0` |
| 5 | WorkManager unused | `libs.androidx.work` in deps; downloads collected in `viewModelScope` (`triggerModelDownload`) |
| 6 | Security | `AndroidManifest.xml` `usesCleartextTraffic="true"`; `HttpGatewayServer` `InetSocketAddress(port)`; `GatewayRepository.getSettings()` token = `UUID…take(8)` |
| 7 | AICore over-reports support | `AiCoreEngine.isSupported()` matches `"sdk"/"droid"/"emulator"` |
| 8 | Preview model id | `ModelRouter.mapToRealCloudModelId()` → `gemini-3.1-pro-preview`; registry `cloudModels` |
| 9 | System role dropped | `GeminiCloudClient.buildGeminiPayload()` maps non-assistant → `user` |
| 12 | Docs stale | `README.md` §2 omits `HttpGatewayServer`/`ModelRouter`/`GeminiCloudClient`/FGS; data dict lacks new columns/table |

### 3.2 External verification (cloud model IDs)
A web check of current Google Gemini docs confirms `gemini-3.5-flash` is a current, valid model in this timeline, and that **preview-suffixed** IDs are deprecated on a schedule (a 3.1 flash-lite preview was retired ~May 2026). This **downgrades** the earlier worry that the cloud path uses fabricated IDs: the default flash id is fine; only the **`-preview` Pro alias** is durability-risky. *(Sources rephrased for licensing compliance: Google DeepMind Gemini Flash page; Google AI for Developers deprecations page.)*

### 3.3 Validation plan for the changes
- **Build:** `./gradlew assembleDebug` (NOTE: not yet run in this environment — recommended as the first verification step).
- **Migrations:** Room schema-upgrade instrumentation test from v4 fixture → v5 asserting data is preserved (no destructive wipe).
- **Downloads:** unit-test `ModelDownloadManager` against a local server returning known bytes; assert SHA-256 pass/fail and resume from `Range`.
- **HTTP/proxy:** `adb reverse tcp:8080 tcp:8080`, then exercise `/v1/models`, non-stream and `stream:true` chat, an `OPTIONS` preflight, and a bad/oversized body; confirm auth rejects a wrong Bearer token.
- **Cloud:** with a real key, confirm `gemini-3.5-flash` returns 200 and SSE chunks arrive incrementally.

### 3.4 Not verified / assumptions
- No Gradle build or device run was performed here; findings are from static reading + one web check.
- Exact published SHA-256 values and the available LiteRT/MediaPipe artifact coordinates must be confirmed before CR-1/CR-6 implementation.

---

## 4. Review

### 4.1 Overall assessment
Strong progress: the prior round of recommendations largely landed, and the README is now honest about simulation. The architecture is clean and the cloud proxy is production-credible. The risk has shifted from "design gaps" to **a few sharp correctness/security defects** plus the **still-unimplemented core feature** (real local inference).

### 4.2 Risks & trade-offs

| Area | Risk | Severity | Mitigation |
| :--- | :--- | :--- | :--- |
| Missing 4→5 migration | Silent user-data wipe on update | 🔴 High | CR-1 first; add migration + instrumentation test; drop destructive fallback in debug |
| Broken checksums | Real downloads of 2 models always fail; others unverified | 🔴 High | CR-1: real hashes or "unverified" semantics |
| Local inference simulated | Headline feature absent; big SDK/size/device unknowns | 🔴 High | CR-6 as a device-gated spike; keep cloud fallback; don't advertise NPU until measured |
| LAN bind + weak default token | Quota theft / open relay on shared Wi-Fi | 🟠 Med | CR-4: loopback default + 128-bit token + opt-in LAN |
| Placeholder "ready" | Users think a model runs when it doesn't | 🟠 Med | CR-2: manifest-only readiness + SIMULATED badge |
| Preview cloud id | Future 404 when alias retired | 🟡 Low | CR-5: pin to GA id |
| Doc drift | Onboarding confusion | 🟡 Low | CR-7 |

### 4.3 Recommendation
Proceed in this order: **CR-1 → CR-2 → CR-4 → CR-5 → CR-7 → CR-3 → CR-6.** CR-1 through CR-5/CR-7 are low-risk, need **no heavy SDKs**, and convert the app into a *correct, safe, honest* cloud gateway with a clean simulation mode. CR-6 (real on-device inference) is the true differentiator but carries the most uncertainty — schedule it as a separate, device-validated spike once the foundation is solid.

### 4.4 Open questions
1. **Is on-device inference the actual product goal**, or is the Gemini cloud proxy the real deliverable? (Decides whether CR-6 is core or optional.)
2. **Target devices** for local inference (Pixel 8/9, Galaxy S24/S25, …)? Needed before CR-6.
3. Should LAN exposure be **off by default** (loopback-only) with an explicit opt-in?
4. Do you want me to **start implementing CR-1** (migration + checksum/token fixes) on a branch and open a PR?
