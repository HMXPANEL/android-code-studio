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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Single chokepoint for every tool execution:
 * resolve → validate → permit → confirm → execute (timeout) → truncate.
 *
 * Never throws past this class except [CancellationException] (run cancel),
 * which must propagate so structured cancellation works. The confirmation UI
 * is injected as [onConfirm] so this class stays Android-free and testable.
 */
open class ToolExecutor(
    private val registry: ToolRegistry = ToolRegistry,
    private val outputCharCap: Int = 8000
) {

  suspend fun execute(
      call: ToolCall,
      ctx: ToolContext,
      permission: ToolPermission,
      onConfirm: suspend (ToolCall) -> Boolean
  ): ToolResult {
    val tool = registry.lookup(call.name)
        ?: return ToolResult.failure(
            "Unknown tool '${call.name}'. Available tools: " +
                registry.availableIds().joinToString(", ")
        )

    val argError = validateArgs(tool, call.args)
    if (argError != null) {
      return ToolResult.failure(
          "Invalid arguments for '${tool.id}': $argError. " +
              "Expected: ${describeSchema(tool)}"
      )
    }

    if (ctx.planMode && tool.kind != ToolKind.READ) {
      return ToolResult.failure(
          "Tool '${tool.id}' is disabled in PLAN mode. " +
              "Only read-only tools may be used; produce a plan instead."
      )
    }

    val decision = permission.decide(tool)
    when (decision) {
      ToolDecision.DENY -> return ToolResult.failure(
          "Tool '${tool.id}' is not permitted in the current mode."
      )
      ToolDecision.ASK -> { /* fall through to confirmation below */ }
      ToolDecision.ALLOW -> { /* proceed */ }
    }

    if (decision == ToolDecision.ASK || tool.confirmPolicy == ConfirmPolicy.ALWAYS) {
      val granted = try {
        onConfirm(call)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return ToolResult.failure("Confirmation failed: ${e.message}")
      }
      if (!granted) {
        return ToolResult.failure(
            "User denied '${tool.id}'. Explain what you need instead and continue."
        )
      }
    }

    val raw: ToolResult = try {
      withTimeout(tool.timeoutSec * 1000L) {
        tool.execute(call.args, ctx)
      }
    } catch (e: TimeoutCancellationException) {
      return ToolResult.failure(
          "Tool '${tool.id}' timed out after ${tool.timeoutSec}s and was stopped."
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return ToolResult.failure("Tool '${tool.id}' failed: ${e.message}")
    }

    if (raw.text.length <= outputCharCap) {
      return raw
    }
    val capped = Truncate.chars(raw.text, outputCharCap, "tool output")
    return raw.copy(text = capped.text, truncated = true)
  }

  private fun validateArgs(tool: Tool, args: Map<String, Any?>): String? {
    tool.schema.fields.forEach { field ->
      val value = if (args.containsKey(field.name)) args[field.name] else field.default
      if (value == null) {
        if (field.required) {
          return "missing required argument '${field.name}'"
        }
        return@forEach
      }
      val ok = when (field.type) {
        ToolInputType.STRING -> value is String
        ToolInputType.NUMBER -> value is Number
        ToolInputType.BOOLEAN -> value is Boolean
        ToolInputType.OBJECT -> value is Map<*, *>
        ToolInputType.ARRAY -> value is List<*>
        ToolInputType.ANY -> true
      }
      if (!ok) {
        return "argument '${field.name}' must be ${field.type.name.lowercase()}"
      }
    }
    return null
  }

  private fun describeSchema(tool: Tool): String {
    return tool.schema.fields.joinToString(", ") { field ->
      val marker = if (field.required) "required" else "optional"
      "${field.name}(${field.type.name.lowercase()},$marker)"
    }
  }
}
