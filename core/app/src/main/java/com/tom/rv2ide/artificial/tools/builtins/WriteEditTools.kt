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

import android.content.Context
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
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
import java.io.File

/** local:write_file — full-file create/overwrite via AIFileWriter (backup kept). */
class WriteFileTool(appContext: Context) : Tool {
  private val writer = AIFileWriter(appContext)

  override val id = "local:write_file"
  override val namespace = "local"
  override val description =
      "Create or completely overwrite a text file. Prefer local:edit_file for small changes."
  override val schema = ToolSchema(
      listOf(
          ToolInputField("path", ToolInputType.STRING, "File path relative to project root", true),
          ToolInputField("content", ToolInputType.STRING, "Complete new file content", true)
      )
  )
  override val kind = ToolKind.WRITE
  override val readOnlyHint = false
  override val timeoutSec = 60L
  override val confirmPolicy = ConfirmPolicy.NEVER
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    val pathArg = args["path"] as? String
        ?: return ToolResult.failure("Missing required argument 'path'.")
    val content = args["content"] as? String
        ?: return ToolResult.failure("Missing required argument 'content'.")
    if (pathArg.isBlank()) {
      return ToolResult.failure("Argument 'path' must not be blank.")
    }
    if (content.length > 512 * 1024) {
      return ToolResult.failure("Content exceeds 512 KB; split into smaller writes.")
    }
    val file = PathJail.resolveInside(ctx.projectRoot, pathArg)
        ?: return ToolResult.failure("Path '$pathArg' is outside the project root.")
    val previous: String? = try {
      if (file.exists() && file.isFile) file.readText() else null
    } catch (e: Exception) {
      return ToolResult.failure("Could not read existing file '$pathArg': ${e.message}")
    }
    return when (val result = writer.writeFile(file.absolutePath, content, true)) {
      is FileWriteResult.Success -> {
        // Truth rule: a Success return is a claim, not proof. Re-read and
        // verify the bytes on disk before reporting success.
        val verified = try {
          file.readText() == content
        } catch (e: Exception) {
          false
        }
        if (!verified) {
          try {
            if (previous != null) {
              writer.writeFile(file.absolutePath, previous, false)
            } else if (file.exists()) {
              file.delete()
            }
          } catch (e: Exception) {
            // Best-effort cleanup; the failure below is what matters.
          }
          ctx.onFileModified?.invoke(file.absolutePath, previous, content, false)
          return ToolResult.failure(
              "Write FAILED verification: disk content does not match. " +
                  "Rolled back to the previous state."
          )
        }
        ctx.onFileModified?.invoke(
            file.absolutePath,
            previous,
            content,
            true
        )
        ToolResult.success(
            if (previous == null) {
              "Created '$pathArg' (${content.length} chars)."
            } else {
              "Overwrote '$pathArg' (${content.length} chars, backup kept: ${result.backupCreated})."
            },
            stats = mapOf("path" to pathArg, "new" to (previous == null).toString())
        )
      }
      is FileWriteResult.PermissionDenied ->
        ToolResult.failure("Write refused: ${result.reason}")
      is FileWriteResult.Error ->
        ToolResult.failure("Write failed: ${result.message}")
    }
  }
}

/** local:edit_file — targeted SEARCH/REPLACE with stale-check, backup, rollback. */
class EditFileTool(appContext: Context) : Tool {
  private val writer = AIFileWriter(appContext)

  override val id = "local:edit_file"
  override val namespace = "local"
  override val description =
      "Replace one SEARCH block with new content. Pass the sha256 hash from local:read_file."
  override val schema = ToolSchema(
      listOf(
          ToolInputField("path", ToolInputType.STRING, "File path relative to project root", true),
          ToolInputField("search", ToolInputType.STRING, "Exact block to find", true),
          ToolInputField("replace", ToolInputType.STRING, "Replacement block", true),
          ToolInputField(
              "expectedHash",
              ToolInputType.STRING,
              "sha256 from local:read_file (stale protection)",
              true
          ),
          ToolInputField(
              "occurrence",
              ToolInputType.NUMBER,
              "0-based match index when several match",
              false,
              null
          )
      )
  )
  override val kind = ToolKind.WRITE
  override val readOnlyHint = false
  override val timeoutSec = 60L
  override val confirmPolicy = ConfirmPolicy.NEVER
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    val pathArg = args["path"] as? String
        ?: return ToolResult.failure("Missing required argument 'path'.")
    val search = args["search"] as? String
        ?: return ToolResult.failure("Missing required argument 'search'.")
    val replace = args["replace"] as? String
        ?: return ToolResult.failure("Missing required argument 'replace'.")
    val expectedHash = args["expectedHash"] as? String
        ?: return ToolResult.failure("Missing required argument 'expectedHash'.")
    val occurrence = (args["occurrence"] as? Number)?.toInt()
    if (pathArg.isBlank()) {
      return ToolResult.failure("Argument 'path' must not be blank.")
    }
    val file = PathJail.resolveInside(ctx.projectRoot, pathArg)
        ?: return ToolResult.failure("Path '$pathArg' is outside the project root.")
    val current: String = try {
      if (!file.exists() || !file.isFile) {
        return ToolResult.failure("File '$pathArg' does not exist; use local:write_file to create it.")
      }
      if (file.length() > 1024L * 1024L) {
        return ToolResult.failure("File '$pathArg' exceeds 1 MB; cannot edit safely.")
      }
      file.readText()
    } catch (e: Exception) {
      return ToolResult.failure("Could not read '$pathArg': ${e.message}")
    }

    return when (val outcome = EditApply.apply(current, expectedHash, search, replace, occurrence)) {
      is EditApply.EditOutcome.Rejected ->
        ToolResult.failure("Edit refused: ${outcome.reason}")
      is EditApply.EditOutcome.Applied -> {
        when (val written = writer.writeFile(file.absolutePath, outcome.newContent, true)) {
          is FileWriteResult.Success -> {
            // Truth rule: verify the replacement block is actually on disk.
            val verified = try {
              file.readText().contains(replace)
            } catch (e: Exception) {
              false
            }
            if (!verified) {
              val restored = try {
                writer.writeFile(file.absolutePath, current, false)
                file.readText() == current
              } catch (e: Exception) {
                false
              }
              ctx.onFileModified?.invoke(file.absolutePath, current, current, false)
              return ToolResult.failure(
                  "Edit FAILED verification: replacement not found on disk." +
                      if (restored) " Original content restored."
                      else " ROLLBACK FAILED — check the file!"
              )
            }
            ctx.onFileModified?.invoke(file.absolutePath, current, outcome.newContent, true)
            ToolResult.success(
                "Edited '$pathArg' (${outcome.matchCount} match(es), occurrence applied).",
                stats = mapOf("path" to pathArg, "matches" to outcome.matchCount.toString())
            )
          }
          else -> {
            // Roll back to the pre-edit content; report both outcomes.
            val rolledBack = try {
              writer.writeFile(file.absolutePath, current, false)
              file.readText() == current
            } catch (e: Exception) {
              false
            }
            val detail = if (written is FileWriteResult.PermissionDenied) {
              written.reason
            } else {
              (written as? FileWriteResult.Error)?.message ?: "unknown error"
            }
            ctx.onFileModified?.invoke(file.absolutePath, current, current, false)
            ToolResult.failure(
                "Edit write failed ($detail)." +
                    if (rolledBack) " Original content restored." else " ROLLBACK FAILED — check the file!"
            )
          }
        }
      }
    }
  }
}
