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
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * local:build_project — Gradle builds through the existing [BuildService].
 * POWER. Never shells out to Gradle directly; honors the single-build lock
 * and supports cancellation via `cancelCurrentBuild()`. Calling the tool is
 * the user's intent, so no extra confirmation is required.
 */
class BuildProjectTool(
    private val serviceLookup: () -> BuildService? = {
      Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? BuildService
    }
) : Tool {
  override val id = "local:build_project"
  override val namespace = "local"
  override val description =
      "Run Gradle tasks (default assembleDebug) and report success or failure."
  override val schema = ToolSchema(
      listOf(
          ToolInputField(
              "tasks",
              ToolInputType.STRING,
              "Comma-separated Gradle tasks",
              false,
              "assembleDebug"
          ),
          ToolInputField("timeoutSec", ToolInputType.NUMBER, "Timeout seconds (max 600)", false, 300)
      )
  )
  override val kind = ToolKind.POWER
  override val readOnlyHint = false
  override val timeoutSec = 600L
  override val confirmPolicy = ConfirmPolicy.NEVER
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    val tasksRaw = (args["tasks"] as? String)?.ifBlank { "assembleDebug" } ?: "assembleDebug"
    val tasks = tasksRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (tasks.isEmpty()) {
      return ToolResult.failure("No Gradle tasks given.")
    }
    if (tasks.any { it.contains("..") || it.contains("/") || it.contains("\\") }) {
      return ToolResult.failure("Task names must be plain Gradle task paths.")
    }
    val timeoutSec = ((args["timeoutSec"] as? Number)?.toLong() ?: 300L).coerceIn(30L, 600L)

    val service = try {
      serviceLookup()
    } catch (e: Exception) {
      return ToolResult.failure("Build service unavailable: ${e.message}")
    } ?: return ToolResult.failure(
        "Build service is not running. Open the project in the IDE and try again."
    )

    if (service.isBuildInProgress) {
      return ToolResult.failure(
          "Another build is already in progress. Wait for it to finish or cancel it first."
      )
    }

    val future = try {
      service.executeTasks(*tasks.toTypedArray())
    } catch (e: Exception) {
      return ToolResult.failure("Could not start build ${tasks.joinToString(",")}: ${e.message}")
    }

    return try {
      pollForResult(service, future, tasks, timeoutSec)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      try {
        service.cancelCurrentBuild()
      } catch (cancelError: Exception) {
        // Best effort.
      }
      throw kotlinx.coroutines.CancellationException("Build interrupted.")
    } catch (e: CancellationException) {
      try {
        service.cancelCurrentBuild()
      } catch (cancelError: Exception) {
        // Best effort.
      }
      throw kotlinx.coroutines.CancellationException("Build cancelled.")
    } catch (e: ExecutionException) {
      ToolResult.failure("Build failed to execute: ${e.cause?.message ?: e.message}")
    } catch (e: Exception) {
      ToolResult.failure("Build error: ${e.message}")
    }
  }

  /**
   * Polls the build future in short slices so coroutine cancellation is
   * honored promptly (a single blocking get() would ignore cancel until the
   * build finishes). Deadline expiry requests cancellation and reports timeout.
   */
  private suspend fun pollForResult(
      service: BuildService,
      future: java.util.concurrent.Future<com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult?>,
      tasks: List<String>,
      timeoutSec: Long
  ): ToolResult {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000L
    while (true) {
      try {
        val result = future.get(500, TimeUnit.MILLISECONDS)
        if (result == null) {
          return ToolResult.failure("Build returned no result for ${tasks.joinToString(",")}.")
        }
        if (result.isSuccessful) {
          return ToolResult.success(
              "Build succeeded: ${tasks.joinToString(",")}.",
              stats = mapOf("tasks" to tasks.joinToString(","))
          )
        }
        return ToolResult.failure(
            "Build failed: ${tasks.joinToString(",")} " +
                "(reason: ${result.failure?.name ?: "unknown"}). " +
                "Read the relevant files and try a fix.",
            stats = mapOf(
                "tasks" to tasks.joinToString(","),
                "failure" to (result.failure?.name ?: "unknown")
            )
        )
      } catch (e: TimeoutException) {
        kotlinx.coroutines.ensureActive()
        if (System.currentTimeMillis() >= deadline) {
          try {
            service.cancelCurrentBuild()
          } catch (cancelError: Exception) {
            // Best effort; the timeout result below is what matters.
          }
          return ToolResult.failure(
              "Build timed out after ${timeoutSec}s and a cancellation was requested."
          )
        }
      }
    }
  }
}
