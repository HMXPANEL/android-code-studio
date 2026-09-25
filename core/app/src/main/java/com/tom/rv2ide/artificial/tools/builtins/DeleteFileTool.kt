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

/** local:delete_file — backup-first delete. DESTRUCTIVE, always confirms. */
class DeleteFileTool(appContext: Context) : Tool {
  private val writer = AIFileWriter(appContext)

  override val id = "local:delete_file"
  override val namespace = "local"
  override val description =
      "Delete a file. A backup is kept first; restore is possible from backup history."
  override val schema = ToolSchema(
      listOf(
          ToolInputField("path", ToolInputType.STRING, "File path relative to project root", true)
      )
  )
  override val kind = ToolKind.DESTRUCTIVE
  override val readOnlyHint = false
  override val timeoutSec = 30L
  override val confirmPolicy = ConfirmPolicy.ALWAYS
  override val visible = true

  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    val pathArg = args["path"] as? String
        ?: return ToolResult.failure("Missing required argument 'path'.")
    if (pathArg.isBlank()) {
      return ToolResult.failure("Argument 'path' must not be blank.")
    }
    val file = PathJail.resolveInside(ctx.projectRoot, pathArg)
        ?: return ToolResult.failure("Path '$pathArg' is outside the project root.")
    if (!file.exists() || !file.isFile) {
      return ToolResult.failure("File '$pathArg' does not exist.")
    }
    val previous: String = try {
      file.readText()
    } catch (e: Exception) {
      return ToolResult.failure("Could not read '$pathArg' before delete: ${e.message}")
    }
    // Backup-first: rewriting identical content forces AIFileWriter to snapshot.
    when (val snap = writer.writeFile(file.absolutePath, previous, true)) {
      is FileWriteResult.PermissionDenied ->
        return ToolResult.failure("Delete refused: ${snap.reason}")
      is FileWriteResult.Error ->
        return ToolResult.failure("Could not back up '$pathArg': ${snap.message}")
      is FileWriteResult.Success -> { /* backup kept, proceed */ }
    }
    val deleted = try {
      file.delete()
    } catch (e: Exception) {
      return ToolResult.failure("Delete failed for '$pathArg': ${e.message}")
    }
    if (!deleted || file.exists()) {
      return ToolResult.failure("Delete failed for '$pathArg' (file still exists).")
    }
    ctx.onFileModified?.invoke(file.absolutePath, previous, "", true)
    return ToolResult.success(
        "Deleted '$pathArg' (backup kept in AI backup history).",
        stats = mapOf("path" to pathArg)
    )
  }
}
