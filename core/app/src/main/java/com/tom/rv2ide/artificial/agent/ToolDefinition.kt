package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.tools.ToolRegistry
import com.tom.rv2ide.artificial.tools.ToolSchema

/**
 * Provider-neutral tool definition.
 *
 * This is the single source of truth for a tool's interface. Provider
 * adapters convert this to their native schema format (Gemini function
 * declarations, OpenAI function calling, Anthropic tool use).
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: ToolSchema
) {

    /** Unique key for this tool definition (namespace:name). */
    val key: String = name

    /**
     * Converts this definition to a JSON Schema object suitable for
     * provider function calling APIs.
     */
    fun toJsonSchema(): Map<String, Any?> {
        val properties = mutableMapOf<String, Any?>()
        val required = mutableListOf<String>()

        parameters.fields.forEach { field ->
            val fieldSchema = mutableMapOf<String, Any?>()
            fieldSchema["type"] = field.type.name.lowercase()
            field.description?.let { fieldSchema["description"] = it }
            field.default?.let { fieldSchema["default"] = it }
            properties[field.name] = fieldSchema
            if (field.required) {
                required.add(field.name)
            }
        }

        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false
        )
    }
}

/**
 * Registry of tool definitions for native function calling.
 *
 * This mirrors ToolRegistry but provides schema information in the
 * format expected by provider function calling APIs.
 */
object ToolDefinitionRegistry {

    private val definitions = mutableMapOf<String, ToolDefinition>()

    fun register(definition: ToolDefinition) {
        definitions[definition.key] = definition
    }

    fun unregister(key: String): Boolean = definitions.remove(key) != null

    fun lookup(key: String): ToolDefinition? = definitions[key]

    fun all(): List<ToolDefinition> = definitions.values.toList()

    fun availableKeys(): List<String> = definitions.keys.toList()

    fun clear() {
        definitions.clear()
    }

    /**
     * Builds the function declarations list for provider APIs.
     *
     * Returns a list of function declarations in the format expected by
     * the AI SDK's `streamObject`/`generateObject` function calling.
     */
    fun toFunctionDeclarations(): List<Map<String, Any?>> {
        return definitions.values.map { it.toFunctionDeclaration() }
    }

    /**
     * Populates this registry from the existing ToolRegistry.
     *
     * This should be called once at startup to synchronize the two registries.
     */
    fun syncFromToolRegistry(toolRegistry: ToolRegistry) {
        clear()
        toolRegistry.all().forEach { tool ->
            register(ToolDefinition(
                name = tool.id,
                description = tool.description,
                parameters = tool.schema
            ))
        }
    }
}

private fun ToolDefinition.toFunctionDeclaration(): Map<String, Any?> {
    return mapOf(
        "name" to name,
        "description" to description,
        "parameters" to toJsonSchema()
    )
}