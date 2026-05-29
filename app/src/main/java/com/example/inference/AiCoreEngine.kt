package com.example.inference

import android.content.Context
import android.util.Log
import com.example.data.LocalModelInfo

/**
 * Scaffold for Android AICore (Gemini Nano) inference.
 *
 * Status (plan.md §11 PR #10): **scaffold only**. This file lays out the
 * eventual API shape — singleton object, [Result] sealed class, suspend
 * `generate(...)` — and a runtime classpath probe for the AICore SDK, but
 * [generate] always returns [Result.Err] with `type = "not_implemented"`.
 *
 * Why a scaffold and not a working engine: the AICore SDK is not yet on
 * this app's classpath, and a real smoke test requires a Pixel 8 / 8 Pro
 * (or newer Tensor SoC) with the AICore system app installed and Gemini
 * Nano weights provisioned. The honest path is a follow-up PR that:
 *
 *  1. Adds the AICore SDK dependency to `app/build.gradle.kts`. The
 *     candidate class names probed by [probeAiCoreClass] below should
 *     guide which artifact to add — once the SDK is published, replace
 *     the [PROBE_CLASSES] list with the single canonical FQCN.
 *  2. Replaces the body of [generate] with a real call into the SDK.
 *     The signature here intentionally mirrors [LiteRtLmEngine.generate]
 *     to make the dispatcher's `RoutedModel.AiCore` branch a near-clone
 *     of its `RoutedModel.LiteRtLm` neighbor.
 *  3. Wires [isAvailable] into [com.example.server.ProxyServerManager]'s
 *     `/v1/models` availability check so AICore models are advertised
 *     only when the device can actually serve them. Today that block
 *     hard-codes `RuntimeType.AICORE -> false`; it can drop that branch
 *     once this engine is real.
 *
 * Until then, the dispatcher reaches the `RoutingError.AiCoreUnsupported`
 * 501 in `ModelRouter` for any AICore id, which is the honest current
 * state. This scaffold does *not* override that — the routing change is
 * intentionally deferred to the same follow-up that lands the real SDK
 * call, so the on-disk behavior cannot drift ahead of what's actually
 * verified on a device.
 *
 * What the SDK will need from the host app (verified against AICore docs):
 *  - `com.google.android.aicore` system package present on the device,
 *    enabled in Settings → System → On-device AI.
 *  - `<uses-feature android:name="android.software.aicore" android:required="false"/>`
 *    in `AndroidManifest.xml` (kept optional so non-AICore devices can
 *    still install the app).
 *  - On first run, the system may need to download model weights; the
 *    SDK exposes a "model status" API that returns `DOWNLOADING` /
 *    `READY` / `UNAVAILABLE`. The follow-up PR should expose this in
 *    [isAvailable] rather than treating "SDK on classpath" as proof of
 *    runtime readiness.
 */
object AiCoreEngine {

    private const val TAG = "AiCoreEngine"

    /**
     * Candidate fully-qualified class names that the runtime classpath might
     * expose if an AICore SDK is bundled with the app. Probed in order; the
     * first match wins.
     *
     * These are best-effort guesses based on Google's published AI client
     * libraries — the real package may differ when the SDK ships. The
     * follow-up PR that adds the dependency should:
     *  - Verify exactly which class lives at the entrypoint.
     *  - Replace this list with that one canonical FQCN (and remove this
     *    list-of-candidates pattern).
     */
    private val PROBE_CLASSES = listOf(
        // Generative AI client library (most likely host for an AICore client)
        "com.google.ai.client.generativeai.GenerativeModel",
        // AICore-specific entrypoint hypotheses — keep until verified
        "com.google.ai.aicore.GenerativeAIClient",
        "com.google.android.aicore.GenerativeAIClient",
        "androidx.aicore.client.GenerativeAIClient"
    )

