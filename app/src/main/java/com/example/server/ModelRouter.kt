package com.example.server

import com.example.data.LocalModelInfo
import com.example.data.ModelsRegistry
import com.example.data.RuntimeType

/**
 * The destination a request resolved to. The dispatch site only needs to
 * pattern-match on this; all eligibility logic is already done by [ModelRouter].
 */
sealed class RoutedModel {
    abstract val info: LocalModelInfo

    data class Cloud(override val info: LocalModelInfo) : RoutedModel()
    data class LiteRtLm(override val info: LocalModelInfo) : RoutedModel()
    data class AiCore(override val info: LocalModelInfo) : RoutedModel()
}

/**
 * Closed set of routing failures, each with a documented HTTP status and
 * OpenAI-shaped `error.type`. Centralizing these lets [HttpErrors.jsonError]
 * render a uniform wire shape regardless of where the failure originated.
 *
 * As of plan.md §11 PR #8, [ProviderMismatch] is gone: the gateway no longer
 * has a global "current provider" that gates which models are dispatchable.
 * Each registered model decides its own runtime via its [RuntimeType], and
 * any registered id is reachable so long as its prerequisite resource (cloud
 * key or on-disk weights) is present.
 */
sealed class RoutingError(
    val httpStatus: Int,
    val type: String,
    val message: String
) {
    /** No registry entry matches the requested id (or any alias). */
    class UnknownModel(id: String) : RoutingError(
        httpStatus = 400,
        type = "model_not_found",
        message = "Unknown model id: '$id'. Call GET /v1/models to list available ids."
    )

    /**
     * LiteRT-LM weights are not present on the device. Returned as
     * `model_not_found` (not a 5xx) so OpenAI clients can surface a clean
     * error to end users without retrying.
     */
    class WeightsMissing(id: String, path: String) : RoutingError(
        httpStatus = 400,
        type = "model_not_found",
        message = "LiteRT-LM weights for '$id' are not on this device. " +
                "Expected at: $path"
    )

    /** Cloud Gemini key not configured but a CLOUD model was requested. */
    class CloudKeyMissing(id: String) : RoutingError(
        httpStatus = 500,
        type = "gateway_setup_error",
        message = "Cloud Gemini key not configured but model '$id' requires it. " +
                "Set GEMINI_API_KEY via app settings or .env."
    )

    /** AICore runtime is registered in the catalog but not yet implemented. */
    class AiCoreUnsupported(id: String) : RoutingError(
        httpStatus = 501,
        type = "not_implemented",
        message = "AICore runtime is not yet implemented. " +
                "Use a LiteRT-LM or Gemini cloud model instead."
    )
}

/**
 * Pure routing logic. No Android imports, no I/O, fully unit-testable.
 *
 * Per-model provider selection (plan.md §11 PR #8): the router no longer
 * consults a global `targetProvider`. The runtime to dispatch to is
 * determined entirely by the resolved [LocalModelInfo.runtimeType], so a
 * single gateway can serve cloud Gemini and on-device LiteRT-LM requests
 * concurrently — pick which by passing the corresponding `model` id.
 *
 * The dispatch site supplies two callbacks instead of doing the I/O directly:
 *  - [weightsAvailable] decides whether a LiteRT-LM model's `.litertlm` file
 *    exists on disk for the current Context.
 *  - [hasCloudKey] decides whether a Gemini API key is resolvable (settings
 *    column or BuildConfig).
 *
 * This keeps the router itself trivially testable — see `ModelRouterTest`.
 */
object ModelRouter {

    /**
     * Resolve a request's `model` field to a concrete [RoutedModel].
     * Returns either:
     *  - `Result.success(RoutedModel)` — caller dispatches directly.
     *  - `Result.failure(re)` where `re` is a [RoutingErrorException] wrapping
     *    a [RoutingError]. The dispatch site renders it via
     *    [HttpErrors.jsonError] and writes the matching HTTP status.
     *
     * Failure modes (closed set):
     *  - [RoutingError.UnknownModel]     — id not in registry
     *  - [RoutingError.CloudKeyMissing]  — CLOUD model + no Gemini key
     *  - [RoutingError.WeightsMissing]   — LITERT_LM model + no on-disk file
     *  - [RoutingError.AiCoreUnsupported] — AICORE model (always, until PR #10
     *    lands a real engine)
     */
    fun resolve(
        requestedId: String,
        weightsAvailable: (LocalModelInfo) -> Boolean,
        hasCloudKey: () -> Boolean
    ): Result<RoutedModel> {
        val info = ModelsRegistry.findStrict(requestedId)
            ?: return Result.failure(RoutingErrorException(RoutingError.UnknownModel(requestedId)))

        return when (info.runtimeType) {
            RuntimeType.CLOUD -> {
                if (!hasCloudKey()) {
                    Result.failure(
                        RoutingErrorException(RoutingError.CloudKeyMissing(info.modelId))
                    )
                } else {
                    Result.success(RoutedModel.Cloud(info))
                }
            }

            RuntimeType.LITERT_LM -> {
                if (!weightsAvailable(info)) {
                    Result.failure(
                        RoutingErrorException(
                            RoutingError.WeightsMissing(info.modelId, info.targetFilePath)
                        )
                    )
                } else {
                    Result.success(RoutedModel.LiteRtLm(info))
                }
            }

            RuntimeType.AICORE -> Result.failure(
                RoutingErrorException(RoutingError.AiCoreUnsupported(info.modelId))
            )
        }
    }
}

/**
 * Carries a [RoutingError] through the [Result] sad path. We use a
 * [Result]-of-[RoutedModel] return so callers can compose with `getOrElse`,
 * `fold`, etc., without try/catch. The error class itself isn't a Throwable
 * (it's a sealed data shape), so we wrap it for [Result].
 */
class RoutingErrorException(val error: RoutingError) :
    RuntimeException(error.message)
