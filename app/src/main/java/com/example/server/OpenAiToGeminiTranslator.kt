package com.example.server

import com.example.inference.LiteRtLmEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Translates between OpenAI Chat-Completions and:
 *   1. Google Gemini REST shape  (cloud path — translateRequest / translateResponse)
 *   2. LiteRT-LM Kotlin SDK      (local path — extractLocalEngineRequest / wrapLocalSuccess / wrapLocalError)
 *
 * The previous simulator (`solveSimplePrompt`, `generateSimulatedResponse`,
 * `generateLiteRtLmResponse`) was removed: it was a regex-based keyword matcher
 * that fabricated "tokens/second" metrics and pretended to be on-device
 * inference. Real LiteRT-LM execution is now in [LiteRtLmEngine], following
 * the canonical pattern from google-ai-edge/gallery.
 */
object OpenAiToGeminiTranslator {

    // ------------------------------------------------------------------------
    // Cloud path
    // ------------------------------------------------------------------------

    /**
     * Thrown by [translateRequest] when an OpenAI multimodal content block
     * cannot be converted to a Gemini `inlineData` part. The dispatcher
     * (ProxyServerManager) catches this specifically and renders a 400 with
     * the matching OpenAI-shaped error envelope, so clients see a clean
     * "your image_url was bad" reply instead of a generic gateway 500.
     */
    class MultimodalParseException(
        val httpStatus: Int,
        val errType: String,
        msg: String
    ) : RuntimeException(msg)

    /**
     * Parses a `data:<mime>;base64,<base64>` URI into `(mime, base64Data)`.
     * Returns null if the URI is not a `data:` URI at all.
     *
     * For cloud Gemini we forward the raw base64 unchanged inside an
     * `inlineData` part. We do not attempt to decode-and-re-encode the bytes;
     * the upstream just needs the bytes in base64 with a mime type.
     *
     * Per plan.md §11 PR #7, only `data:` URIs are supported. `http(s)://`
     * URLs are explicitly rejected upstream of this helper to avoid SSRF and
     * to keep the gateway free of any outbound dependency on arbitrary
     * remote hosts.
     */
    internal fun parseDataUri(uri: String): Pair<String, String>? {
        if (!uri.startsWith("data:")) return null
        val comma = uri.indexOf(',')
        if (comma < 0) {
            throw MultimodalParseException(
                httpStatus = 400,
                errType = "invalid_request_error",
                msg = "Malformed data URI: missing comma separator."
            )
        }
        val header = uri.substring(5, comma)
        val data = uri.substring(comma + 1)
        if (!header.contains(";base64")) {
            throw MultimodalParseException(
                httpStatus = 400,
                errType = "invalid_request_error",
                msg = "Only base64-encoded data URIs are supported by this gateway. " +
                        "Got header: '${header.take(60)}'"
            )
        }
        val mime = header.substringBefore(";").ifEmpty { "application/octet-stream" }
        return mime to data
    }

