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

/**
 * Agent run states. Pure Kotlin, JVM-testable transition table.
 */
enum class AgentState {
  IDLE,
  THINKING,
  PROPOSING_TOOLS,
  AWAITING_USER,
  EXECUTING,
  OBSERVING,
  FINALIZING,
  DONE,
  FAILED,
  CANCELLED;

  companion object {
    private val allowed: Map<AgentState, Set<AgentState>> = mapOf(
        IDLE to setOf(THINKING, CANCELLED),
        THINKING to setOf(PROPOSING_TOOLS, FAILED, CANCELLED),
        PROPOSING_TOOLS to setOf(EXECUTING, FINALIZING, FAILED, CANCELLED),
        AWAITING_USER to setOf(EXECUTING, OBSERVING, CANCELLED),
        EXECUTING to setOf(AWAITING_USER, OBSERVING, FAILED, CANCELLED),
        OBSERVING to setOf(THINKING, FINALIZING, FAILED, CANCELLED),
        FINALIZING to setOf(DONE, FAILED, CANCELLED),
        DONE to emptySet(),
        FAILED to emptySet(),
        CANCELLED to emptySet()
    )

    fun canTransition(from: AgentState, to: AgentState): Boolean {
      if (to == CANCELLED && (from != DONE && from != FAILED && from != CANCELLED)) {
        return true
      }
      return allowed[from]?.contains(to) == true
    }
  }
}
