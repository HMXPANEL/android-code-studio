package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.ConversationAdapter
import com.tom.rv2ide.adapters.HistoryAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.session.AgentSessionCoordinator
import com.tom.rv2ide.artificial.session.toConversationRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 *
 * Two clearly separated sections:
 * - "Saved conversations": persisted agent sessions from the project's
 *   `.opencode/sessions/` (chat text + tool history). Tapping one reopens it.
 * - "File changes": legacy in-memory file-modification attempts. These are
 *   NOT chat history and are labeled as such; they vanish on process death.
 */
class AIHistoryFragment : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private var projectRoot: File? = null
    private var onSessionSelected: ((String) -> Unit)? = null

    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var conversationsRecyclerView: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var conversationsStatus: TextView

    companion object {
        fun newInstance(
            aiAgent: AIAgentManager,
            projectRoot: File? = null,
            onSessionSelected: ((String) -> Unit)? = null
        ): AIHistoryFragment {
            return AIHistoryFragment().apply {
                this.aiAgent = aiAgent
                this.projectRoot = projectRoot
                this.onSessionSelected = onSessionSelected
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!::aiAgent.isInitialized) {
            aiAgent = AIAgentManager(requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        historyRecyclerView = view.findViewById(R.id.historyRecyclerView)
        historyAdapter = HistoryAdapter()

        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }

        conversationsStatus = view.findViewById(R.id.conversationsStatus)
        conversationsRecyclerView = view.findViewById(R.id.conversationsRecyclerView)
        conversationAdapter = ConversationAdapter { id -> onSessionSelected?.invoke(id) }
        conversationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = conversationAdapter
        }

        loadHistory()
        loadConversations()
    }

    override fun onResume() {
        super.onResume()
        // Fresh query every time: a run that finished while this screen was
        // open appears on return without any refresh plumbing.
        loadHistory()
        loadConversations()
    }

    private fun loadHistory() {
        val history = aiAgent.getConversationHistory()
        historyAdapter.setItems(history)
    }

    private fun loadConversations() {
        val root = projectRoot
        if (root == null) {
            showConversationStatus(getString(R.string.ai_history_sessions_unavailable))
            conversationAdapter.setItems(emptyList())
            return
        }
        lifecycleScope.launch {
            // One IO block: coordinator construction scans the session
            // directory, so it must not run on the main thread. Read-only
            // use here: list/corrupt only, never create or resume.
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val coordinator = AgentSessionCoordinator(root, lifecycleScope)
                    Triple(
                        coordinator.listSessions().map { it.toConversationRow() },
                        coordinator.corruptRecords(),
                        root.canWrite()
                    )
                }
            } catch (e: Exception) {
                null
            }
            if (!isAdded) return@launch
            if (loaded == null) {
                showConversationStatus(getString(R.string.ai_history_sessions_unavailable))
                conversationAdapter.setItems(emptyList())
                return@launch
            }
            val (rows, corrupt, writable) = loaded
            conversationAdapter.setItems(rows.take(50))
            when {
                rows.isEmpty() && corrupt.isNotEmpty() -> showConversationStatus(
                    getString(R.string.ai_history_sessions_corrupt, corrupt.size)
                )
                rows.isEmpty() && !writable -> showConversationStatus(
                    getString(R.string.ai_history_project_readonly)
                )
                rows.isEmpty() -> showConversationStatus(
                    getString(R.string.ai_chat_sessions_empty)
                )
                corrupt.isNotEmpty() -> showConversationStatus(
                    getString(R.string.ai_history_sessions_corrupt, corrupt.size)
                )
                else -> hideConversationStatus()
            }
        }
    }

    private fun showConversationStatus(text: String) {
        conversationsStatus.text = text
        conversationsStatus.visibility = View.VISIBLE
    }

    private fun hideConversationStatus() {
        conversationsStatus.visibility = View.GONE
    }
}
