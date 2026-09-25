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

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.tools.ToolCall

/**
 * Converts one model reply into normalized [ToolCall] events.
 *
 * The controller depends ONLY on this interface — never on a parser — so a
 * future `NativeFunctionCallSource` can replace text parsing without touching
 * the loop, registry, executor, tools, or UI.
 */
interface ToolCallSource {

  /** Zero calls means "final answer": the reply text answers the user. */
  fun extractCalls(reply: String, runId: String): List<ToolCall>

  /** Parse diagnostics to feed back to the model as a guided retry. */
  fun extractErrors(reply: String, runId: String): List<ParseError>
}

/**
 * Phase 1 source: strict `TOOL_CALL:` text blocks via [ToolCallParser].
 * Uniform across all providers; no SDK changes required.
 */
class TextProtocolSource : ToolCallSource {

  override fun extractCalls(reply: String, runId: String): List<ToolCall> {
    return ToolCallParser.parse(reply, runId).calls
  }

  override fun extractErrors(reply: String, runId: String): List<ParseError> {
    return ToolCallParser.parse(reply, runId).errors
  }

  fun parseFull(reply: String, runId: String): ParsedReply {
    return ToolCallParser.parse(reply, runId)
  }
}
