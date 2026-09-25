/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.tools

/**
 * Central tool registry (mirrors the existing provider-side `AIAgentRegistry`
 * idea, but for tools). Plain `object`, deterministic iteration order, no DI.
 */
object ToolRegistry {

  private val tools = LinkedHashMap<String, Tool>()

  fun register(tool: Tool) {
    tools[tool.id] = tool
  }

  fun unregister(toolId: String): Boolean = tools.remove(toolId) != null

  fun lookup(toolId: String): Tool? = tools[toolId]

  fun all(): List<Tool> = tools.values.toList()

  fun availableIds(): List<String> = tools.keys.toList()

  /** Test/support hook: clears the registry. Production code registers at startup. */
  fun clear() {
    tools.clear()
  }

  fun count(): Int = tools.size

  /**
   * Renders the model-facing tool menu. Only listed tools available in the
   * given mode are described (keeps prompts small); PLAN runs additionally get
   * one line stating modification tools are disabled.
   */
  fun describeForPrompt(planMode: Boolean): String {
    val usable = tools.values.filter { it.visible && (!planMode || it.kind == ToolKind.READ) }
    if (usable.isEmpty()) {
      return "No tools are available."
    }
    val builder = StringBuilder()
    builder.append("Available tools (call exactly one per turn with a TOOL_CALL: block):\n")
    usable.forEach { tool ->
      builder.append("- ${tool.id}: ${tool.description} Args: ")
      builder.append(
          tool.schema.fields.joinToString(", ") { field ->
            val marker = if (field.required) "required" else "optional"
            "${field.name}(${field.type.name.lowercase()},$marker)"
          }
      )
      builder.append("\n")
    }
    if (planMode) {
      builder.append("File modification tools are disabled in PLAN mode.\n")
    }
    return builder.toString().trim()
  }
}