    /**
     * Mirror of [LiteRtLmEngine.Result] so dispatcher code that handles both
     * engines can share its error-mapping logic. Kept intentionally smaller
     * than [LiteRtLmEngine.Result.Ok] (no thinkingText, no kvCacheReused) —
     * we'll grow it as the real implementation surfaces those signals.
     */
    sealed class Result {
        data class Ok(
            val text: String,
            val promptTokens: Int,
            val completionTokens: Int,
            val totalLatencyMs: Long
        ) : Result()

        data class Err(
            val type: String,
            val message: String,
            val cause: Throwable? = null
        ) : Result()
    }

    /**
     * Probes the runtime classpath for one of [PROBE_CLASSES]. Returns the
     * FQCN of the first class found, or null if none of the candidates is
     * present.
     *
     * This is a *runtime* probe, not a build-time check, so the app
     * compiles without taking a hard dependency on any AICore SDK. The
     * probe is cheap (one [Class.forName] per candidate, all expected to
     * miss in the current build) and idempotent — callers can invoke it
     * freely.
     */
    fun probeAiCoreClass(): String? {
        for (fqcn in PROBE_CLASSES) {
            try {
                Class.forName(fqcn)
                Log.d(TAG, "Found AICore-like class on classpath: $fqcn")
                return fqcn
            } catch (e: ClassNotFoundException) {
                // Expected on every environment that hasn't bundled an SDK.
            } catch (e: Throwable) {
                // Defensive: any other failure (LinkageError, SecurityException)
                // is treated as "not present" rather than crashing the app.
                Log.w(TAG, "Probe for $fqcn raised non-CNFE", e)
            }
        }
        Log.d(TAG, "No AICore SDK classes found on classpath (probed ${PROBE_CLASSES.size} candidates)")
        return null
    }

    /**
     * Whether this engine can plausibly serve a request right now.
     *
     * Currently returns `false` whenever [probeAiCoreClass] finds nothing on
     * the classpath, which is every build that hasn't added the SDK
     * dependency. Returns `true` when an SDK class is detectable —
     * but **callers must not infer that a real generate() will succeed**
     * from a `true` return. In particular:
     *
     *  - The system AICore service may be disabled.
     *  - Gemini Nano weights may not yet be provisioned on this device.
     *  - The user's device may lack a supported Tensor SoC.
     *
     * The follow-up PR that lands real inference should layer those
     * checks on top of this probe (or surface them as separate
     * `Result.Err` types from [generate]).
     */
    fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        return probeAiCoreClass() != null
    }

    /**
     * Stub. Always returns `Result.Err("not_implemented", ...)`. The
     * signature mirrors [LiteRtLmEngine.generate] (suspend, takes the
     * resolved [LocalModelInfo] plus a single user-text turn) so the
     * dispatcher can swap engines with minimal call-site change once the
     * real implementation lands.
     *
     * The real body, when written, should:
     *  - Translate `userText` (and any future history/system-instruction
     *    arguments) into the SDK's request type.
     *  - Configure `temperature` / `topK` / `topP` from
     *    [LiteRtLmEngine.GenerationParams]-equivalent values.
     *  - Map SDK exceptions to typed [Result.Err] codes.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun generate(
        context: Context,
        model: LocalModelInfo,
        userText: String
    ): Result {
        val sdk = probeAiCoreClass()
        return if (sdk == null) {
            Result.Err(
                type = "not_implemented",
                message = "AICore engine is a scaffold (plan.md §11 PR #10) and no AICore SDK class " +
                        "was found on the classpath. Add an AICore SDK dependency in " +
                        "app/build.gradle.kts, then replace the body of AiCoreEngine.generate() " +
                        "with a real implementation."
            )
        } else {
            Result.Err(
                type = "not_implemented",
                message = "AICore SDK class '$sdk' was detected on the classpath but " +
                        "AiCoreEngine.generate() has not yet been implemented against it. " +
                        "See plan.md §11 PR #10 for the integration plan."
            )
        }
    }
}
