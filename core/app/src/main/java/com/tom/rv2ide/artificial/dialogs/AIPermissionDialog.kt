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

package com.tom.rv2ide.artificial.dialogs

import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.htmlFromHtml
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.permissions.AIPermissionManager

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class AIPermissionDialog(private val context: Context) {

    private val permissionManager = AIPermissionManager(context)

    /**
     * Maximum lines of diff to show in the preview dialog.
     * Keeps memory/rendering bounded on low-RAM devices.
     */
    private const val MAX_DIFF_LINES = 80

    fun showFileWriteConfirmation(
        fileName: String,
        onConfirm: () -> Unit,
        onDeny: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("AI File Write Permission")
            .setMessage("AI wants to write to:\n$fileName\n\nAllow this action?")
            .setPositiveButton("Allow") { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton("Deny") { dialog, _ ->
                onDeny()
                dialog.dismiss()
            }
            .setNeutralButton("Always Allow") { dialog, _ ->
                permissionManager.setRequireConfirmation(false)
                onConfirm()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows a confirmation dialog with a bounded diff preview for file writes/edits.
     *
     * @param filePath the file path being modified
     * @param oldContent the previous content (null for new files)
     * @param newContent the new content to be written
     * @param onConfirm callback when user allows the change
     * @param onDeny callback when user denies the change
     */
    fun showFileWriteConfirmationWithDiff(
        filePath: String,
        oldContent: String?,
        newContent: String,
        onConfirm: () -> Unit,
        onDeny: () -> Unit
    ) {
        val diffText = buildBoundedDiff(filePath, oldContent, newContent)

        val scrollView = ScrollView(context)
        val textView = TextView(context).apply {
            text = diffText
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(context)
            .setTitle("Confirm File Change")
            .setView(scrollView)
            .setPositiveButton("Apply") { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                onDeny()
                dialog.dismiss()
            }
            .setNeutralButton("Always Allow") { dialog, _ ->
                permissionManager.setRequireConfirmation(false)
                onConfirm()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Builds a bounded unified diff between old and new content.
     * Limited to MAX_DIFF_LINES to prevent OOM on large files.
     */
    private fun buildBoundedDiff(filePath: String, oldContent: String?, newContent: String): String {
        val fileName = java.io.File(filePath).name
        val builder = StringBuilder()
        builder.append("<b>File:</b> $fileName<br/><br/>")

        if (oldContent == null) {
            // New file - show first N lines of new content
            builder.append("<b>New file (first ${MAX_DIFF_LINES} lines):</b><br/>")
            val lines = newContent.lines().take(MAX_DIFF_LINES).toList()
            builder.append(escapeHtml(lines.joinToString("\n")))
            if (newContent.count { it == '\n' } >= MAX_DIFF_LINES) {
                builder.append("<br/><br/><i>... truncated (${newContent.lines().size} total lines)</i>")
            }
            return builder.toString()
        }

        // Compute simple line-by-line diff
        val oldLines = oldContent.lines().toList()
        val newLines = newContent.lines().toList()

        val diff = computeSimpleDiff(oldLines, newLines)

        builder.append("<b>Changes:</b><br/>")
        var lineCount = 0
        for (change in diff) {
            if (lineCount >= MAX_DIFF_LINES) {
                builder.append("<br/><i>... diff truncated (showing first $MAX_DIFF_LINES lines)</i>")
                break
            }
            when (change.type) {
                DiffType.UNCHANGED -> {
                    builder.append("<font color='#888888'>  ${escapeHtml(change.line)}</font><br/>")
                }
                DiffType.REMOVED -> {
                    builder.append("<font color='#FF6B6B'>- ${escapeHtml(change.line)}</font><br/>")
                }
                DiffType.ADDED -> {
                    builder.append("<font color='#6BCB77'>+ ${escapeHtml(change.line)}</font><br/>")
                }
            }
            lineCount++
        }

        return builder.toString()
    }

    private enum class DiffType { UNCHANGED, REMOVED, ADDED }

    private data class DiffLine(val type: DiffType, val line: String)

    /**
     * Simple O(N*M) diff for small files. For large files this is bounded by
     * MAX_DIFF_LINES anyway, so performance is acceptable.
     */
    private fun computeSimpleDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0

        while (i < oldLines.size || j < newLines.size) {
            if (i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j]) {
                result.add(DiffLine(DiffType.UNCHANGED, oldLines[i]))
                i++
                j++
            } else if (j < newLines.size && (i >= oldLines.size || !oldLines.subList(i, oldLines.size).contains(newLines[j]))) {
                // Line added in new
                result.add(DiffLine(DiffType.ADDED, newLines[j]))
                j++
            } else if (i < oldLines.size) {
                // Line removed from old
                result.add(DiffLine(DiffType.REMOVED, oldLines[i]))
                i++
            } else {
                // Should not happen, but guard
                break
            }
        }

        return result
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
            .replace("\"", """)
            .replace("'", "'")
    }

    fun showPermissionSettings(onSettingsChanged: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(
            R.layout.dialog_ai_permissions, 
            null
        )

        val enableWriteCheckbox: CheckBox = view.findViewById(R.id.enableWriteCheckbox)
        val requireConfirmationCheckbox: CheckBox = view.findViewById(R.id.requireConfirmationCheckbox)
        val autoBackupCheckbox: CheckBox = view.findViewById(R.id.autoBackupCheckbox)

        // Load current settings
        enableWriteCheckbox.isChecked = permissionManager.isFileWriteEnabled()
        requireConfirmationCheckbox.isChecked = permissionManager.requiresConfirmation()
        autoBackupCheckbox.isChecked = permissionManager.isAutoBackupEnabled()

        MaterialAlertDialogBuilder(context)
            .setTitle("AI Permissions")
            .setView(view)
            .setPositiveButton("Save") { dialog, _ ->
                permissionManager.setFileWriteEnabled(enableWriteCheckbox.isChecked)
                permissionManager.setRequireConfirmation(requireConfirmationCheckbox.isChecked)
                permissionManager.setAutoBackup(autoBackupCheckbox.isChecked)
                onSettingsChanged()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
