package com.example.server

import org.junit.Assert.*
import org.junit.Test

class ModelRouterTest {

    @Test
    fun testResolveCloudModelSuccess() {
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
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
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            weightsAvailable = { false },
            hasCloudKey = { false }
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RoutingError.CloudKeyMissing)
    }

    @Test
    fun testResolveLiteRtLmSuccess() {
        val result = ModelRouter.resolve(
            requestedId = "litert-community/gemma-4-E2B-it-litert-lm",
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
        val result = ModelRouter.resolve(
            requestedId = "litert-community/gemma-4-E2B-it-litert-lm",
            weightsAvailable = { false },
            hasCloudKey = { false }
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RoutingError.WeightsMissing)
    }

    @Test
    fun testUnknownModel() {
        val result = ModelRouter.resolve(
            requestedId = "some-unknown-model",
            weightsAvailable = { true },
            hasCloudKey = { true }
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is RoutingError.UnknownModel)
    }

    @Test
    fun testBothCloudAndLiteRtResolveInSameSession() {
        // Resolve a cloud model first
        val cloudResult = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            weightsAvailable = { true },
            hasCloudKey = { true }
        )
        assertTrue(cloudResult.isSuccess)
        assertTrue(cloudResult.getOrNull() is RoutedModel.Cloud)

        // Resolve a local model back-to-back in the same session without provider switches
        val localResult = ModelRouter.resolve(
            requestedId = "litert-community/gemma-4-E2B-it-litert-lm",
            weightsAvailable = { true },
            hasCloudKey = { true }
        )
        assertTrue(localResult.isSuccess)
        assertTrue(localResult.getOrNull() is RoutedModel.LiteRtLm)
    }
}
