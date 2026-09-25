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
import com.tom.rv2ide.artificial.tools.ToolRegistry

/**
 * Registers the Phase 1 built-in tools. Idempotent (re-registering replaces).
 * Call once per loop start; PLAN/BUILD filtering happens at runtime.
 */
object AgentTools {

  fun registerAll(appContext: Context) {
    ToolRegistry.register(ListFilesTool())
    ToolRegistry.register(ReadFileTool())
    ToolRegistry.register(SearchFilesTool())
    ToolRegistry.register(WriteFileTool(appContext))
    ToolRegistry.register(EditFileTool(appContext))
    ToolRegistry.register(DeleteFileTool(appContext))
    ToolRegistry.register(RunCommandTool())
    ToolRegistry.register(BuildProjectTool())
  }

  fun toolIds(): List<String> = listOf(
      "local:list_files",
      "local:read_file",
      "local:search_files",
      "local:write_file",
      "local:edit_file",
      "local:delete_file",
      "local:run_command",
      "local:build_project"
  )
}
