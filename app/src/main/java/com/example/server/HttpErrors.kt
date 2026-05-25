package com.example.server

import org.json.JSONObject

/**
 * Renders a [RoutingError] (or any structurally similar tuple) as the
 * OpenAI-compatible error envelope:
 *
 * ```json
 * { "error": { "message": "...", "type": "...", "code": 400 } }
 * ```
 *
 * Used by every non-200 path in [ProxyServerManager] so clients always see
 * the same shape regardless of whether the failure was a routing decision,
 * an upstream Gemini error, or a local engine error.
 */
object HttpErrors {

    fun jsonError(error: RoutingError): String =
        JSONObject()
            .put(
                "error",
                JSONObject()
                    .put("message", error.message)
                    .put("type", error.type)
                    .put("code", error.httpStatus)
            )
            .toString()

    /** Convenience for hand-written errors that don't have a [RoutingError]. */
    fun jsonError(message: String, type: String, code: Int): String =
        JSONObject()
            .put(
                "error",
                JSONObject()
                    .put("message", message)
                    .put("type", type)
                    .put("code", code)
            )
            .toString()
}
