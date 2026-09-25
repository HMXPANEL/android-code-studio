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
import com.tom.rv2ide.artificial.parser.SnippetParser
import com.tom.rv2ide.artificial.tools.ConfirmPolicy
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolDecision
import com.tom.rv2ide.artificial.tools.ToolExecutor
import com.tom.rv2ide.artificial.tools.ToolPermission
import com.tom.rv2ide.artificial.tools.ToolRegistry
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.builtins.BoundedWalk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Owns Phase 1 agent runs: step budget, cancellation, transcript, and the
 * model → tool → result loop.
 *
 * Depends on [AIAgentManager] for provider calls (unchanged behavior) and on
 * the tool runtime for everything else. One active run at a time per
 * controller; a new user message cancels the previous run first.
 * Tool execution is strictly sequential in Phase 1.
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

  /**
   * Runs one full agent turn for [userRequest].
   *
   * @param mode BUILD or PLAN snapshot for this run.
   * @param projectRoot absolute project directory for tool path jailing.
   * @param events sink receiving [AgentEvents] (UI maps these to chat updates).
   * @param onConfirm invoked for confirm-gated tools; true grants, false denies.
   * @param legacyFallback when true (default), legacy `FILE_TO_MODIFY:` replies
   *   are converted into equivalent `local:write_file` calls through the same
   *   safe pipeline instead of the old direct path.
   */
  suspend fun runAgent(
      userRequest: String,
      mode: RunMode,
      projectRoot: File,
      events: (AgentEvents) -> Unit,
      onConfirm: suspend (ToolCall) -> Boolean,
      legacyFallback: Boolean = true
  ): AgentRun {
    // Parent the run job to the caller: UI cancellation then cancels the run
    // automatically, while run.cancel() stays local (SupervisorJob).
    val run = trackRun(AgentRun(mode = mode, parentJob = coroutineContext[Job]))
    val permission = permissionFor(mode)
    val planMode = mode == RunMode.PLAN
    val snippetParser = SnippetParser()
    val nudge = NudgeState()
    try {
      run.transitionTo(AgentState.THINKING)
      run.addEntry(EntryRole.USER, userRequest)
      events(AgentEvents.Thinking("Analyzing your request…"))

      while (true) {
        run.job.ensureActive()
        val prompt = buildPrompt(
            userRequest = userRequest,
            run = run,
            permission = permission,
            planMode = planMode,
            projectRoot = projectRoot
        )
        events(AgentEvents.Thinking("Thinking (step ${run.stepCount + 1})…"))

        val agent = manager.getCurrentAgent()
        if (agent == null) {
          return failRun(run, events, "No AI provider is configured. Set an API key first.")
        }
        val reply: String = try {
          withContext(run.job) {
            agent.generateCode(
                prompt = prompt,
                context = null,
                language = "kotlin",
                projectStructure = null
            )
          }.getOrElse { error ->
            return failRun(
                run, events,
                "AI request failed: ${error.message ?: "unknown error"}"
            )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return failRun(run, events, "AI request failed: ${e.message}")
        }

        run.addEntry(EntryRole.THOUGHT, reply.take(MAX_THOUGHT_CHARS))
        events(AgentEvents.ModelReply(reply))

        val parsed = try {
          (callSource as? TextProtocolSource)?.parseFull(reply, run.runId)
              ?: ParsedReply(
                  calls = callSource.extractCalls(reply, run.runId),
                  errors = callSource.extractErrors(reply, run.runId),
                  hasLegacyModifications = false,
                  legacyFiles = emptyList()
              )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return failRun(run, events, "Reply parsing failed: ${e.message}")
        }

        // Malformed tool blocks: guided retry, counted against the budget.
        if (parsed.calls.isEmpty() && parsed.errors.isNotEmpty()) {
          val detail = parsed.errors.joinToString("; ") { it.message }
          run.addEntry(EntryRole.SYSTEM, "Parse error: $detail")
          if (registerFailureOrStop(run, events, "I could not parse a tool call ($detail). ", nudge)) {
            return run
          }
          events(AgentEvents.Retrying(run.retryCount, "Fixing tool call format…"))
          continue
        }

        // No tool calls: final answer (or legacy fallback conversion).
        if (parsed.calls.isEmpty()) {
          if (legacyFallback && parsed.hasLegacyModifications) {
            val converted = convertLegacy(
                parsed = parsed,
                run = run,
                snippetParser = snippetParser
            )
            if (converted.isNotEmpty()) {
              val verdict = executeOneCall(
                  call = converted.first(),
                  run = run,
                  permission = permission,
                  planMode = planMode,
                  projectRoot = projectRoot,
                  events = events,
                  onConfirm = onConfirm,
                  ignoredExtra = converted.size - 1,
                  nudge = nudge
              )
              if (verdict == LoopAction.STOP) return run
              continue
            }
          }
          run.transitionTo(AgentState.FINALIZING)
          run.addEntry(EntryRole.FINAL, reply.take(MAX_FINAL_CHARS))
          events(AgentEvents.FinalAnswer(reply, parsed.hasLegacyModifications))
          run.transitionTo(AgentState.DONE)
          clearRun(run)
          return run
        }

        // Deterministic: execute the first call; note any extras as ignored.
        val call = parsed.calls.first()
        val verdict = executeOneCall(
            call = call,
            run = run,
            permission = permission,
            planMode = planMode,
            projectRoot = projectRoot,
            events = events,
            onConfirm = onConfirm,
            ignoredExtra = parsed.calls.size - 1,
            nudge = nudge
        )
        if (verdict == LoopAction.STOP) return run
      }
    } catch (e: CancellationException) {
      run.transitionTo(AgentState.CANCELLED)
      events(AgentEvents.Cancelled(run.stepCount))
      clearRun(run)
      throw e
    }
  }

  private enum class LoopAction {
    CONTINUE,
    STOP
  }

  private suspend fun executeOneCall(
      call: ToolCall,
      run: AgentRun,
      permission: ToolPermission,
      planMode: Boolean,
      projectRoot: File,
      events: (AgentEvents) -> Unit,
      onConfirm: suspend (ToolCall) -> Boolean,
      ignoredExtra: Int,
      nudge: NudgeState
  ): LoopAction {
    run.transitionTo(AgentState.PROPOSING_TOOLS)
    events(AgentEvents.ToolsProposed(listOf(call)))
    if (ignoredExtra > 0) {
      run.addEntry(
          EntryRole.SYSTEM,
          "Note: $ignoredExtra extra tool call(s) in the same reply were skipped; one call per turn."
      )
    }

    when (run.registerStep(call.fingerprint())) {
      AgentRun.StepVerdict.BUDGET_EXHAUSTED -> {
        run.transitionTo(AgentState.FINALIZING)
        val text = "Stopped after ${run.budget.maxSteps} steps without a final answer. " +
            "Partial progress is preserved above; try a smaller request."
        run.addEntry(EntryRole.FINAL, text)
        events(AgentEvents.FinalAnswer(text, false))
        run.transitionTo(AgentState.DONE)
        clearRun(run)
        return LoopAction.STOP
      }
      AgentRun.StepVerdict.DOOM_LOOP -> {
        run.transitionTo(AgentState.FINALIZING)
        val text = "Stopped: '${call.name}' was repeated with identical arguments " +
            "${run.budget.doomRepeat} times. Please rephrase or narrow the request."
        run.addEntry(EntryRole.FINAL, text)
        events(AgentEvents.FinalAnswer(text, false))
        run.transitionTo(AgentState.DONE)
        clearRun(run)
        return LoopAction.STOP
      }
      AgentRun.StepVerdict.OK -> { /* proceed */ }
    }

    run.transitionTo(AgentState.EXECUTING)
    events(AgentEvents.ToolStarted(call))

    val tool = registry.lookup(call.name)
    val needsConfirm = tool != null &&
        (permission.decide(tool) == ToolDecision.ASK ||
            tool.confirmPolicy == ConfirmPolicy.ALWAYS)
    if (needsConfirm) {
      run.transitionTo(AgentState.AWAITING_USER)
      run.pendingApproval = call
      events(AgentEvents.ApprovalRequired(call, "Tool '${call.name}' needs approval."))
    }

    val ctx = ToolContext(
        projectRoot = projectRoot,
        planMode = planMode,
        runId = run.runId,
        job = run.job,
        stepIndex = run.stepCount,
        onFileModified = { path, previous, new, success ->
          if (success) {
            run.trackChange(path)
            try {
              manager.getCurrentAgent()?.recordModification(path, previous, new, true)
            } catch (e: Exception) {
              // History bookkeeping must never break a run.
            }
          }
        }
    )

    val result: ToolResult = try {
      executor.execute(call, ctx, permission) { pending ->
        events(AgentEvents.ApprovalRequired(pending, "Confirm '${pending.name}'."))
        onConfirm(pending)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Executor contract says this never happens, but never trust it.
      ToolResult.failure("Execution failed: ${e.message}")
    } finally {
      run.pendingApproval = null
      if (run.state == AgentState.AWAITING_USER) {
        run.transitionTo(AgentState.EXECUTING)
      }
    }

    run.transitionTo(AgentState.OBSERVING)
    events(AgentEvents.ToolFinished(call, result))
    run.addEntry(
        EntryRole.TOOL_RESULT,
        "TOOL ${call.name}: " + if (result.ok) {
          result.text.take(MAX_RESULT_CHARS)
        } else {
          "FAILED: ${result.error}"
        }
    )
    if (result.ok) {
      run.registerSuccess()
    } else {
      if (registerFailureOrStop(run, events, "Tool '${call.name}' failed (${result.error}). ", nudge)) {
        return LoopAction.STOP
      }
      events(AgentEvents.Retrying(run.retryCount, "Retrying with the error in context…"))
    }
    return LoopAction.CONTINUE
  }

  /** Per-run single re-plan nudge before giving up on repeated failures. */
  private class NudgeState(var armed: Boolean = true)

  /**
   * Registers a failure; on budget exhaustion emits a re-plan nudge the first
   * time (returns false = keep going once) and stops the run the next time
   * (returns true).
   */
  private fun registerFailureOrStop(
      run: AgentRun,
      events: (AgentEvents) -> Unit,
      prefix: String,
      nudge: NudgeState
  ): Boolean {
    val stop = run.registerFailure()
    if (!stop) {
      return false
    }
    if (nudge.armed) {
      nudge.armed = false
      run.addEntry(
          EntryRole.SYSTEM,
          prefix + "Re-plan with a different approach (one more attempt)."
      )
      return false
    }
    run.transitionTo(AgentState.FINALIZING)
    val text = prefix + "Stopping after repeated failures. " +
        "Changed files this run: " +
        (run.changedFiles.ifEmpty { listOf("none") }.joinToString(", "))
    run.addEntry(EntryRole.FINAL, text)
    events(AgentEvents.FinalAnswer(text, false))
    run.transitionTo(AgentState.DONE)
    clearRun(run)
    return true
  }

  private fun convertLegacy(
      parsed: ParsedReply,
      run: AgentRun,
      snippetParser: SnippetParser
  ): List<ToolCall> {
    if (run.mode == com.tom.rv2ide.artificial.tools.RunMode.PLAN) {
      run.addEntry(
          EntryRole.SYSTEM,
          "Legacy file modifications refused: PLAN mode cannot modify files."
      )
      return emptyList()
    }
    return parsed.legacyFiles.mapIndexed { index, block ->
      val cleaned = try {
        snippetParser.cleanFileContent(block.content.trim())
      } catch (e: Exception) {
        block.content.trim()
      }
      ToolCall(
          name = "local:write_file",
          args = mapOf("path" to block.path, "content" to cleaned),
          callId = "call-${run.runId}-legacy-$index",
          runId = run.runId,
          legacy = true
      )
    }
  }

  private fun failRun(
      run: AgentRun,
      events: (AgentEvents) -> Unit,
      reason: String
  ): AgentRun {
    run.transitionTo(AgentState.FINALIZING)
    run.addEntry(EntryRole.FINAL, reason)
    events(
        AgentEvents.Failed(
            reason = reason,
            changedFiles = run.changedFiles.toList()
        )
    )
    run.transitionTo(AgentState.FAILED)
    clearRun(run)
    return run
  }

  private fun clearRun(run: AgentRun) {
    if (activeRun === run) {
      activeRun = null
    }
  }

  internal suspend fun buildPrompt(
      userRequest: String,
      run: AgentRun,
      permission: ToolPermission,
      planMode: Boolean,
      projectRoot: File
  ): String {
    val builder = StringBuilder()
    builder.append("You are a coding assistant inside an Android IDE. ")
    builder.append("You can call tools with strict TOOL_CALL: blocks.\n")
    builder.append("Format: a line `TOOL_CALL: <tool-id>` followed by a JSON object, e.g.\n")
    builder.append("TOOL_CALL: local:read_file {\"path\": \"app/src/Main.kt\"}\n")
    builder.append("Rules: exactly one tool call per reply; reply with plain text only " +
        "when you have the final answer; never invent tool names.\n")
    builder.append("For local:edit_file, always pass the sha256 hash from the " +
        "matching local:read_file result as expectedHash.\n")
    if (planMode) {
      builder.append("PLAN MODE: investigate only and return a numbered plan. " +
          "Do not modify, create, or delete files.\n")
    } else {
      builder.append("BUILD MODE: you may inspect and modify project files and run builds.\n")
    }
    builder.append("\n").append(registry.describeForPrompt(planMode)).append("\n")
    builder.append("\nProject tree (truncated):\n")
    builder.append(renderTree(projectRoot, run.job)).append("\n")
    builder.append("\nConversation so far:\n")
    val tail = run.transcript.takeLast(MAX_TRANSCRIPT_TURNS * 2)
    tail.forEach { entry ->
      val label = when (entry.role) {
        EntryRole.USER -> "USER"
        EntryRole.THOUGHT -> "ASSISTANT"
        EntryRole.TOOL_CALL -> "TOOL_CALL"
        EntryRole.TOOL_RESULT -> "TOOL_RESULT"
        EntryRole.FINAL -> "ASSISTANT(final)"
        EntryRole.SYSTEM -> "SYSTEM"
      }
      builder.append("[$label] ").append(entry.text.take(MAX_ENTRY_CHARS)).append("\n")
    }
    // The original request stays anchored so long runs do not drift.
    builder.append("\nCurrent user request: ").append(userRequest.take(2000)).append("\n")
    var prompt = builder.toString()
    if (prompt.length > MAX_PROMPT_CHARS) {
      prompt = prompt.take(MAX_PROMPT_CHARS) +
          "\n…[prompt trimmed to $MAX_PROMPT_CHARS chars]"
    }
    return prompt
  }

  internal suspend fun renderTree(projectRoot: File, runJob: Job?): String {
    return try {
      val entries = BoundedWalk.list(
          root = projectRoot,
          maxDepth = 4,
          maxEntries = 100,
          runJob = runJob
      ).filter { !it.truncated }
      if (entries.isEmpty()) {
        "(empty project)"
      } else {
        entries.joinToString("\n") { entry ->
          val rel = try {
            entry.file.relativeTo(projectRoot).path
          } catch (e: Exception) {
            entry.file.name
          }
          val indent = "  ".repeat((entry.depth - 1).coerceAtLeast(0))
          if (entry.file.isDirectory) "$indent$rel/" else "$indent$rel"
        }
      }
    } catch (e: Exception) {
      "(project tree unavailable: ${e.message})"
    }
  }

  companion object {
    internal const val MAX_THOUGHT_CHARS = 4000
    internal const val MAX_FINAL_CHARS = 12000
    internal const val MAX_RESULT_CHARS = 4000
    internal const val MAX_ENTRY_CHARS = 2000
    internal const val MAX_TRANSCRIPT_TURNS = 10
    internal const val MAX_PROMPT_CHARS = 24000
  }
}
