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

import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.ToolCall
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** One transcript line inside a run. */
enum class EntryRole {
  USER,
  THOUGHT,
  TOOL_CALL,
  TOOL_RESULT,
  FINAL,
  SYSTEM
}

data class RunEntry(val role: EntryRole, val text: String)

/** Step/retry/doom-loop budgets for one run. */
data class RunBudget(
    val maxSteps: Int = 15,
    val maxRetries: Int = 2,
    val doomRepeat: Int = 3
)

/**
 * One agent run: in-memory only, non-resumable, cancellable via [job].
 * A mode snapshot is taken at run start; later UI mode switches affect
 * only new runs.
 */
class AgentRun(
    val runId: String = UUID.randomUUID().toString(),
    val mode: RunMode,
    val budget: RunBudget = RunBudget(),
    parentJob: Job? = null
) {

  val job: Job = SupervisorJob(parentJob)

  @Volatile
  var state: AgentState = AgentState.IDLE
    private set

  var stepCount: Int = 0
    private set
  var retryCount: Int = 0
    private set
  var consecutiveFailures: Int = 0
    private set

  var pendingApproval: ToolCall? = null

  val startedAt: Long = System.currentTimeMillis()
  var updatedAt: Long = startedAt
    private set

  private val recentFingerprints = ArrayDeque<String>()
  private val _transcript = mutableListOf<RunEntry>()

  val transcript: List<RunEntry> get() = _transcript.toList()

  fun transitionTo(next: AgentState): Boolean {
    if (!AgentState.canTransition(state, next)) {
      return false
    }
    state = next
    updatedAt = System.currentTimeMillis()
    return true
  }

  fun addEntry(role: EntryRole, text: String, maxEntries: Int = 40) {
    _transcript.add(RunEntry(role, text))
    if (_transcript.size > maxEntries) {
      val drop = _transcript.size - maxEntries
      repeat(drop) { _transcript.removeAt(0) }
      _transcript.add(
          0, RunEntry(EntryRole.SYSTEM, "…[older run history trimmed]")
      )
    }
    updatedAt = System.currentTimeMillis()
  }

  fun registerStep(fingerprint: String?): StepVerdict {
    stepCount++
    if (stepCount > budget.maxSteps) {
      return StepVerdict.BUDGET_EXHAUSTED
    }
    if (fingerprint != null) {
      recentFingerprints.addLast(fingerprint)
      while (recentFingerprints.size > budget.doomRepeat) {
        recentFingerprints.removeFirst()
      }
      if (recentFingerprints.size == budget.doomRepeat &&
          recentFingerprints.all { it == fingerprint }
      ) {
        return StepVerdict.DOOM_LOOP
      }
    }
    return StepVerdict.OK
  }

  fun registerSuccess() {
    consecutiveFailures = 0
    retryCount = 0
  }

  /** Returns true when failures hit the retry budget (caller should re-plan/stop). */
  fun registerFailure(): Boolean {
    consecutiveFailures++
    retryCount++
    return consecutiveFailures >= 2 || retryCount > budget.maxRetries
  }

  fun cancel() {
    transitionTo(AgentState.CANCELLED)
    job.cancel()
  }

  enum class StepVerdict {
    OK,
    BUDGET_EXHAUSTED,
    DOOM_LOOP
  }
}
