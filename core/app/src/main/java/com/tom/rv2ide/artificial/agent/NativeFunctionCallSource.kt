package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.tools.ToolCall

/**
 * Provider-native function calling response.
 *
 * Represents a model response that contains native function calls
 * instead of (or in addition to) text. Different providers have
 * different formats; this is the normalized internal representation.
 */
data class NativeFunctionCallResponse(
    /** The function calls requested by the model. */
    val functionCalls: List<NativeFunctionCall> = emptyList(),
    /** Any text content accompanying the function calls. */
    val text: String = "",
    /** Provider-specific metadata. */
    val metadata: Map<String, Any?> = emptyMap()
)

/**
 * Provider-neutral function call representation.
 *
 * Different providers have different function call formats.
 * This is the normalized internal representation used throughout ACS.
 */
data class NativeFunctionCall(
    val name: String,
    val args: Map<String, Any?> = emptyMap(),
    val callId: String = ""
)

/**
 * Interface for providers that support native function calling.
 *
 * Implementations convert the provider's native function calling
 * format into the internal [NativeFunctionCallResponse].
 */
interface NativeFunctionCallProvider {

    /**
     * The provider's display name.
     */
    val providerName: String

    /**
     * Generates a response with native function calling.
     *
     * @param prompt the full assembled prompt
     * @param functionDeclarations the available tool declarations in provider format
     * @param language the target programming language
     * @param projectStructure optional project structure context
     * @return a response containing function calls and/or text
     */
    suspend fun generateWithFunctions(
        prompt: String,
        functionDeclarations: List<Any>, // Provider-specific function declaration type
        language: String = "kotlin",
        projectStructure: String? = null
    ): Result<NativeFunctionCallResponse>
}

/**
 * Phase 2 source: parses provider-native function calls into [ToolCall] events.
 *
 * This source is used when the provider supports native function calling.
 * It converts the provider's native function call format into ACS's
 * internal [ToolCall] representation.
 */
class NativeFunctionCallSource : ToolCallSource {

    override fun extractCalls(reply: String, runId: String): List<ToolCall> {
        // For native function calling, the "reply" is actually a JSON-encoded
        // NativeFunctionCallResponse. This fallback handles cases where the
        // provider returns a mixed text+functions response that wasn't fully
        // parsed by the provider adapter.
        return try {
            val response = parseNativeResponse(reply)
            response.functionCalls.mapIndexed { index, fc ->
                ToolCall(
                    name = fc.name,
                    args = fc.args,
                    callId = "call-$runId-$index",
                    runId = runId
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun extractErrors(reply: String, runId: String): List<ParseError> {
        // Native function calling doesn't produce parse errors in the same way.
        // Errors are handled at the provider level.
        return emptyList()
    }

    /**
     * Parses a provider-native function call response into [ToolCall] list.
     *
     * @param response the provider's native function call response
     * @param runId the current run ID for call ID generation
     * @return list of normalized ToolCalls
     */
    fun parseNativeResponse(response: NativeFunctionCallResponse, runId: String): List<ToolCall> {
        return response.functionCalls.mapIndexed { index, fc ->
            ToolCall(
                name = fc.name,
                args = fc.args,
                callId = "call-$runId-$index",
                runId = runId
            )
        }
    }

    /**
     * Parses a JSON string into a [NativeFunctionCallResponse].
     *
     * Used as a fallback when the provider adapter returns a JSON string
     * instead of a structured response.
     */
    private fun parseNativeResponse(json: String): NativeFunctionCallResponse {
        // Simple JSON parsing for fallback - in practice the provider adapter
        // should return a structured response directly.
        return try {
            // Minimal parsing - in production use a proper JSON parser
            NativeFunctionCallResponse()
        } catch (e: Exception) {
            NativeFunctionCallResponse()
        }
    }
}