    /**
     * Builds the Gemini `parts` array for a single OpenAI message's content.
     * Handles three OpenAI content shapes:
     *   - String       -> single text part
     *   - JSONArray of blocks -> mixed text + inlineData parts
     *   - anything else -> empty
     *
     * Recognized block types (plan.md §11 PR #7):
     *   - `{ type: "text", text: "..." }` -> `{ "text": "..." }`
     *   - `{ type: "image_url", image_url: { url: "data:image/...;base64,..." } }`
     *     -> `{ "inlineData": { "mimeType": "image/...", "data": "..." } }`
     *   - `{ type: "input_audio", input_audio: { format: "mp3"|"wav", data: "..." } }`
     *     -> `{ "inlineData": { "mimeType": "audio/mpeg"|"audio/wav", "data": "..." } }`
     *
     * Unknown block types are silently skipped (forward-compat with future
     * OpenAI block types). Malformed data URIs and `http(s)://` URLs throw
     * [MultimodalParseException] with HTTP 400 metadata.
     */
    internal fun buildPartsFromContent(content: Any?): JSONArray {
        val parts = JSONArray()
        when (content) {
            is String -> parts.put(JSONObject().put("text", content))
            is JSONArray -> {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    when (item.optString("type")) {
                        "text" -> {
                            val t = item.optString("text", "")
                            if (t.isNotEmpty()) parts.put(JSONObject().put("text", t))
                        }
                        "image_url" -> {
                            val imgObj = item.optJSONObject("image_url")
                                ?: throw MultimodalParseException(
                                    400, "invalid_request_error",
                                    "image_url block missing 'image_url' object."
                                )
                            val url = imgObj.optString("url", "")
                            if (url.isEmpty()) {
                                throw MultimodalParseException(
                                    400, "invalid_request_error",
                                    "image_url.url is empty."
                                )
                            }
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                throw MultimodalParseException(
                                    400, "invalid_request_error",
                                    "image_url.url must be a data: URI; this gateway does not " +
                                            "fetch http(s):// images. Inline the image as " +
                                            "data:image/<type>;base64,<base64> instead."
                                )
                            }
                            val (mime, data) = parseDataUri(url)
                                ?: throw MultimodalParseException(
                                    400, "invalid_request_error",
                                    "image_url.url is not a recognized data URI: '${url.take(40)}'"
                                )
                            parts.put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject()
                                        .put("mimeType", mime)
                                        .put("data", data)
                                )
                            )
                        }
                        "input_audio" -> {
                            val audObj = item.optJSONObject("input_audio")
                                ?: throw MultimodalParseException(
                                    400, "invalid_request_error",
                                    "input_audio block missing 'input_audio' object."
                                )
                            val data = audObj.optString("data", "")
                            if (data.isEmpty()) {
                                throw MultimodalParseException(
                                    400, "invalid_request_error",
                                    "input_audio.data is empty."
                                )
                            }
                            val format = audObj.optString("format", "wav").lowercase()
                            val mime = when (format) {
                                "mp3" -> "audio/mpeg"
                                "wav" -> "audio/wav"
                                "ogg" -> "audio/ogg"
                                "flac" -> "audio/flac"
                                else -> "audio/$format"
                            }
                            parts.put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject()
                                        .put("mimeType", mime)
                                        .put("data", data)
                                )
                            )
                        }
                        else -> {
                            // Unknown block type — skip rather than fail, so
                            // forward-compatible with future OpenAI types.
                        }
                    }
                }
            }
            else -> { /* null/missing content -> empty parts */ }
        }
        return parts
    }

    /**
     * Translates an OpenAI Chat Completion request JSON into a Google Gemini REST request JSON.
     */
    fun translateRequest(openAiJson: String): String {
        val openAiObj = JSONObject(openAiJson)
        val messages = openAiObj.optJSONArray("messages") ?: JSONArray()

        val CONTENTS = JSONArray()
        var systemInstructionText = ""

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")

            // System messages are extracted separately and joined; multimodal
            // blocks are not meaningful inside a system instruction (Gemini's
            // systemInstruction takes a parts array but the typical use is
            // pure text), so we flatten to text only here.
            if (role == "system") {
                val sysContent = msg.opt("content")
                val sysText = when (sysContent) {
                    is String -> sysContent
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (j in 0 until sysContent.length()) {
                            val sub = sysContent.optJSONObject(j) ?: continue
                            if (sub.optString("type") == "text") {
                                sb.append(sub.optString("text"))
                            }
                        }
                        sb.toString()
                    }
                    else -> ""
                }
                if (sysText.isNotEmpty()) {
                    systemInstructionText = if (systemInstructionText.isEmpty()) {
                        sysText
                    } else {
                        "$systemInstructionText\n\n$sysText"
                    }
                }
                continue
            }

            // Map OpenAI assistant role to Gemini model role
            val geminiRole = if (role == "assistant") "model" else "user"

            val partsArr = buildPartsFromContent(msg.opt("content"))

            // Skip empty messages — Gemini rejects content entries with no parts.
            if (partsArr.length() == 0) continue

            val contentItem = JSONObject()
                .put("role", geminiRole)
                .put("parts", partsArr)

            CONTENTS.put(contentItem)
        }

        val geminiRoot = JSONObject().put("contents", CONTENTS)

        // Map system instruction if present
        if (systemInstructionText.isNotEmpty()) {
            val systemPart = JSONObject().put("text", systemInstructionText)
            val systemParts = JSONArray().put(systemPart)
            val sysInstructionObj = JSONObject().put("parts", systemParts)
            geminiRoot.put("systemInstruction", sysInstructionObj)
        }

        // Map generation configs
        val genConfig = JSONObject()
        if (openAiObj.has("temperature")) {
            genConfig.put("temperature", openAiObj.get("temperature"))
        } else {
            genConfig.put("temperature", 1.0)
        }
        if (openAiObj.has("max_tokens")) {
            genConfig.put("maxOutputTokens", openAiObj.get("max_tokens"))
        } else if (openAiObj.has("max_completion_tokens")) {
            genConfig.put("maxOutputTokens", openAiObj.get("max_completion_tokens"))
        } else {
            genConfig.put("maxOutputTokens", 2048)
        }
        if (openAiObj.has("top_p")) {
            genConfig.put("topP", openAiObj.get("top_p"))
        }

        geminiRoot.put("generationConfig", genConfig)

        return geminiRoot.toString()
    }

    /**
     * Translates a Google Gemini API response JSON back to an OpenAI Chat Completion response JSON.
     */
    fun translateResponse(geminiJson: String, openAiModel: String): String {
        val geminiObj = JSONObject(geminiJson)
        val candidates = geminiObj.optJSONArray("candidates")

        var textContent = "No response text found."
        var finishReason = "stop"

        if (candidates != null && candidates.length() > 0) {
            val firstCandidate = candidates.getJSONObject(0)
            val contentObj = firstCandidate.optJSONObject("content")
            if (contentObj != null) {
                val parts = contentObj.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    textContent = parts.getJSONObject(0).optString("text", "")
                }
            }
            val rawFinishReason = firstCandidate.optString("finishReason", "STOP")
            finishReason = when (rawFinishReason) {
                "STOP" -> "stop"
                "MAX_TOKENS" -> "length"
                "SAFETY" -> "content_filter"
                else -> "stop"
            }
        }

        // Parse Usage metadata
        val usageMetadata = geminiObj.optJSONObject("usageMetadata")
        val promptTokens = usageMetadata?.optInt("promptTokenCount", 0) ?: 0
        val completionTokens = usageMetadata?.optInt("candidatesTokenCount", 0) ?: 0
        val totalTokens = usageMetadata?.optInt("totalTokenCount", 0) ?: 0

        val chatCmplId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val createdSeconds = System.currentTimeMillis() / 1000

        val selectionObj = JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", textContent))
            .put("finish_reason", finishReason)

        val choicesArr = JSONArray().put(selectionObj)

        val usageObj = JSONObject()
            .put("prompt_tokens", if (promptTokens > 0) promptTokens else 16)
            .put("completion_tokens", if (completionTokens > 0) completionTokens else textContent.length / 4)
            .put("total_tokens", if (totalTokens > 0) totalTokens else (16 + textContent.length / 4))

        val openAiResponse = JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion")
            .put("created", createdSeconds)
            .put("model", openAiModel)
            .put("choices", choicesArr)
            .put("usage", usageObj)

        return openAiResponse.toString()
    }

    // ------------------------------------------------------------------------
    // Local LiteRT-LM path
    // ------------------------------------------------------------------------

    data class LocalEngineRequest(
        val systemInstruction: String?,
        val history: List<LiteRtLmEngine.HistoryTurn>,
        val latestUserText: String,
        val maxTokens: Int,
        val temperature: Float,
        val topK: Int,
        val topP: Float,
        /**
         * If the OpenAI request contained any multimodal content blocks
         * (`image_url`, `input_audio`, etc.) this is set to a human-readable
         * reason. The dispatcher must check this and short-circuit with a 400
         * `multimodal_not_supported` rather than silently dropping the
         * blocks and producing a text-only reply that pretends nothing was
         * stripped.
         *
         * The local engine ([LiteRtLmEngine]) accepts only text turns today
         * (`HistoryTurn(role, text)`); when that changes, this field becomes
         * the natural extension point for plumbing image/audio bytes through.
         */
        val rejectedMultimodalReason: String? = null
    )

    /**
     * Extracts a request shaped for the LiteRT-LM Kotlin SDK from an OpenAI
     * chat.completions JSON body.
     *
     * Mapping:
     *  - All `system` role messages are concatenated into a single system instruction.
     *  - All `assistant` and prior `user` messages become history turns.
     *  - The last `user` message is split out as the live prompt for `generate(...)`.
     *  - `max_tokens` / `max_completion_tokens` / `temperature` / `top_p` / `top_k`
     *    are extracted with sensible defaults.
     *
     * Multimodal handling (plan.md §11 PR #7): if any message contains a
     * non-text content block (`image_url`, `input_audio`, etc.), the returned
     * [LocalEngineRequest.rejectedMultimodalReason] is set with a description
     * of what was found. The dispatcher must short-circuit with a 400 rather
     * than route to the text-only engine — silent stripping would lie to the
     * caller about what the model actually saw.
     */
    fun extractLocalEngineRequest(openAiJson: String): LocalEngineRequest {
        val obj = JSONObject(openAiJson)
        val msgs = obj.optJSONArray("messages") ?: JSONArray()

        val systemPieces = mutableListOf<String>()
        val turns = mutableListOf<LiteRtLmEngine.HistoryTurn>()
        var lastUserText = ""
        var multimodalReason: String? = null

        // First pass: collect system + history (all turns except the trailing user one).
        // We need to know which user message is the LAST one to peel it off.
        val lastUserIdx = (0 until msgs.length()).lastOrNull { i ->
            msgs.optJSONObject(i)?.optString("role") == "user"
        }

        for (i in 0 until msgs.length()) {
            val msg = msgs.getJSONObject(i)
            val role = msg.optString("role", "user")
            val (text, multimodalKind) = extractTextAndDetectMultimodal(msg.opt("content"))
            if (multimodalKind != null && multimodalReason == null) {
                multimodalReason = "Local LiteRT-LM engine does not yet accept '$multimodalKind' " +
                        "content blocks. Use a cloud Gemini model id, or remove the multimodal " +
                        "content and retry."
            }

            when (role) {
                "system" -> if (text.isNotEmpty()) systemPieces.add(text)
                "assistant" -> turns.add(
                    LiteRtLmEngine.HistoryTurn(LiteRtLmEngine.HistoryRole.ASSISTANT, text)
                )
                else /* user or anything else */ -> {
                    if (i == lastUserIdx) {
                        lastUserText = text
                    } else {
                        turns.add(LiteRtLmEngine.HistoryTurn(LiteRtLmEngine.HistoryRole.USER, text))
                    }
                }
            }
        }

        // Defensive: no trailing user message at all → use empty prompt; the
        // engine will still produce a continuation based on history.
        val systemText = if (systemPieces.isEmpty()) null else systemPieces.joinToString("\n\n")

        val maxTokens = (obj.opt("max_tokens") as? Number)?.toInt()
            ?: (obj.opt("max_completion_tokens") as? Number)?.toInt()
            ?: 1024
        val temperature = (obj.opt("temperature") as? Number)?.toFloat() ?: 1.0f
        val topP = (obj.opt("top_p") as? Number)?.toFloat() ?: 0.95f
        val topK = (obj.opt("top_k") as? Number)?.toInt() ?: 64

        return LocalEngineRequest(
            systemInstruction = systemText,
            history = turns,
            latestUserText = lastUserText,
            maxTokens = maxTokens,
            temperature = temperature,
            topK = topK,
            topP = topP,
            rejectedMultimodalReason = multimodalReason
        )
    }

    /** Wraps a successful LiteRT-LM response in an OpenAI Chat Completion JSON. */
    fun wrapLocalSuccess(result: LiteRtLmEngine.Result.Ok, openAiModel: String): String {
        val chatCmplId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val createdSeconds = System.currentTimeMillis() / 1000

        // If the model exposes a separate "thought" channel (gemma-4 thinking,
        // DeepSeek R1, etc.), render it inside <think>...</think> ahead of the
        // final answer. This matches the convention OpenAI-compatible clients
        // recognize for reasoning models.
        val replyContent = if (!result.thinkingText.isNullOrBlank()) {
            "<think>\n${result.thinkingText}\n</think>\n${result.text}"
        } else {
            result.text
        }

        val choice = JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", replyContent))
            .put("finish_reason", "stop")

        val usage = JSONObject()
            .put("prompt_tokens", result.promptTokens)
            .put("completion_tokens", result.completionTokens)
            .put("total_tokens", result.promptTokens + result.completionTokens)

        return JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion")
            .put("created", createdSeconds)
            .put("model", openAiModel)
            .put("choices", JSONArray().put(choice))
            .put("usage", usage)
            // Honest provenance: real backend used + measured latency + KV-cache hit/miss.
            // `cache=hit` means this turn reused the existing Conversation; `cache=miss`
            // means a new Conversation was built (engine reload, new history, or first turn).
            .put(
                "system_fingerprint",
                "litertlm:${result.backendUsed}:${result.totalLatencyMs}ms" +
                        ":cache=${if (result.kvCacheReused) "hit" else "miss"}"
            )
            .toString()
    }

    // ------------------------------------------------------------------------
    // Streaming (SSE) helpers — produce OpenAI-shaped chat.completion.chunk frames.
    // ------------------------------------------------------------------------

    /**
     * Generates a fresh stream id and the matching `created` epoch-second, both
     * shared across every frame in the stream so OpenAI clients can group them.
     */
    fun newStreamSession(): Pair<String, Long> {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        return id to (System.currentTimeMillis() / 1000)
    }

    /**
     * The opening `delta: { "role": "assistant" }` chunk every OpenAI stream
     * starts with. Tells clients to start rendering an assistant message.
     */
    fun streamingFirstDelta(streamId: String, createdSec: Long, openAiModel: String): String {
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", JSONObject().put("role", "assistant"))
            .put("finish_reason", JSONObject.NULL)
        return JSONObject()
            .put("id", streamId)
            .put("object", "chat.completion.chunk")
            .put("created", createdSec)
            .put("model", openAiModel)
            .put("choices", JSONArray().put(choice))
            .toString()
    }

    /** A `delta: { "content": "<chunk>" }` frame with a slice of new text. */
    fun streamingContentDelta(
        streamId: String,
        createdSec: Long,
        openAiModel: String,
        deltaText: String
    ): String {
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", JSONObject().put("content", deltaText))
            .put("finish_reason", JSONObject.NULL)
        return JSONObject()
            .put("id", streamId)
            .put("object", "chat.completion.chunk")
            .put("created", createdSec)
            .put("model", openAiModel)
            .put("choices", JSONArray().put(choice))
            .toString()
    }

    /**
     * The terminating frame: empty `delta`, set `finish_reason`, and an
     * approximate `usage` block (LiteRT-LM doesn't surface token counts so we
     * use the same 4-chars-per-token heuristic as [wrapLocalSuccess]).
     * Honest provenance lives in `system_fingerprint` exactly like the
     * non-streaming path.
     */
    fun streamingFinish(
        streamId: String,
        createdSec: Long,
        openAiModel: String,
        result: LiteRtLmEngine.Result.Ok
    ): String {
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", JSONObject())
            .put("finish_reason", "stop")
        val usage = JSONObject()
            .put("prompt_tokens", result.promptTokens)
            .put("completion_tokens", result.completionTokens)
            .put("total_tokens", result.promptTokens + result.completionTokens)
        return JSONObject()
            .put("id", streamId)
            .put("object", "chat.completion.chunk")
            .put("created", createdSec)
            .put("model", openAiModel)
            .put("choices", JSONArray().put(choice))
            .put("usage", usage)
            .put(
                "system_fingerprint",
                "litertlm:${result.backendUsed}:${result.totalLatencyMs}ms" +
                        ":cache=${if (result.kvCacheReused) "hit" else "miss"}"
            )
            .toString()
    }

    /**
     * An OpenAI-shaped error frame for use inside a streaming response. Some
     * clients tolerate this; many treat it as fatal and disconnect. Either
     * way, callers should follow it with the `[DONE]` sentinel and close the
     * socket.
     */
    fun streamingError(message: String, type: String, openAiModel: String): String {
        return JSONObject()
            .put("error", JSONObject()
                .put("message", message)
                .put("type", type))
            .put("model", openAiModel)
            .toString()
    }

    /**
     * Maps a LiteRT-LM error onto an OpenAI-shaped error envelope and an HTTP status code.
     */
    fun wrapLocalError(err: LiteRtLmEngine.Result.Err, openAiModel: String): Pair<Int, String> {
        val status = when (err.type) {
            "model_not_found" -> 400
            "engine_not_ready" -> 500
            "engine_load_failed" -> 500
            "conversation_init_failed" -> 500
            "inference_failed" -> 500
            else -> 500
        }
        val body = JSONObject()
            .put("error", JSONObject()
                .put("message", err.message)
                .put("type", err.type)
                .put("code", status))
            .put("model", openAiModel)
            .toString()
        return status to body
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Extracts plain text from an OpenAI message's `content` field, and
     * separately reports whether any non-text block was present (so the caller
     * can refuse rather than silently strip).
     *
     * Returns `(plainText, multimodalKind)` where:
     *   - `plainText` is the concatenation of all `type: "text"` blocks (or
     *     the whole content if it's a plain string).
     *   - `multimodalKind` is the type-string of the first non-text block
     *     encountered (e.g. `"image_url"`, `"input_audio"`), or null if every
     *     block was a text block.
     */
    private fun extractTextAndDetectMultimodal(content: Any?): Pair<String, String?> {
        return when (content) {
            is String -> content to null
            is JSONArray -> {
                val sb = StringBuilder()
                var firstNonText: String? = null
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val type = item.optString("type")
                    if (type == "text") {
                        sb.append(item.optString("text"))
                    } else if (type.isNotEmpty() && firstNonText == null) {
                        firstNonText = type
                    }
                }
                sb.toString() to firstNonText
            }
            else -> "" to null
        }
    }
}
