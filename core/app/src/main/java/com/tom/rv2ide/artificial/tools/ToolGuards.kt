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

import java.io.File

/**
 * Pure safety helpers: project path jail + output truncation.
 * No Android dependencies; JVM-testable.
 */
object PathJail {

  /**
   * Resolves [requestedPath] (absolute or relative to [root]) and returns the
   * canonical file only if it stays inside [root]. Null means rejected.
   */
  fun resolveInside(root: File, requestedPath: String): File? {
    return try {
      val rootCanonical = root.canonicalFile
      val candidate = if (File(requestedPath).isAbsolute) {
        File(requestedPath)
      } else {
        File(rootCanonical, requestedPath)
      }.canonicalFile
      var current: File? = candidate
      while (current != null) {
        if (current == rootCanonical) return candidate
        current = current.parentFile
      }
      if (candidate == rootCanonical) candidate else null
    } catch (e: Exception) {
      null
    }
  }
}

/** Bounded-output helper. Every truncation is explicitly signalled. */
object Truncate {

  data class Truncated(val text: String, val truncated: Boolean, val dropped: Int)

  fun lines(text: String, maxLines: Int, label: String = "output"): Truncated {
    if (maxLines <= 0) return Truncated("", text.isNotEmpty(), text.length)
    val all = text.lines()
    if (all.size <= maxLines) return Truncated(text, false, 0)
    val kept = all.take(maxLines).joinToString("\n")
    val note = "\n…[truncated to $maxLines lines of ${all.size} $label]"
    return Truncated(kept + note, true, all.size - maxLines)
  }

  fun chars(text: String, maxChars: Int, label: String = "output"): Truncated {
    if (text.length <= maxChars) return Truncated(text, false, 0)
    val kept = text.take(maxChars)
    val note = "\n…[truncated to $maxChars chars of ${text.length} $label]"
    return Truncated(kept + note, true, text.length - maxChars)
  }
}
