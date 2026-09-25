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

import java.security.MessageDigest

/**
 * Pure SEARCH/REPLACE application logic for `local:edit_file`.
 * No I/O, no Android dependencies — fully JVM-testable. The tool wires
 * file I/O, backup, and rollback around [apply].
 */
object EditApply {

  /** SHA-256 hex of UTF-8 content. Also used by `read_file` stats. */
  fun sha256Hex(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  sealed class EditOutcome {
    data class Applied(val newContent: String, val matchCount: Int) : EditOutcome()
    data class Rejected(val reason: String) : EditOutcome()
  }

  /**
   * Applies one SEARCH/REPLACE edit.
   *
   * @param currentContent live file content just re-read at execution time.
   * @param expectedHash hash the model saw when reading (stale → reject).
   * @param search exact block to find (must be non-blank).
   * @param replace replacement block (may be empty to delete the block).
   * @param occurrence 0-based match index, or null to require exactly one match.
   */
  fun apply(
      currentContent: String,
      expectedHash: String?,
      search: String,
      replace: String,
      occurrence: Int?
  ): EditOutcome {
    if (search.isEmpty()) {
      return EditOutcome.Rejected("Argument 'search' must not be empty.")
    }
    if (expectedHash != null && !expectedHash.equals(sha256Hex(currentContent), ignoreCase = true)) {
      return EditOutcome.Rejected(
          "File changed since it was read (stale hash). Re-read the file to get fresh content and hash, then retry."
      )
    }
    val matches = findMatches(currentContent, search)
    if (matches.isEmpty()) {
      return EditOutcome.Rejected("SEARCH block not found in the current file content.")
    }
    val chosen: Int = if (occurrence != null) {
      if (occurrence < 0 || occurrence >= matches.size) {
        return EditOutcome.Rejected(
            "Occurrence $occurrence out of range (found ${matches.size} match(es), valid 0..${matches.size - 1})."
        )
      }
      occurrence
    } else {
      if (matches.size != 1) {
        return EditOutcome.Rejected(
            "SEARCH block matches ${matches.size} times; pass occurrence (0..${matches.size - 1}) to choose, or narrow the block."
        )
      }
      0
    }
    val at = matches[chosen]
    val newContent = currentContent.substring(0, at) + replace +
        currentContent.substring(at + search.length)
    if (newContent == currentContent) {
      return EditOutcome.Rejected("Edit produced no change (search and replace are identical).")
    }
    if (newContent.isBlank() && currentContent.isNotBlank()) {
      return EditOutcome.Rejected(
          "Edit would empty a non-empty file; refused. Delete the file explicitly if that is intended."
      )
    }
    return EditOutcome.Applied(newContent, matches.size)
  }

  /** Non-overlapping match offsets of [search] in [content]. */
  internal fun findMatches(content: String, search: String): List<Int> {
    val out = mutableListOf<Int>()
    var from = 0
    while (true) {
      val at = content.indexOf(search, from)
      if (at < 0) break
      out.add(at)
      from = at + search.length
    }
    return out
  }
}
