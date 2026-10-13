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

package com.tom.rv2ide.artificial.session

import java.io.File
import java.util.UUID

/**
 * Stable project identity for project-local agent data.
 *
 * Project identity in the IDE is otherwise the filesystem path, so moving or
 * renaming a project orphans path-keyed discovery. A UUID marker file,
 * `<root>/.opencode/project-id.txt`, travels with the project and is
 * preferred whenever present and valid.
 *
 * Copy policy (documented, implemented, tested): a copied project tree keeps
 * its marker file, so the copy initially shares the source's ID. Session
 * discovery is still isolated because listing is scoped to the store's own
 * base directory — a copy never sees the source's session *files*, and
 * vice versa. Regenerating on copy is intentionally NOT attempted: silent
 * rewrites of user project content are worse than a shared marker.
 *
 * No Android dependencies: fully JVM-testable. All functions are
 * synchronous, small-file I/O only; callers on the main thread must prefer
 * [resolve] (single small read) and reserve [getOrCreate] for write paths.
 */
object ProjectIdentity {

    const val DIR_NAME = ".opencode"
    const val MARKER_NAME = "project-id.txt"

    /** Why stable identity is unavailable and path fallback applies. */
    enum class FallbackReason {
        /** Root is not a directory (missing, a file, or inaccessible). */
        NOT_A_DIRECTORY,
        /** Marker absent and the directory is not writable. */
        NOT_WRITABLE,
        /** Marker absent (read-only probe; use [getOrCreate] to initialize). */
        MARKER_MISSING,
        /** Marker present but not a UUID — file is left untouched. */
        MARKER_MALFORMED,
        /** Any other I/O failure. */
        IO_ERROR
    }

    sealed interface Resolution {
        /** A validated stable UUID. */
        data class Stable(val id: String) : Resolution
        /** Stable identity unavailable; use the project path instead. */
        data class PathFallback(val reason: FallbackReason) : Resolution
    }

    fun markerFile(root: File): File = File(File(root, DIR_NAME), MARKER_NAME)

    /**
     * Read-only probe: never creates, moves, or modifies anything.
     * A malformed marker is reported, never repaired or deleted here.
     */
    fun resolve(root: File): Resolution {
        return try {
            if (!root.isDirectory) {
                return Resolution.PathFallback(FallbackReason.NOT_A_DIRECTORY)
            }
            val marker = markerFile(root)
            if (!marker.isFile) {
                return if (root.canWrite()) {
                    Resolution.PathFallback(FallbackReason.MARKER_MISSING)
                } else {
                    Resolution.PathFallback(FallbackReason.NOT_WRITABLE)
                }
            }
            val text = marker.readText().trim()
            val id = try {
                UUID.fromString(text).toString()
            } catch (e: IllegalArgumentException) {
                null
            }
            if (id != null && text == id) {
                Resolution.Stable(id)
            } else {
                Resolution.PathFallback(FallbackReason.MARKER_MALFORMED)
            }
        } catch (e: Exception) {
            Resolution.PathFallback(FallbackReason.IO_ERROR)
        }
    }

    /**
     * Returns the stable ID, creating and persisting a fresh UUID when no
     * valid marker exists and the directory is writable. Returns a
     * [Resolution.PathFallback] (leaving any malformed marker untouched) when
     * creation is impossible or unsafe.
     */
    fun getOrCreate(root: File): Resolution {
        val current = resolve(root)
        if (current is Resolution.Stable) return current
        if (current is Resolution.PathFallback &&
            current.reason != FallbackReason.MARKER_MISSING
        ) {
            return current
        }
        return try {
            if (!root.isDirectory || !root.canWrite()) {
                return Resolution.PathFallback(FallbackReason.NOT_WRITABLE)
            }
            val dir = File(root, DIR_NAME)
            if (!dir.isDirectory && !dir.mkdirs()) {
                return Resolution.PathFallback(FallbackReason.IO_ERROR)
            }
            val id = UUID.randomUUID().toString()
            markerFile(root).writeText(id)
            // Read back to prove the write landed.
            when (val check = resolve(root)) {
                is Resolution.Stable -> check
                else -> Resolution.PathFallback(FallbackReason.IO_ERROR)
            }
        } catch (e: Exception) {
            Resolution.PathFallback(FallbackReason.IO_ERROR)
        }
    }
}

/**
 * Read-only check for the `.opencode/` Git ignore rule in user projects.
 *
 * Strategy (deliberate): the IDE never writes or modifies a user project's
 * `.gitignore` — that would mutate user content unprompted. Callers use this
 * status to *warn* (e.g. a hint when opening session history), never to edit.
 */
object ProjectGitIgnore {

    enum class Status {
        /** No `.git` directory: nothing to ignore. */
        NOT_A_REPO,
        /** An ignore rule matching `.opencode` is present. */
        IGNORED,
        /** A git repo whose ignore file lacks the rule. */
        RULE_MISSING
    }

    private val acceptedRules = setOf(".opencode/", "/.opencode/", ".opencode", "/.opencode")

    fun status(root: File): Status {
        return try {
            if (!root.isDirectory) return Status.NOT_A_REPO
            if (!File(root, ".git").exists()) return Status.NOT_A_REPO
            val ignore = File(root, ".gitignore")
            if (!ignore.isFile) return Status.RULE_MISSING
            val covered = ignore.readLines().any { line ->
                // Comment and blank lines never count; a `!.opencode/` negation
                // is not in acceptedRules, so un-ignoring can't pass as ignored.
                val rule = line.trim()
                rule.isNotEmpty() && !rule.startsWith("#") && rule in acceptedRules
            }
            if (covered) Status.IGNORED else Status.RULE_MISSING
        } catch (e: Exception) {
            Status.NOT_A_REPO
        }
    }
}
