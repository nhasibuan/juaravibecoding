package com.example.inference

import org.junit.Assert.*
import org.junit.Test

class AiCoreEngineTest {

    @Test
    fun testProbeMissingClassesByDefault() {
        // By default, the testing classpath shouldn't contain the mocked AICore FQCNs
        val probed = AiCoreEngine.probeAiCoreClass()
        assertNull("Expected AICore classes to not be present under default test environment", probed)
    }

    @Test
    fun testGenerateErrorsWhenSdkMissing() {
        // Run generation under mock missing dependencies environment
        val result = kotlinx.coroutines.runBlocking {
            AiCoreEngine.generate("Hello world")
        }
        assertTrue(result is AiCoreEngine.Result.Err)
        val err = result as AiCoreEngine.Result.Err
        assertEquals("load_error", err.type)
        assertTrue(err.message.contains("AICore SDK not detected"))
    }

    @Test
    fun testGenerateStreamingErrorsWhenSdkMissing() {
        val result = kotlinx.coroutines.runBlocking {
            AiCoreEngine.generateStreaming("Hello world") { }
        }
        assertTrue(result is AiCoreEngine.Result.Err)
        val err = result as AiCoreEngine.Result.Err
        assertEquals("load_error", err.type)
        assertTrue(err.message.contains("AICore SDK not detected"))
    }

    @Test
    fun testEnsureLoadedAndResetErrorsWhenSdkMissing() {
        val result = kotlinx.coroutines.runBlocking {
            AiCoreEngine.ensureLoadedAndReset(
                context = org.mockito.Mockito.mock(android.content.Context::class.java),
                modelId = "gemini-nano",
                params = AiCoreEngine.GenerationParams(100, 1.0f, 40, 0.9f),
                systemInstruction = null,
                history = emptyList()
            )
        }
        assertNotNull(result)
        assertEquals("load_error", result?.type)
        assertTrue(result?.message?.contains("AICore SDK not detected") == true)
    }
}
