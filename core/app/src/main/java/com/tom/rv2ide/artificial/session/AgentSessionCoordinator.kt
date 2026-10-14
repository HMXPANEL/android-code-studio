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

import com.tom.rv2ide.artificial.tools.RunMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sanitized debug snapshot of session persistence health. Deliberately contains
 * no message content, no filesystem paths, no keys and no secrets: the project
 * root appears only as a SHA-256 hex digest, failures only as exception type
 * names. Safe for debug logs and bug reports.
 */
data class SessionDiagnostics(
    val rootHash: String,
    val sessionsDirExists: Boolean,
    val sessionsDirWritable: Boolean,
    val sessionFileCount: Int,
    val hasCurrentSession: Boolean,
    val currentUserMessages: Int,
    val currentAssistantMessages: Int,
    val lastErrorType: String?
) {
    companion object {
        fun unavailable(): SessionDiagnostics = SessionDiagnostics(
            rootHash = "unavailable",
            sessionsDirExists = false,
            sessionsDirWritable = false,
            sessionFileCount = 0,
            hasCurrentSession = false,
            currentUserMessages = 0,
            currentAssistantMessages = 0,
            lastErrorType = "probe-failed"
        )
    }
}
/**
 * Owns one conversation's session lifecycle for a single project root.
 *
 * The UI layer (ArtificialFragment) keeps one coordinator per open project and
 * asks it for the current session ID before each run. The coordinator
 * guarantees:
 *
 * - at most one session is created per conversation (never one per message);
 * - follow-up messages reuse the same ID while it still exists in the store;
 * - switching/forgetting never mixes histories (IDs are explicit);
 * - storage failures are reported via [lastError] + null instead of throwing
 *   into the UI, so the agent can continue without persistence.
 *
 * Construct off the main thread: the default [FileSessionStore] reads the
 * session directory in its constructor. All public methods dispatch to
 * [Dispatchers.IO] internally and are safe to call from the main thread.
 *
 * No Android dependencies: fully JVM-testable.
 */
