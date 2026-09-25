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
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ensureActive

/** local:search_files — bounded content search. READ, never confirms. */
class SearchFilesTool : Tool {
  override val id = "local:search_files"
  override val namespace = "local"
  override val description =
      "Search file contents for text. Returns path:line rows. Prefer this over reading whole trees."
  override val schema = ToolSchema(
      listOf(
          ToolInputField("query", ToolInputType.STRING, "Text to search for", true),
          ToolInputField("dir", ToolInputType.STRING, "Directory relative to project root", false, "."),
          ToolInputField(
              "extensions",
              ToolInputType.STRING,
              "Comma-separated extensions, e.g. kt,java,xml (empty = all text files)",
              false,
              ""
          ),
          ToolInputField("maxResults", ToolInputType.NUMBER, "Max matches to return", false, 50)
      )
  )
  override val kind = ToolKind.READ
  override val readOnlyHint = true
  override val timeoutSec = 30L
  override val confirmPolicy = ConfirmPolicy.NEVER
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    val query = args["query"] as? String
        ?: return ToolResult.failure("Missing required argument 'query'.")
    if (query.isBlank()) {
      return ToolResult.failure("Argument 'query' must not be blank.")
    }
    if (query.length > 200) {
      return ToolResult.failure("Query is too long (max 200 characters).")
    }
    val dirArg = (args["dir"] as? String)?.ifBlank { "." } ?: "."
    val extRaw = (args["extensions"] as? String).orEmpty()
    val extensions = extRaw.split(",").map { it.trim().lowercase().removePrefix(".") }
        .filter { it.isNotEmpty() }.toSet().ifEmpty { null }
    val maxResults = ((args["maxResults"] as? Number)?.toInt() ?: 50).coerceIn(1, 50)

    val base = PathJail.resolveInside(ctx.projectRoot, dirArg)
        ?: return ToolResult.failure("Directory '$dirArg' is outside the project root.")
    if (!base.exists() || !base.isDirectory) {
      return ToolResult.failure("Directory '$dirArg' does not exist.")
    }

    val hits = ArrayList<String>(maxResults + 1)
    var filesScanned = 0
    var capped = false
    val job: Job? = kotlinx.coroutines.currentCoroutineContext()[Job]

    fun scanFile(file: File) {
      if (hits.size >= maxResults) {
        capped = true
        return
      }
      if (file.length() > 1024L * 1024L) return
      if (ReadFileTool.isBinary(file)) return
      try {
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
          var lineNumber = 0
          var line = reader.readLine()
          while (line != null) {
            lineNumber++
            if (line.contains(query)) {
              val rel = try {
                file.relativeTo(ctx.projectRoot).path
              } catch (e: Exception) {
                file.name
              }
              val snippet = if (line.length > 160) line.take(160) + "…" else line
              hits.add("$rel:$lineNumber: ${snippet.trim()}")
              if (hits.size >= maxResults) {
                capped = true
                return
              }
            }
            line = reader.readLine()
          }
        }
      } catch (e: Exception) {
        // Unreadable file: skip, keep searching.
      }
      filesScanned++
    }

    fun walk(dir: File, depth: Int): Boolean {
      job?.ensureActive()
      val children = try {
        dir.listFiles()?.sortedBy { it.name } ?: return true
      } catch (e: Exception) {
        return true
      }
      for (child in children) {
        if (hits.size >= maxResults) {
          capped = true
          return false
        }
        if (child.isDirectory) {
          if (child.name in AGENT_SKIP_DIRS) continue
          if (depth + 1 > 8) continue
          if (!walk(child, depth + 1)) return false
        } else {
          if (extensions != null) {
            val ext = child.name.substringAfterLast('.', "").lowercase()
            if (ext !in extensions) continue
          }
          scanFile(child)
        }
      }
      return true
    }

    return try {
      walk(base, 0)
      val cappedText = Truncate.lines(hits.joinToString("\n"), maxResults, "matches")
      val text = buildString {
        if (hits.isEmpty()) {
          append("No matches for '$query' under '$dirArg' ($filesScanned files scanned).")
        } else {
          append(cappedText.text)
          if (capped) {
            append("\n…[match cap $maxResults reached; narrow dir, extensions, or query]")
          }
        }
      }
      ToolResult.success(
          text,
          stats = mapOf(
              "matches" to hits.size.toString(),
              "filesScanned" to filesScanned.toString()
          ),
          truncated = capped || cappedText.truncated
      )
    } catch (e: Exception) {
      ToolResult.failure("Search failed: ${e.message}")
    }
  }
}
