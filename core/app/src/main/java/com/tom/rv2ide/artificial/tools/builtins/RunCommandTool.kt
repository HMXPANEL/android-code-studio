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

package com.tom.rv2ide.artificial.tools.builtins

import com.tom.rv2ide.artificial.tools.ConfirmPolicy
import com.tom.rv2ide.artificial.tools.Tool
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolInputField
import com.tom.rv2ide.artificial.tools.ToolInputType
import com.tom.rv2ide.artificial.tools.ToolKind
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.ToolSchema
import com.tom.rv2ide.artificial.tools.ToolVisibility
import com.tom.rv2ide.artificial.tools.Truncate
import com.tom.rv2ide.shell.executeProcessAsync
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Pure command gate: block-list + timeout/output constants. JVM-testable. */
object CommandGate {
  const val DEFAULT_TIMEOUT_SEC = 60L
  const val MAX_OUTPUT_LINES = 100

  private val blockedPrefixes = listOf(
      "sudo", "su ", "su\t", "mkfs", "dd ", "dd\t", "shutdown", "reboot",
      "fastboot", ":(){", "chmod -r /", "chown -r /", "adb shell rm"
  )

  fun rejectionReason(command: String): String? {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) {
      return "Command must not be empty."
    }
    val lower = trimmed.lowercase()
    if (lower.contains("rm -rf /") || lower.contains("rm -rf /*")) {
      return "Recursive delete of filesystem roots is forbidden."
    }
    blockedPrefixes.forEach { prefix ->
      if (lower == prefix.trimEnd() || lower.startsWith(prefix)) {
        return "Command prefix '$prefix' is forbidden."
      }
    }
    return null
  }
}

/**
 * local:run_command — headless shell execution. POWER, always confirms,
 * project-root cwd, timeout + kill, capped output. No secrets are added to
 * the environment; API keys live in SharedPreferences, never in env.
 */
class RunCommandTool : Tool {
  override val id = "local:run_command"
  override val namespace = "local"
  override val description =
      "Run a shell command in the project directory. Output is capped; long-running commands time out."
  override val schema = ToolSchema(
      listOf(
          ToolInputField("command", ToolInputType.STRING, "Shell command to run", true),
          ToolInputField("timeoutSec", ToolInputType.NUMBER, "Timeout seconds (max 300)", false, 60)
      )
  )
  override val kind = ToolKind.POWER
  override val readOnlyHint = false
  override val timeoutSec = 300L
  override val confirmPolicy = ConfirmPolicy.ALWAYS
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    val command = args["command"] as? String
        ?: return ToolResult.failure("Missing required argument 'command'.")
    val timeoutSec = ((args["timeoutSec"] as? Number)?.toLong()
        ?: CommandGate.DEFAULT_TIMEOUT_SEC).coerceIn(1L, 300L)
    CommandGate.rejectionReason(command)?.let { reason ->
      return ToolResult.failure("Command refused: $reason")
    }
    return try {
      withContext(Dispatchers.IO) {
        runProcess(command, ctx, timeoutSec)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ToolResult.failure("Command failed to start: ${e.message}")
    }
  }

  private suspend fun runProcess(command: String, ctx: ToolContext, timeoutSec: Long): ToolResult {
    val process = try {
      executeProcessAsync {
        this.command = listOf("sh", "-c", command)
        this.workingDirectory = ctx.projectRoot
        this.redirectErrorStream = true
      }
    } catch (e: Exception) {
      return ToolResult.failure("Command failed to start: ${e.message}")
    }
    // Drain output on a side thread so a chatty process can never deadlock us.
    val lines = ArrayList<String>(CommandGate.MAX_OUTPUT_LINES + 1)
    var dropped = 0
    val drain = Thread({
      try {
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
          var line = reader.readLine()
          while (line != null) {
            synchronized(lines) {
              if (lines.size < CommandGate.MAX_OUTPUT_LINES) {
                lines.add(line)
              } else {
                dropped++
              }
            }
            line = reader.readLine()
          }
        }
      } catch (e: Exception) {
        // Stream closed by destroy(); finishing is what matters.
      }
    }, "agent-run-command-drain")
    drain.isDaemon = true
    drain.start()
    try {
      // Slice the wait so coroutine cancellation is honored promptly: a
      // blocking waitFor alone would ignore cancel until process exit.
      val deadline = System.currentTimeMillis() + timeoutSec * 1000L
      var finished = false
      while (System.currentTimeMillis() < deadline) {
        currentCoroutineContext().ensureActive()
        ctx.job.ensureActive()
        if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
          finished = true
          break
        }
      }
      if (!finished) {
        process.destroyForcibly()
        drain.join(2000)
        return ToolResult.failure(
            "Command timed out after ${timeoutSec}s and was killed. " +
                "Partial output:\n${snapshot(lines)}"
        )
      }
      drain.join(5000)
      val exit = process.exitValue()
      val kept = snapshot(lines)
      val tail = if (dropped > 0) {
        "\n…[output capped at ${CommandGate.MAX_OUTPUT_LINES} lines, $dropped dropped]"
      } else {
        ""
      }
      return if (exit == 0) {
        ToolResult.success(
            if (kept.isBlank()) "(exit 0, no output)" else kept + tail,
            stats = mapOf("exit" to "0", "droppedLines" to dropped.toString()),
            truncated = dropped > 0
        )
      } else {
        ToolResult.failure(
            "Command exited with code $exit.\n$kept$tail",
            stats = mapOf("exit" to exit.toString())
        )
      }
    } finally {
      if (process.isAlive) {
        process.destroyForcibly()
      }
      try {
        drain.join(2000)
      } catch (e: Exception) {
        // Best effort only.
      }
    }
  }

  private fun snapshot(lines: List<String>): String {
    return synchronized(lines) { lines.toList() }.joinToString("\n").let {
      Truncate.chars(it, 8000, "command output").text
    }
  }
}