class AgentSessionCoordinator(
    val projectRoot: File,
    scope: CoroutineScope,
    sessionStore: SessionStore? = null,
    private val loadCurrentId: () -> String? = { null },
    private val saveCurrentId: (String?) -> Unit = {}
) {

    private val store: SessionStore = sessionStore ?: FileSessionStore(projectRoot)
    private val indexer = ProjectIndexer()
    private val relevanceEngine = RelevanceEngine(indexer)

    val contextEngine: ContextEngine = ContextEngine(store, indexer, relevanceEngine)
    val memoryStore: ProjectMemoryStore = ProjectMemoryStore(projectRoot)
    val settingsStore: ProjectAgentSettings = ProjectAgentSettings(projectRoot)
    val sessionManager: SessionManager =
        SessionManager(store, contextEngine, indexer, relevanceEngine, scope, memoryStore, settingsStore)

    /**
     * Stable project UUID from `.opencode/project-id.txt` (see
     * ProjectIdentity), resolved lazily. Null while unresolved or when only
     * path-based identity is available.
     */
    @Volatile
    var currentProjectId: String? = null
        private set

    @Volatile
    var currentSessionId: String? = null
        private set

    /** Last storage failure, or null when the last operation succeeded. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Simple class name of the last failure's exception (e.g.
     * "IllegalStateException"), or null when the last operation succeeded.
     * Unlike [lastError], this never contains paths or other details, so it
     * is safe for debug logs.
     */
    @Volatile
    var lastErrorType: String? = null
        private set

    init {
        require(!projectRoot.exists() || projectRoot.isDirectory) {
            "Project root is not a directory: ${projectRoot.path}"
        }
        currentSessionId = try {
            loadCurrentId()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves the stable project ID, creating the marker on write paths.
     * Never throws: null means path-based identity (with [lastError] set only
     * when creation itself failed unexpectedly — plain absence is normal for
     * read paths, which must not create anything).
     */
    private fun stableIdForWrite(): String? = try {
        when (val r = ProjectIdentity.getOrCreate(projectRoot)) {
            is ProjectIdentity.Resolution.Stable -> {
                currentProjectId = r.id
                r.id
            }
            is ProjectIdentity.Resolution.PathFallback -> {
                currentProjectId = null
                null
            }
        }
    } catch (e: Exception) {
        noteFailure(e, "project identity failed")
        null
    }

    /** Read-only probe for list/switch paths: never creates the marker. */
    private fun stableIdForRead(): String? = try {
        when (val r = ProjectIdentity.resolve(projectRoot)) {
            is ProjectIdentity.Resolution.Stable -> {
                currentProjectId = r.id
                r.id
            }
            // Keep any previously resolved ID: a transiently unreadable marker
            // must not silently re-scope discovery mid-conversation.
            is ProjectIdentity.Resolution.PathFallback -> currentProjectId
        }
    } catch (e: Exception) {
        currentProjectId
    }

    /**
     * Returns the session ID for the next run, creating a session on first
     * use or when the remembered one no longer exists. Returns null (with
     * [lastError] set) when persistence is unavailable; callers then run
     * without a session, preserving pre-Phase-3 behavior.
     */
    suspend fun sessionIdFor(mode: RunMode, providerId: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val remembered = currentSessionId
                if (remembered != null && store.get(remembered) != null) {
                    clearFailure()
                    return@withContext remembered
                }
                val created = sessionManager.createSession(
                    projectRoot, mode, providerId, modelId,
                    projectId = stableIdForWrite()
                )
                setCurrent(created.id)
                clearFailure()
                created.id
            } catch (e: Exception) {
                noteFailure(e, "session storage failed")
                null
            }
        }

    /** Starts a brand-new conversation, abandoning (not deleting) the current one. */
    suspend fun startNewSession(mode: RunMode, providerId: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val created = sessionManager.createSession(
                    projectRoot, mode, providerId, modelId,
                    projectId = stableIdForWrite()
                )
                setCurrent(created.id)
                clearFailure()
                created.id
            } catch (e: Exception) {
                noteFailure(e, "session storage failed")
                null
            }
        }

    /** Drops the current conversation pointer without deleting stored history. */
    suspend fun forgetCurrent() = withContext(Dispatchers.IO) {
        setCurrent(null)
        clearFailure()
    }

    /**
     * Switches to an existing session. Returns false when it does not exist
     * or belongs to another project (stable ID mismatch).
     */
    suspend fun switchTo(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = sessionManager.getSession(id) ?: return@withContext false
            val pid = stableIdForRead()
            if (!sessionManager.belongsTo(session, canonicalRoot(), pid)) return@withContext false
            setCurrent(session.id)
            clearFailure()
            true
        } catch (e: Exception) {
            noteFailure(e, "session storage failed")
            false
        }
    }

    suspend fun listSessions(): List<AgentSession> = withContext(Dispatchers.IO) {
        try {
            val sessions = sessionManager.listSessions(projectRoot, stableIdForRead())
            clearFailure()
            sessions
        } catch (e: Exception) {
            noteFailure(e, "session storage failed")
            emptyList()
        }
    }

    /** Malformed record names reported (never moved/deleted) by the file store. */
    suspend fun corruptRecords(): List<String> = withContext(Dispatchers.IO) {
        try {
            sessionManager.corruptRecords()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Git-ignore coverage for `.opencode/` in this project, for UI hints. */
    suspend fun gitIgnoreStatus(): ProjectGitIgnore.Status = withContext(Dispatchers.IO) {
        try {
            ProjectGitIgnore.status(projectRoot)
        } catch (e: Exception) {
            ProjectGitIgnore.Status.NOT_A_REPO
        }
    }

    suspend fun history(id: String): AgentSession? = withContext(Dispatchers.IO) {
        try {
            sessionManager.getSession(id)
        } catch (e: Exception) {
            noteFailure(e, "session storage failed")
            null
        }
    }

    suspend fun renameSession(id: String, newTitle: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                sessionManager.renameSession(id, newTitle)
            } catch (e: Exception) {
                noteFailure(e, "session storage failed")
                false
            }
        }

    suspend fun deleteSession(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deleted = sessionManager.deleteSession(id)
            if (deleted && currentSessionId == id) setCurrent(null)
            clearFailure()
            deleted
        } catch (e: Exception) {
            noteFailure(e, "session storage failed")
            false
        }
    }

    fun clearError() {
        clearFailure()
    }

    private fun noteFailure(e: Exception, default: String) {
        lastError = e.message ?: default
        lastErrorType = e.javaClass.simpleName
    }

    private fun clearFailure() {
        lastError = null
        lastErrorType = null
    }

    /**
     * Sanitized health snapshot for debug logs and bug reports. Never throws
     * and never contains message content, filesystem paths, keys, or secrets.
     */
    suspend fun diagnostics(): SessionDiagnostics = withContext(Dispatchers.IO) {
        try {
            val dir = (store as? FileSessionStore)?.sessionsDir()
            val current = currentSessionId?.let {
                try {
                    store.getWithHistory(it)
                } catch (e: Exception) {
                    null
                }
            }
            val messages = current?.messages.orEmpty()
            SessionDiagnostics(
                rootHash = sha256Hex(canonicalRoot()),
                sessionsDirExists = dir?.isDirectory == true,
                sessionsDirWritable = dir?.canWrite() == true,
                sessionFileCount = dir?.listFiles { f -> f.extension == "json" }?.size ?: 0,
                hasCurrentSession = current != null,
                currentUserMessages = messages.count { it.role == MessageRole.USER },
                currentAssistantMessages = messages.count { it.role == MessageRole.ASSISTANT },
                lastErrorType = lastErrorType
            )
        } catch (e: Exception) {
            SessionDiagnostics.unavailable()
        }
    }

    private fun sha256Hex(text: String): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "unavailable"
    }

    private fun setCurrent(id: String?) {
        currentSessionId = id
        try {
            saveCurrentId(id)
        } catch (e: Exception) {
            // Preference persistence is best-effort; the in-memory ID still rules.
        }
    }

    private fun canonicalRoot(): String = try {
        projectRoot.canonicalPath
    } catch (e: Exception) {
        projectRoot.absolutePath
    }
}
