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

package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import java.io.File

/**
 * Shared post-write verification utility.
 *
 * Both the agent loop tools (WriteFileTool, EditFileTool) and the legacy
 * FILE_TO_MODIFY path must verify that bytes on disk match the intended
 * content before reporting success. This class centralizes that logic
 * so there is a single source of truth for "what does verified mean".
 */
object WriteVerification {

    /**
     * Writes content to a file using AIFileWriter, then verifies the
     * on-disk content matches exactly. On verification failure, attempts
     * to roll back to the previous content.
     *
     * @param writer the AIFileWriter instance
     * @param file the target file
     * @param content the new content to write
     * @param previous the previous content (null if file was new), used for rollback
     * @param createBackup whether to create a backup before writing
     * @return ToolResult indicating success with verified message, or failure with rollback status
     */
    fun writeAndVerify(
        writer: AIFileWriter,
        file: File,
        content: String,
        previous: String?,
        createBackup: Boolean = true
    ): ToolResult {
        val writeResult = writer.writeFile(file.absolutePath, content, createBackup)
        return when (writeResult) {
            is FileWriteResult.Success -> {
                val verified = try {
                    file.readText() == content
                } catch (e: Exception) {
                    false
                }
                if (!verified) {
                    // Rollback attempt
                    val rolledBack = try {
                        if (previous != null) {
                            writer.writeFile(file.absolutePath, previous, false)
                            file.readText() == previous
                        } else if (file.exists()) {
                            file.delete()
                            !file.exists()
                        } else {
                            true
                        }
                    } catch (e: Exception) {
                        false
                    }
                    return ToolResult.failure(
                        "Write FAILED verification: disk content does not match. " +
                            if (rolledBack) " Rolled back to previous state."
                            else " ROLLBACK FAILED — check the file!"
                    )
                }
                ToolResult.success(
                    if (previous == null) {
                        "Created '${file.name}' (${content.length} chars)."
                    } else {
                        "Overwrote '${file.name}' (${content.length} chars, backup kept: ${writeResult.backupCreated})."
                    },
                    stats = mapOf("path" to file.absolutePath, "new" to (previous == null).toString())
                )
            }
            is FileWriteResult.PermissionDenied ->
                ToolResult.failure("Write refused: ${writeResult.reason}")
            is FileWriteResult.Error ->
                ToolResult.failure("Write failed: ${writeResult.message}")
        }
    }

    /**
     * Verifies that a file's current content matches the expected content.
     * Does not perform any write — pure read-and-compare.
     *
     * @param file the file to verify
     * @param expected the expected content
     * @return true if file exists and content matches exactly
     */
    fun verifyContent(file: File, expected: String): Boolean {
        return try {
            file.exists() && file.isFile && file.readText() == expected
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Attempts to restore a file to a previous state.
     *
     * @param writer the AIFileWriter instance
     * @param file the target file
     * @param previous the content to restore (null means delete the file)
     * @return true if restoration succeeded and content matches
     */
    fun restoreToPrevious(
        writer: AIFileWriter,
        file: File,
        previous: String?
    ): Boolean {
        return try {
            if (previous != null) {
                val result = writer.writeFile(file.absolutePath, previous, false)
                result is FileWriteResult.Success && file.readText() == previous
            } else if (file.exists()) {
                file.delete()
                !file.exists()
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}