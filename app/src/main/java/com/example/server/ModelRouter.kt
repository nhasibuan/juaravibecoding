package com.example.server

import com.example.data.LocalModelInfo
import com.example.data.ModelsRegistry
import com.example.data.RuntimeType

sealed class RoutedModel {
    data class Cloud(val info: LocalModelInfo) : RoutedModel()
    data class LiteRtLm(val info: LocalModelInfo) : RoutedModel()
    data class AiCore(val info: LocalModelInfo) : RoutedModel()
}

sealed class RoutingError(val httpStatus: Int, val type: String, val msg: String) : Exception(msg) {
    class UnknownModel(id: String) : RoutingError(400, "model_not_found", "Unknown model ID: '$id'.")
    class ModelDisabled(id: String, reason: String) : RoutingError(400, "model_disabled", "Model '$id' is disabled. Reason: $reason")
    class WeightsMissing(id: String, path: String) : RoutingError(400, "model_not_found", "Local weights for '$id' are missing at: $path. Please download them first through the gateway UI.")
    class CloudKeyMissing(id: String) : RoutingError(500, "gateway_setup_error", "Requested cloud model '$id' but GEMINI_API_KEY is not configured in the gateway.")
    class AiCoreUnsupported(id: String) : RoutingError(501, "not_implemented", "Android AICore support for '$id' is currently not implemented.")
}

object ModelRouter {
    fun resolve(
        requestedId: String,
        weightsAvailable: (LocalModelInfo) -> Boolean,
        hasCloudKey: () -> Boolean
    ): Result<RoutedModel> {
        val model = ModelsRegistry.findStrict(requestedId)
            ?: return Result.failure(RoutingError.UnknownModel(requestedId))

        when (model.runtimeType) {
            RuntimeType.CLOUD -> {
                if (!hasCloudKey()) {
                    return Result.failure(RoutingError.CloudKeyMissing(model.modelId))
                }
                return Result.success(RoutedModel.Cloud(model))
            }
            RuntimeType.LITERT_LM -> {
                if (!weightsAvailable(model)) {
                    val resolvedFile = model.targetFilePath
                    return Result.failure(RoutingError.WeightsMissing(model.modelId, resolvedFile))
                }
                return Result.success(RoutedModel.LiteRtLm(model))
            }
            RuntimeType.AICORE -> {
                return Result.failure(RoutingError.AiCoreUnsupported(model.modelId))
            }
        }
    }
}
