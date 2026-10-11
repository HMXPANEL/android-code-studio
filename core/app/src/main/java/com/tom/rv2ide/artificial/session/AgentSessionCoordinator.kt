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
    val sessionManager: SessionManager =
        SessionManager(store, contextEngine, indexer, relevanceEngine, scope)

    @Volatile
    var currentSessionId: String? = null
        private set

    /** Last storage failure, or null when the last operation succeeded. */
    @Volatile
    var lastError: String? = null
        private set

    init {
        currentSessionId = try {
            loadCurrentId()
        } catch (e: Exception) {
            null
        }
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
                    lastError = null
                    return@withContext remembered
                }
                val created = sessionManager.createSession(projectRoot, mode, providerId, modelId)
                setCurrent(created.id)
                lastError = null
                created.id
            } catch (e: Exception) {
                lastError = e.message ?: "session storage failed"
                null
            }
        }

    /** Starts a brand-new conversation, abandoning (not deleting) the current one. */
    suspend fun startNewSession(mode: RunMode, providerId: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val created = sessionManager.createSession(projectRoot, mode, providerId, modelId)
                setCurrent(created.id)
                lastError = null
                created.id
            } catch (e: Exception) {
                lastError = e.message ?: "session storage failed"
                null
            }
        }

    /** Drops the current conversation pointer without deleting stored history. */
    suspend fun forgetCurrent() = withContext(Dispatchers.IO) {
        setCurrent(null)
        lastError = null
    }

    /** Switches to an existing session. Returns false when it does not exist. */
    suspend fun switchTo(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = sessionManager.getSession(id) ?: return@withContext false
            if (session.projectRoot != canonicalRoot()) return@withContext false
            setCurrent(session.id)
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e.message ?: "session storage failed"
            false
        }
    }

    suspend fun listSessions(): List<AgentSession> = withContext(Dispatchers.IO) {
        try {
            val sessions = sessionManager.listSessions(projectRoot)
            lastError = null
            sessions
        } catch (e: Exception) {
            lastError = e.message ?: "session storage failed"
            emptyList()
        }
    }

    suspend fun history(id: String): AgentSession? = withContext(Dispatchers.IO) {
        try {
            sessionManager.getSession(id)
        } catch (e: Exception) {
            lastError = e.message ?: "session storage failed"
            null
        }
    }

    suspend fun renameSession(id: String, newTitle: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                sessionManager.renameSession(id, newTitle)
            } catch (e: Exception) {
                lastError = e.message ?: "session storage failed"
                false
            }
        }

    suspend fun deleteSession(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deleted = sessionManager.deleteSession(id)
            if (deleted && currentSessionId == id) setCurrent(null)
            lastError = null
            deleted
        } catch (e: Exception) {
            lastError = e.message ?: "session storage failed"
            false
        }
    }

    fun clearError() {
        lastError = null
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
