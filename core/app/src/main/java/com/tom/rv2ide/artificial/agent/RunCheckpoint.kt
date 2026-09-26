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

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.git.GitManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Checkpoint manager for agent runs.
 *
 * Creates a Git commit on the first file write of a run (if the project
 * is a Git repository), allowing the run's changes to be reverted in
 * one operation. The checkpoint is small (a single commit) and is
 * pruned when the run completes or is cancelled.
 *
 * This is a Phase 1 safety net; it does not replace the existing
 * per-file backup system in AIFileWriter.
 */
class RunCheckpoint private constructor(
    private val projectRoot: File,
    private val runId: String
) {

    private val checkpointCommitHash = AtomicReference<String?>(null)
    private val gitManager: GitManager? = try {
        GitManager(projectRoot.absolutePath).also { gm ->
            if (!gm.openRepository()) {
                // Not a git repo or failed to open
                throw IllegalStateException("Not a git repository")
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Attempts to create a checkpoint for the given project and run.
         * Returns null if the project is not a Git repository or Git is unavailable.
         */
        fun createIfPossible(projectRoot: File, runId: String): RunCheckpoint? {
            return try {
                RunCheckpoint(projectRoot, runId)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Marks the checkpoint by committing current state (if not already done).
     * Called on the first file write of the run.
     */
    fun ensureCheckpoint(): Boolean {
        if (checkpointCommitHash.get() != null) return true // Already checkpointed
        return if (gitManager != null) {
            val hash = createCheckpointCommit()
            if (hash != null) {
                checkpointCommitHash.set(hash)
                true
            } else {
                false
            }
        } else {
            false
        }
    }

    private fun createCheckpointCommit(): String? {
        return try {
            val gm = gitManager!!
            gm.stageAllAndCommit("agent-checkpoint: run $runId")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Restores the project to the checkpoint state.
     * Discards all changes made since the checkpoint.
     */
    fun restore(): Boolean {
        val hash = checkpointCommitHash.get()
        return if (hash != null && gitManager != null) {
            try {
                gitManager!!.hardResetTo(hash)
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    /**
     * Cleans up the checkpoint by dropping the commit if it was created.
     * Called when the run completes successfully (we keep the changes).
     * Note: In Phase 1 we keep the commit for history; advanced cleanup
     * (rebase/squash) is a Phase 2+ concern.
     */
    fun release(): Boolean {
        // For Phase 1, we just release the reference.
        // The checkpoint commit remains in history.
        checkpointCommitHash.set(null)
        return true
    }

    /** Returns true if a checkpoint was created for this run. */
    fun hasCheckpoint(): Boolean = checkpointCommitHash.get() != null
}