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
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/** local:read_file — paged, size-capped file reading. READ, never confirms. */
class ReadFileTool : Tool {
  override val id = "local:read_file"
  override val namespace = "local"
  override val description =
      "Read a text file with line numbers. Results are paged; use offset for more."
  override val schema = ToolSchema(
      listOf(
          ToolInputField("path", ToolInputType.STRING, "File path relative to project root", true),
          ToolInputField("offset", ToolInputType.NUMBER, "First line (0-based)", false, 0),
          ToolInputField("maxLines", ToolInputType.NUMBER, "Max lines to return", false, 200)
      )
  )
  override val kind = ToolKind.READ
  override val readOnlyHint = true
  override val timeoutSec = 30L
  override val confirmPolicy = ConfirmPolicy.NEVER
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {    val pathArg = args["path"] as? String
        ?: return ToolResult.failure("Missing required argument 'path'.")
    if (pathArg.isBlank()) {
      return ToolResult.failure("Argument 'path' must not be blank.")
    }
    val offset = ((args["offset"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
    val maxLines = ((args["maxLines"] as? Number)?.toInt() ?: 200).coerceIn(1, 200)
    val file = PathJail.resolveInside(ctx.projectRoot, pathArg)
        ?: return ToolResult.failure("Path '$pathArg' is outside the project root.")
    if (!file.exists()) {
      return ToolResult.failure("File '$pathArg' does not exist.")
    }
    if (!file.isFile) {
      return ToolResult.failure("'$pathArg' is a directory, not a file. Use local:list_files.")
    }
    if (file.length() > 1024L * 1024L) {
      return ToolResult.failure(
          "File '$pathArg' is larger than 1 MB (${file.length()} bytes) and is not read directly."
      )
    }
    if (isBinary(file)) {
      return ToolResult.failure("File '$pathArg' looks binary and cannot be read as text.")
    }
    return try {
      val lines = ArrayList<String>(maxLines + 1)
      var lineNumber = 0
      var total = 0
      BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
        var line = reader.readLine()
        while (line != null) {
          if (lineNumber >= offset && lines.size < maxLines) {
            lines.add("${lineNumber + 1}: $line")
          }
          lineNumber++
          line = reader.readLine()
        }
        total = lineNumber
      }
      val nextOffset = offset + lines.size
      val text = buildString {
        append(lines.joinToString("\n"))
        if (nextOffset < total) {
          append("\n…[showing lines ${offset + 1}-$nextOffset of $total; re-read with offset=$nextOffset]")
        }
      }
      ToolResult.success(
          text,
          stats = mapOf(
              "path" to pathArg,
              "lines" to total.toString(),
              "offset" to offset.toString(),
              // Full-file hash for local:edit_file stale protection.
              "sha256" to fullFileHash(file)
          ),
          truncated = nextOffset < total
      )
    } catch (e: Exception) {
      ToolResult.failure("Could not read '$pathArg': ${e.message}")
    }
  }

  companion object {
    internal fun fullFileHash(file: File): String {
      return try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
          val buf = ByteArray(8192)
          var read = stream.read(buf)
          while (read > 0) {
            digest.update(buf, 0, read)
            read = stream.read(buf)
          }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
      } catch (e: Exception) {
        "unavailable"
      }
    }

    internal fun isBinary(file: File): Boolean {
      return try {
        FileInputStream(file).use { stream ->
          val buf = ByteArray(4096)
          val read = stream.read(buf)
          if (read <= 0) return@use false
          var i = 0
          while (i < read) {
            if (buf[i] == 0.toByte()) return@use true
            i++
          }
          false
        }
      } catch (e: Exception) {
        false
      }
    }
  }
}
