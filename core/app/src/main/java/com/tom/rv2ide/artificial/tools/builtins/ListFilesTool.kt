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
import com.tom.rv2ide.artificial.tools.PathJail
import com.tom.rv2ide.artificial.tools.Tool
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolInputField
import com.tom.rv2ide.artificial.tools.ToolInputType
import com.tom.rv2ide.artificial.tools.ToolKind
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.ToolSchema
import com.tom.rv2ide.artificial.tools.ToolVisibility
import com.tom.rv2ide.artificial.tools.Truncate
import java.io.File
import kotlinx.coroutines.ensureActive

/** Directories never descended into by agent tools. */
internal val AGENT_SKIP_DIRS = setOf(
    "build", ".gradle", ".git", ".cxx", ".externalNativeBuild",
    "node_modules", ".idea", ".vscode", "captures"
)

/** Shared bounded walker for read tools. Self-contained on java.io. */
internal object BoundedWalk {

  data class Entry(val file: File, val depth: Int, val truncated: Boolean)

  suspend fun list(
      root: File,
      maxDepth: Int,
      maxEntries: Int,
      includeFiles: Boolean = true,
      includeDirs: Boolean = true,
      extensionFilter: Set<String>? = null
  ): List<Entry> {
    val out = mutableListOf<Entry>()
    var truncated = false
    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
    fun walk(dir: File, depth: Int): Boolean {
      // Cooperative cancellation so large trees never hang a run.
      job?.ensureActive()
      val children = try {
        dir.listFiles()?.sortedBy { it.name } ?: return true
      } catch (e: Exception) {
        return true
      }
      for (child in children) {
        if (out.size >= maxEntries) {
          truncated = true
          return false
        }
        val name = child.name
        if (child.isDirectory) {
          if (name in AGENT_SKIP_DIRS) continue
          if (depth + 1 > maxDepth) continue
          if (includeDirs) out.add(Entry(child, depth + 1, false))
          if (out.size < maxEntries) {
            if (!walk(child, depth + 1)) return false
          } else {
            truncated = true
            return false
          }
        } else if (includeFiles) {
          if (extensionFilter != null) {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in extensionFilter) continue
          }
          out.add(Entry(child, depth + 1, false))
        }
      }
      return true
    }
    // Cooperative cancellation for large trees is handled per directory
    // inside walk() via the coroutine job above.
    walk(root, 0)
    return if (truncated) out + Entry(root, -1, true) else out
  }
}

/** local:list_files — bounded project listing. READ, never confirms. */
class ListFilesTool : Tool {
  override val id = "local:list_files"
  override val namespace = "local"
  override val description =
      "List files and folders under a project directory. Use to explore structure before reading."
  override val schema = ToolSchema(
      listOf(
          ToolInputField("dir", ToolInputType.STRING, "Directory relative to project root", false, "."),
          ToolInputField("maxEntries", ToolInputType.NUMBER, "Max entries to return", false, 200),
          ToolInputField("depth", ToolInputType.NUMBER, "Max descent depth", false, 6)
      )
  )
  override val kind = ToolKind.READ
  override val readOnlyHint = true
  override val timeoutSec = 30L
  override val confirmPolicy = ConfirmPolicy.NEVER
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    val dirArg = (args["dir"] as? String)?.ifBlank { "." } ?: "."
    val maxEntries = ((args["maxEntries"] as? Number)?.toInt() ?: 200).coerceIn(1, 200)
    val depth = ((args["depth"] as? Number)?.toInt() ?: 6).coerceIn(0, 6)
    val base = PathJail.resolveInside(ctx.projectRoot, dirArg)
        ?: return ToolResult.failure("Directory '$dirArg' is outside the project root.")
    if (!base.exists()) {
      return ToolResult.failure("Directory '$dirArg' does not exist.")
    }
    if (!base.isDirectory) {
      return ToolResult.failure("'$dirArg' is a file, not a directory.")
    }
    val entries = BoundedWalk.list(base, depth, maxEntries)
    val truncated = entries.any { it.truncated }
    val lines = entries.filter { !it.truncated }.map { entry ->
      val rel = try {
        entry.file.relativeTo(ctx.projectRoot).path
      } catch (e: Exception) {
        entry.file.name
      }
      val indent = "  ".repeat((entry.depth - 1).coerceAtLeast(0))
      if (entry.file.isDirectory) "$indent$rel/" else "$indent$rel"
    }
    val capped = Truncate.lines(lines.joinToString("\n"), maxEntries, "entries")
    val text = buildString {
      append(capped.text)
      if (truncated) append("\n…[listing truncated at $maxEntries entries; narrow dir or depth]")
    }
    return ToolResult.success(
        text,
        stats = mapOf("entries" to lines.size.toString(), "dir" to dirArg),
        truncated = truncated || capped.truncated
    )
  }
}
