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
import com.tom.rv2ide.artificial.tools.ToolResult

/**
 * Events emitted by [AgentController]. The UI layer maps these onto the
 * existing `AIAgentManager.AIAgentCallback` (thinking→onProcessing,
 * tool events→file/activity rows, final→onSuccess/onTextResponse,
 * failed→onError) plus two additive UI hooks: tool proposals and approvals.
 */
sealed class AgentEvents {

  data class Thinking(val status: String) : AgentEvents()

  data class ToolsProposed(val calls: List<ToolCall>) : AgentEvents()

  data class ApprovalRequired(val call: ToolCall, val reason: String) : AgentEvents()

  data class ToolStarted(val call: ToolCall) : AgentEvents()

  data class ToolFinished(val call: ToolCall, val result: ToolResult) : AgentEvents()

  data class ModelReply(val text: String) : AgentEvents()

  data class FinalAnswer(val text: String, val legacyModifications: Boolean) : AgentEvents()

  data class Failed(val reason: String, val changedFiles: List<String>) : AgentEvents()

  data class Cancelled(val partialSteps: Int) : AgentEvents()

  data class Retrying(val attempt: Int, val message: String) : AgentEvents()
}
