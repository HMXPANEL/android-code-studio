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

import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.ToolExecutor
import com.tom.rv2ide.artificial.tools.ToolPermission
import com.tom.rv2ide.artificial.tools.ToolRegistry

/**
 * Owns Phase 1 agent runs: step budget, cancellation, transcript, and the
 * model → tool → result loop (loop body lands in Step 5; this file establishes
 * construction, run ownership, and the event channel).
 *
 * Depends on [AIAgentManager] for provider calls (unchanged behavior) and on
 * the tool runtime for everything else. One active run at a time per
 * controller; a new user message cancels the previous run first.
 */
class AgentController(
    private val manager: AIAgentManager,
    private val registry: ToolRegistry = ToolRegistry,
    private val executor: ToolExecutor = ToolExecutor(),
    private val callSource: ToolCallSource = TextProtocolSource(),
    private val permissionFor: (RunMode) -> ToolPermission = { mode ->
      ToolPermission.buildDefault(planMode = mode == RunMode.PLAN)
    }
) {

  @Volatile
  var activeRun: AgentRun? = null
    private set

  /**
   * Starts tracking [run] as the active run, cancelling any previous one.
   * Returns the run for chaining.
   */
  fun trackRun(run: AgentRun): AgentRun {
    cancelActiveRun()
    activeRun = run
    return run
  }

  /** Cancels the active run (if any) and clears it. Safe to call repeatedly. */
  fun cancelActiveRun() {
    val run = activeRun
    activeRun = null
    run?.cancel()
  }

  fun currentModePermission(mode: RunMode): ToolPermission = permissionFor(mode)
}
