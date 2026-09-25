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
 * allow/ask/deny permission model with PLAN/BUILD presets.
 *
 * Pure Kotlin, JVM-testable. Deny always wins; explicit rules beat defaults.
 */
enum class ToolDecision {
  ALLOW,
  ASK,
  DENY
}

/** One pattern rule. Pattern is an exact tool id or "*" (matches everything). */
data class PermissionRule(val pattern: String, val decision: ToolDecision) {

  fun matches(toolId: String): Boolean = pattern == "*" || pattern == toolId
}

class ToolPermission(
    val rules: List<PermissionRule> = emptyList(),
    val planMode: Boolean = false
) {

  /**
   * Decides a tool call. Order: PLAN gate first (only READ survives PLAN),
   * then explicit rules (any DENY wins, else any ASK wins), then kind defaults.
   */
  fun decide(tool: Tool): ToolDecision {
    if (planMode && tool.kind != ToolKind.READ) {
      return ToolDecision.DENY
    }
    val matching = rules.filter { it.matches(tool.id) }
    if (matching.any { it.decision == ToolDecision.DENY }) {
      return ToolDecision.DENY
    }
    if (matching.any { it.decision == ToolDecision.ASK }) {
      return ToolDecision.ASK
    }
    return when (tool.kind) {
      ToolKind.READ -> ToolDecision.ALLOW
      ToolKind.WRITE -> ToolDecision.ALLOW
      ToolKind.DESTRUCTIVE -> ToolDecision.ASK
      ToolKind.POWER -> ToolDecision.ASK
    }
  }

  companion object {
    fun buildDefault(planMode: Boolean): ToolPermission =
        ToolPermission(rules = emptyList(), planMode = planMode)
  }
}
