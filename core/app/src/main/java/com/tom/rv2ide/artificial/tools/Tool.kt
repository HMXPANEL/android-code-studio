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

import java.io.File
import kotlinx.coroutines.Job

/**
 * Phase 1 agent runtime contracts.
 *
 * Everything in this file is plain Kotlin with no Android dependencies so the
 * contract logic stays JVM-testable. See PHASE-1-FINAL-IMPLEMENTATION-PLAN.md.
 */

/** What mode an agent run operates in. Mirrors the chat UI mode 1:1. */
enum class RunMode {
  BUILD,
  PLAN
}

/** Safety class of a tool. Drives PLAN filtering and confirmation. */
enum class ToolKind {
  READ,
  WRITE,
  DESTRUCTIVE,
  POWER
}

/** Whether a tool is advertised to the model. Reserved for deferred loading. */
enum class ToolVisibility {
  LISTED,
  HIDDEN
}

/** Whether executing the tool always requires explicit user confirmation. */
enum class ConfirmPolicy {
  NEVER,
  ALWAYS
}

/** Supported argument value types for tool input schemas. */
enum class ToolInputType {
  STRING,
  NUMBER,
  BOOLEAN,
  OBJECT,
  ARRAY,
  ANY
}

/** One named argument accepted by a tool. */
data class ToolInputField(
    val name: String,
    val type: ToolInputType,
    val description: String,
    val required: Boolean = false,
    val default: Any? = null
)

/** Declares the arguments a tool accepts. Plain data, no new library. */
data class ToolSchema(val fields: List<ToolInputField>) {

  fun field(name: String): ToolInputField? = fields.firstOrNull { it.name == name }
}

/** Uniform envelope every tool execution returns. Never throws past executor. */
data class ToolResult(
    val ok: Boolean,
    val text: String,
    val error: String? = null,
    val stats: Map<String, String> = emptyMap(),
    val truncated: Boolean = false,
    val attachments: List<String> = emptyList()
) {
  companion object {
    fun success(
        text: String,
        stats: Map<String, String> = emptyMap(),
        truncated: Boolean = false
    ): ToolResult = ToolResult(ok = true, text = text, stats = stats, truncated = truncated)

    fun failure(error: String, stats: Map<String, String> = emptyMap()): ToolResult =
        ToolResult(ok = false, text = "", error = error, stats = stats)
  }
}

/** Everything a tool needs to execute safely. Threaded into every execution. */
data class ToolContext(
    val projectRoot: File,
    val planMode: Boolean,
    val runId: String,
    val job: Job,
    val stepIndex: Int,
    /**
     * Called by file tools after a write/edit/delete with
     * (path, previousContent, newContent, success) so agent-level
     * modification history and undo stay consistent. Null in tests.
     */
    val onFileModified: ((String, String?, String, Boolean) -> Unit)? = null
)

/** One normalized tool invocation (produced by a [com.tom.rv2ide.artificial.agent.ToolCallSource]). */
data class ToolCall(
    val name: String,
    val args: Map<String, Any?>,
    val callId: String,
    /** Run ID for attribution; reserved for subagent/background run tracking (Phase 6+). */
    val runId: String,
    /** True if converted from legacy FILE_TO_MODIFY format; reserved for migration. */
    val legacy: Boolean = false
) {
  /** Fingerprint used by the doom-loop guard (same tool + same args). */
  fun fingerprint(): String {
    val rendered = args.toSortedMap(compareBy { it }).entries.joinToString(",") { (k, v) ->
      "$k=${renderArg(v)}"
    }
    return "$name{$rendered}"
  }

  private fun renderArg(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"$value\""
    is Map<*, *> -> value.toSortedMap(compareBy { "$it" }).entries.joinToString(",") {
      "${it.key}=${renderArg(it.value)}"
    }.let { "{$it}" }
    is List<*> -> value.joinToString(",", "[", "]") { renderArg(it) }
    else -> value.toString()
  }
}

/** Single tool contract. Implementations wrap exactly one existing backend. */
interface Tool {
  val id: String
  /** Namespace prefix (e.g., "local", "mcp", "skill"). Reserved for future MCP/Skills. */
  val namespace: String
  val description: String
  val schema: ToolSchema
  val kind: ToolKind
  /** True for read-only tools; enables future parallel dispatch. Reserved for Phase 3+. */
  val readOnlyHint: Boolean
  val timeoutSec: Long
  val confirmPolicy: ConfirmPolicy
  /** Visibility in tool listings; HIDDEN reserved for deferred/lazy loading. */
  val visible: Boolean

  suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult
}
