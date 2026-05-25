package com.example.server

import com.example.data.LocalModelInfo
import com.example.data.ProxySetting
import com.example.data.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ModelRouter]. The router is pure logic — no Android, no I/O —
 * so plain JUnit is sufficient (no Robolectric needed).
 *
 * Coverage matches plan.md §7.1 (acceptance criterion #6 in §10):
 *   - Unknown id              -> UnknownModel
 *   - Cloud id, LOCAL_VAL     -> ProviderMismatch
 *   - Cloud id, no key        -> CloudKeyMissing
 *   - LiteRT id, no weights   -> WeightsMissing
 *   - Alias                   -> Cloud success against the aliased upstream
 *   - AICore id               -> AiCoreUnsupported
 *   - Happy paths (cloud + local)
 */
class ModelRouterTest {

    // ---- helpers -----------------------------------------------------------

    private fun cloudSettings(geminiKey: String = "fake-key") = ProxySetting(
        targetProvider = "CLOUD_GEMINI",
        geminiApiKey = geminiKey
    )

    private fun localSettings() = ProxySetting(
        targetProvider = "LOCAL_VAL"
    )

    /** All-yes weights callback — useful when the test isn't about file presence. */
    private val weightsAlways: (LocalModelInfo) -> Boolean = { true }

    /** No-weights callback — for the WeightsMissing case. */
    private val weightsNever: (LocalModelInfo) -> Boolean = { false }

    /** Helper to extract the [RoutingError] from a failed [Result]. */
    private fun errorOf(result: Result<RoutedModel>): RoutingError {
        val ex = result.exceptionOrNull()
        assertNotNull("expected failure, got success: ${result.getOrNull()}", ex)
        assertTrue(
            "expected RoutingErrorException, got ${ex!!::class.qualifiedName}",
            ex is RoutingErrorException
        )
        return (ex as RoutingErrorException).error
    }

    // ---- error cases -------------------------------------------------------

    @Test
    fun unknown_model_id_returns_UnknownModel() {
        val result = ModelRouter.resolve(
            requestedId = "totally-made-up-model",
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        val err = errorOf(result)
        assertTrue("expected UnknownModel, got ${err::class.simpleName}", err is RoutingError.UnknownModel)
        assertEquals(400, err.httpStatus)
        assertEquals("model_not_found", err.type)
        assertTrue(err.message.contains("totally-made-up-model"))
    }

    @Test
    fun empty_id_returns_UnknownModel() {
        val result = ModelRouter.resolve(
            requestedId = "",
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        assertTrue(errorOf(result) is RoutingError.UnknownModel)
    }

    @Test
    fun cloud_id_with_LOCAL_VAL_provider_returns_ProviderMismatch() {
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            settings = localSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        val err = errorOf(result)
        assertTrue(err is RoutingError.ProviderMismatch)
        assertEquals(400, err.httpStatus)
        assertEquals("provider_mismatch", err.type)
        assertTrue(err.message.contains("CLOUD"))
        assertTrue(err.message.contains("LOCAL_VAL"))
    }

    @Test
    fun litert_id_with_CLOUD_GEMINI_provider_returns_ProviderMismatch() {
        val result = ModelRouter.resolve(
            requestedId = "litert-community/Gemma3-1B-IT",
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        val err = errorOf(result)
        assertTrue(err is RoutingError.ProviderMismatch)
        assertTrue(err.message.contains("LITERT_LM"))
        assertTrue(err.message.contains("CLOUD_GEMINI"))
    }

    @Test
    fun cloud_id_with_no_key_returns_CloudKeyMissing() {
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            settings = cloudSettings(geminiKey = ""),
            weightsAvailable = weightsAlways,
            hasCloudKey = { false }
        )
        val err = errorOf(result)
        assertTrue(err is RoutingError.CloudKeyMissing)
        assertEquals(500, err.httpStatus)
        assertEquals("gateway_setup_error", err.type)
        assertTrue(err.message.contains("gemini-2.5-flash"))
    }

    @Test
    fun litert_id_with_missing_weights_returns_WeightsMissing() {
        val result = ModelRouter.resolve(
            requestedId = "litert-community/Gemma3-1B-IT",
            settings = localSettings(),
            weightsAvailable = weightsNever,
            hasCloudKey = { true }
        )
        val err = errorOf(result)
        assertTrue(err is RoutingError.WeightsMissing)
        assertEquals(400, err.httpStatus)
        assertEquals("model_not_found", err.type)
        assertTrue(err.message.contains("Gemma3-1B-IT"))
    }

    @Test
    fun aicore_id_returns_AiCoreUnsupported() {
        val result = ModelRouter.resolve(
            requestedId = "aicore-gemma-4-e2b",
            // Provider doesn't matter — AICore is unconditionally rejected for now.
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        val err = errorOf(result)
        assertTrue(err is RoutingError.AiCoreUnsupported)
        assertEquals(501, err.httpStatus)
        assertEquals("not_implemented", err.type)
    }

    // ---- happy paths -------------------------------------------------------

    @Test
    fun cloud_id_with_correct_provider_and_key_returns_Cloud() {
        val result = ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        assertNull("expected success", result.exceptionOrNull())
        val routed = result.getOrNull()
        assertNotNull(routed)
        assertTrue("expected RoutedModel.Cloud, got ${routed!!::class.simpleName}", routed is RoutedModel.Cloud)
        assertEquals(RuntimeType.CLOUD, routed.info.runtimeType)
        assertEquals("gemini-2.5-flash", routed.info.modelId)
        assertEquals("gemini-2.5-flash", routed.info.cloudUpstreamId)
    }

    @Test
    fun litert_id_with_correct_provider_and_weights_returns_LiteRtLm() {
        val result = ModelRouter.resolve(
            requestedId = "litert-community/Gemma3-1B-IT",
            settings = localSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { false }
        )
        assertNull("expected success", result.exceptionOrNull())
        val routed = result.getOrNull()
        assertNotNull(routed)
        assertTrue(routed is RoutedModel.LiteRtLm)
        assertEquals(RuntimeType.LITERT_LM, routed!!.info.runtimeType)
        assertEquals("litert-community/Gemma3-1B-IT", routed.info.modelId)
    }

    // ---- alias resolution --------------------------------------------------

    @Test
    fun openai_alias_gpt_4o_mini_resolves_to_gemini_2_5_flash() {
        val result = ModelRouter.resolve(
            requestedId = "gpt-4o-mini",
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        val routed = result.getOrNull()
        assertNotNull("alias should resolve, got error: ${result.exceptionOrNull()?.message}", routed)
        assertTrue(routed is RoutedModel.Cloud)
        // The resolved entry is the Flash registration — its modelId, not the alias.
        assertEquals("gemini-2.5-flash", routed!!.info.modelId)
        assertEquals("gemini-2.5-flash", routed.info.cloudUpstreamId)
        assertTrue(
            "expected gpt-4o-mini in openAiAliases",
            "gpt-4o-mini" in routed.info.openAiAliases
        )
    }

    @Test
    fun openai_alias_gpt_3_5_turbo_also_resolves_to_flash() {
        val result = ModelRouter.resolve(
            requestedId = "gpt-3.5-turbo",
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        val routed = result.getOrNull()
        assertNotNull(routed)
        assertEquals("gemini-2.5-flash", routed!!.info.modelId)
    }

    @Test
    fun unknown_alias_still_returns_UnknownModel() {
        // Make sure findStrict doesn't return the first entry as a fallback.
        val result = ModelRouter.resolve(
            requestedId = "gpt-9000",
            settings = cloudSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = { true }
        )
        assertTrue(errorOf(result) is RoutingError.UnknownModel)
    }

    // ---- callbacks fire only when needed -----------------------------------

    @Test
    fun weightsAvailable_is_not_called_for_cloud_routes() {
        var weightsCalled = false
        ModelRouter.resolve(
            requestedId = "gemini-2.5-flash",
            settings = cloudSettings(),
            weightsAvailable = {
                weightsCalled = true
                true
            },
            hasCloudKey = { true }
        )
        assertEquals(false, weightsCalled)
    }

    @Test
    fun hasCloudKey_is_not_called_for_litert_routes() {
        var keyCalled = false
        ModelRouter.resolve(
            requestedId = "litert-community/Gemma3-1B-IT",
            settings = localSettings(),
            weightsAvailable = weightsAlways,
            hasCloudKey = {
                keyCalled = true
                true
            }
        )
        assertEquals(false, keyCalled)
    }
}
