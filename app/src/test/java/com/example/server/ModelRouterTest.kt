package com.example.server

import com.example.data.LocalModelInfo
import com.example.data.ProxySetting
import org.junit.Assert.*
import org.junit.Test

class ModelRouterTest {

    @Test
    fun testResolveCloudModelSuccess() {
        val settings = ProxySetting(targetProvider = "CLOUD_GEMINI")
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            settings = settings,
            weightsAvailable = { false },
            hasCloudKey = { true }
        )
        assertTrue(result.isSuccess)
        val routed = result.getOrNull()
        assertTrue(routed is RoutedModel.Cloud)
        assertEquals("gemini-2.5-flash", (routed as RoutedModel.Cloud).info.modelId)
    }

    @Test
    fun testResolveCloudModelMissingKey() {
        val settings = ProxySetting(targetProvider = "CLOUD_GEMINI")
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            settings = settings,
            weightsAvailable = { false },
            hasCloudKey = { false }
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RoutingError.CloudKeyMissing)
    }

    @Test
    fun testResolveCloudModelProviderMismatch() {
        val settings = ProxySetting(targetProvider = "LOCAL_VAL")
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            settings = settings,
            weightsAvailable = { false },
            hasCloudKey = { true }
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RoutingError.ProviderMismatch)
    }

    @Test
    fun testResolveLiteRtLmSuccess() {
        val settings = ProxySetting(targetProvider = "LOCAL_VAL")
        val result = ModelRouter.resolve(
            requestedId = "litert-community/gemma-4-E2B-it-litert-lm",
            settings = settings,
            weightsAvailable = { true },
            hasCloudKey = { false }
        )
        assertTrue(result.isSuccess)
        val routed = result.getOrNull()
        assertTrue(routed is RoutedModel.LiteRtLm)
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", (routed as RoutedModel.LiteRtLm).info.modelId)
    }

    @Test
    fun testResolveLiteRtLmMissingWeights() {
        val settings = ProxySetting(targetProvider = "LOCAL_VAL")
        val result = ModelRouter.resolve(
            requestedId = "litert-community/gemma-4-E2B-it-litert-lm",
            settings = settings,
            weightsAvailable = { false },
            hasCloudKey = { false }
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RoutingError.WeightsMissing)
    }

    @Test
    fun testUnknownModel() {
        val settings = ProxySetting()
        val result = ModelRouter.resolve(
            requestedId = "some-unknown-model",
            settings = settings,
            weightsAvailable = { true },
            hasCloudKey = { true }
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RoutingError.UnknownModel)
    }
}
