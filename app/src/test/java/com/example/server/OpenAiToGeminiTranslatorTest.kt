package com.example.server

import com.example.inference.LiteRtLmEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenAiToGeminiTranslatorTest {

    @Test
    fun testTranslateRequestSimple() {
        val openAiJson = """
            {
                "model": "gemini-2.5-flash",
                "messages": [
                    {"role": "system", "content": "You are a helpful assistant."},
                    {"role": "user", "content": "Hello!"}
                ],
                "temperature": 0.7,
                "max_tokens": 100
            }
        """.trimIndent()

        val geminiJsonStr = OpenAiToGeminiTranslator.translateRequest(openAiJson)
        val geminiObj = JSONObject(geminiJsonStr)

        assertTrue(geminiObj.has("contents"))
        assertTrue(geminiObj.has("systemInstruction"))
        assertTrue(geminiObj.has("generationConfig"))

        val contents = geminiObj.getJSONArray("contents")
        assertEquals(1, contents.length())
        val firstChoice = contents.getJSONObject(0)
        assertEquals("user", firstChoice.getString("role"))
        assertEquals("Hello!", firstChoice.getJSONArray("parts").getJSONObject(0).getString("text"))

        val sysIns = geminiObj.getJSONObject("systemInstruction")
        assertEquals("You are a helpful assistant.", sysIns.getJSONArray("parts").getJSONObject(0).getString("text"))

        val genConfig = geminiObj.getJSONObject("generationConfig")
        assertEquals(0.7, genConfig.getDouble("temperature"), 0.001)
        assertEquals(100, genConfig.getInt("maxOutputTokens"))
    }

    @Test
    fun testTranslateResponseSimple() {
        val geminiResponse = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {"text": "Hello there traveler!"}
                            ],
                            "role": "model"
                        },
                        "finishReason": "STOP"
                    }
                ],
                "usageMetadata": {
                    "promptTokenCount": 10,
                    "candidatesTokenCount": 8,
                    "totalTokenCount": 18
                }
            }
        """.trimIndent()

        val openAiResponseStr = OpenAiToGeminiTranslator.translateResponse(geminiResponse, "gemini-2.5-flash")
        val openAiObj = JSONObject(openAiResponseStr)

        assertTrue(openAiObj.has("id"))
        assertEquals("chat.completion", openAiObj.getString("object"))
        assertEquals("gemini-2.5-flash", openAiObj.getString("model"))

        val choices = openAiObj.getJSONArray("choices")
        assertEquals(1, choices.length())
        val choice = choices.getJSONObject(0)
        assertEquals(0, choice.getInt("index"))
        assertEquals("stop", choice.getString("finish_reason"))

        val message = choice.getJSONObject("message")
        assertEquals("assistant", message.getString("role"))
        assertEquals("Hello there traveler!", message.getString("content"))

        val usage = openAiObj.getJSONObject("usage")
        assertEquals(10, usage.getInt("prompt_tokens"))
        assertEquals(8, usage.getInt("completion_tokens"))
        assertEquals(18, usage.getInt("total_tokens"))
    }

    @Test
    fun testExtractLocalEngineRequest() {
        val openAiJson = """
            {
                "model": "litert-community/gemma-4-E2B-it-litert-lm",
                "messages": [
                    {"role": "system", "content": "System prompt info"},
                    {"role": "user", "content": "Question 1"},
                    {"role": "assistant", "content": "Answer 1"},
                    {"role": "user", "content": "Question 2"}
                ],
                "temperature": 0.8,
                "top_p": 0.9,
                "max_tokens": 512
            }
        """.trimIndent()

        val req = OpenAiToGeminiTranslator.extractLocalEngineRequest(openAiJson)

        assertEquals("System prompt info", req.systemInstruction)
        assertEquals("Question 2", req.latestUserText)
        assertEquals(512, req.maxTokens)
        assertEquals(0.8f, req.temperature, 0.001f)
        assertEquals(0.9f, req.topP, 0.001f)

        // History includes: Question 1, Answer 1
        assertEquals(2, req.history.size)
        assertEquals(LiteRtLmEngine.HistoryRole.USER, req.history[0].role)
        assertEquals("Question 1", req.history[0].text)
        assertEquals(LiteRtLmEngine.HistoryRole.ASSISTANT, req.history[1].role)
        assertEquals("Answer 1", req.history[1].text)
    }

    @Test
    fun testWrapLocalSuccess() {
        val resultOk = LiteRtLmEngine.Result.Ok(
            text = "Fine weather",
            thought = "Processing thought process here",
            backendUsed = "gpu",
            latencyMs = 1500
        )

        val openAiResponseStr = OpenAiToGeminiTranslator.wrapLocalSuccess(resultOk, "litert-community/Gemma3-1B-IT")
        val openAiObj = JSONObject(openAiResponseStr)

        val textWithThought = openAiObj.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        assertTrue(textWithThought.contains("<think>"))
        assertTrue(textWithThought.contains("Processing thought process here"))
        assertTrue(textWithThought.contains("Fine weather"))

        assertEquals("litertlm:gpu:1500ms:cache=miss", openAiObj.getString("system_fingerprint"))
    }

    @Test
    fun testStreamingHelpers() {
        val chatCmplId = "chatcmpl-test"
        val model = "test-model"
        
        // 1. First Delta
        val firstStr = OpenAiToGeminiTranslator.streamingFirstDelta(chatCmplId, model, "gpu", 100L, true)
        val firstObj = JSONObject(firstStr)
        assertEquals(chatCmplId, firstObj.getString("id"))
        assertEquals("chat.completion.chunk", firstObj.getString("object"))
        assertEquals("litertlm:gpu:100ms:cache=hit", firstObj.getString("system_fingerprint"))
        val choice1 = firstObj.getJSONArray("choices").getJSONObject(0)
        assertEquals("assistant", choice1.getJSONObject("delta").getString("role"))
        assertEquals("", choice1.getJSONObject("delta").getString("content"))

        // 2. Content Delta
        val contentStr = OpenAiToGeminiTranslator.streamingContentDelta(chatCmplId, model, "Hello", "cpu", 250L, false)
        val contentObj = JSONObject(contentStr)
        assertEquals("litertlm:cpu:250ms:cache=miss", contentObj.getString("system_fingerprint"))
        val choice2 = contentObj.getJSONArray("choices").getJSONObject(0)
        assertEquals("Hello", choice2.getJSONObject("delta").getString("content"))

        // 3. Finish Delta
        val finishStr = OpenAiToGeminiTranslator.streamingFinish(chatCmplId, model, "npu", 500L, true)
        val finishObj = JSONObject(finishStr)
        val choice3 = finishObj.getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice3.getString("finish_reason"))

        // 4. Error Delta
        val errorStr = OpenAiToGeminiTranslator.streamingError(chatCmplId, model, "Something failed", "gpu", 400L, false)
        val errorObj = JSONObject(errorStr)
        val choice4 = errorObj.getJSONArray("choices").getJSONObject(0)
        assertEquals("error", choice4.getString("finish_reason"))
        assertTrue(choice4.getJSONObject("delta").getString("content").contains("Something failed"))
    }

    @Test
    fun testTranslateRequestWithMultimodalBlocks() {
        val json = """
            {
                "model": "gpt-4o",
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Describe this"},
                            {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgoAAAANSUFORK5CYII="}},
                            {"type": "input_audio", "input_audio": {"format": "mp3", "data": "SGVsbG8="}}
                        ]
                    }
                ]
            }
        """.trimIndent()

        val geminiStr = OpenAiToGeminiTranslator.translateRequest(json)
        val geminiObj = JSONObject(geminiStr)

        val contents = geminiObj.getJSONArray("contents")
        assertEquals(1, contents.length())
        val msg = contents.getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        val parts = msg.getJSONArray("parts")
        assertEquals(3, parts.length())

        val textPart = parts.getJSONObject(0)
        assertEquals("Describe this", textPart.getString("text"))

        val imagePart = parts.getJSONObject(1)
        val imageInline = imagePart.getJSONObject("inlineData")
        assertEquals("image/png", imageInline.getString("mimeType"))
        assertEquals("iVBORw0KGgoAAAANSUFORK5CYII=", imageInline.getString("data"))

        val audioPart = parts.getJSONObject(2)
        val audioInline = audioPart.getJSONObject("inlineData")
        assertEquals("audio/mp3", audioInline.getString("mimeType"))
        assertEquals("SGVsbG8=", audioInline.getString("data"))
    }

    @Test
    fun testTranslateRequestThrowsOnHttpAndMalformed() {
        // Test HTTP link rejection
        val httpJson = """
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "image_url", "image_url": {"url": "https://example.com/image.png"}}
                        ]
                    }
                ]
            }
        """.trimIndent()

        try {
            OpenAiToGeminiTranslator.translateRequest(httpJson)
            fail("Should have thrown MultimodalParseException for HTTP link")
        } catch (e: OpenAiToGeminiTranslator.MultimodalParseException) {
            assertTrue(e.message!!.contains("not supported"))
        }

        // Test non-data scheme rejection
        val badSchemeJson = """
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "image_url", "image_url": {"url": "ftp://example.com/image.png"}}
                        ]
                    }
                ]
            }
        """.trimIndent()

        try {
            OpenAiToGeminiTranslator.translateRequest(badSchemeJson)
            fail("Should have thrown MultimodalParseException for FTP scheme")
        } catch (e: OpenAiToGeminiTranslator.MultimodalParseException) {
            assertTrue(e.message!!.contains("only data: URIs are supported"))
        }

        // Test missing base64
        val noBase64Json = """
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "image_url", "image_url": {"url": "data:image/png,1234"}}
                        ]
                    }
                ]
            }
        """.trimIndent()

        try {
            OpenAiToGeminiTranslator.translateRequest(noBase64Json)
            fail("Should have thrown MultimodalParseException for missing base64 declaration")
        } catch (e: OpenAiToGeminiTranslator.MultimodalParseException) {
            assertTrue(e.message!!.contains("missing base64 encoding prefix"))
        }
    }

    @Test
    fun testExtractLocalEngineRequestWithMultimodal() {
        val localJson = """
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Listen to this"},
                            {"type": "input_audio", "input_audio": {"format": "wav", "data": "AAA="}},
                            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,BBB="}}
                        ]
                    }
                ]
            }
        """.trimIndent()

        val req = OpenAiToGeminiTranslator.extractLocalEngineRequest(localJson)
        assertEquals("Listen to this", req.latestUserText)
        assertNotNull(req.rejectedMultimodalReason)
        assertTrue(req.rejectedMultimodalReason!!.contains("input_audio"))
        assertTrue(req.rejectedMultimodalReason!!.contains("image_url"))
    }
}
