package com.tom.rv2ide.fragments.sidebar

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agent.AgentController
import com.tom.rv2ide.artificial.agent.AgentEvents
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.builtins.AgentTools
import com.tom.rv2ide.fragments.AIHistoryFragment
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.ui.CodeEditorView
import com.tom.rv2ide.utils.EditorSidebarActions
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * AI Agent full-screen chat destination.
 *
 * The second-last bottom-navigation button opens this fragment directly. The
 * header (back button, "AI Agent", current project name, menu), the
 * conversation and the composer all live in this fragment's own view
 * hierarchy — there is no nested chat fragment and no parent AI Agent page,
 * so the chat always occupies the full available content area.
 *
 * All AI behavior (providers, project context, file modification, history
 * storage, code completion) is reused, not reimplemented. Conversation
 * history and provider settings open as overlays using the existing
 * fragments.
 */
class ArtificialFragment(
    private val editorView: CodeEditorView? = null
) : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private lateinit var agents: Agents
    private lateinit var viewModel: AIAgentViewModel

    // Header.
    private lateinit var chatContainer: View
    private lateinit var contentContainer: View
    private lateinit var projectNameText: MaterialTextView
    private lateinit var backBtn: MaterialButton
    private lateinit var moreBtn: MaterialButton

    // Conversation + composer (inflated from fragment_ai_chat into chatContainer).
    private lateinit var messageList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var chatInput: TextInputEditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var sendProgress: CircularProgressIndicator
    private lateinit var attachBtn: MaterialButton
    private lateinit var composerMoreBtn: MaterialButton
    private lateinit var modeBtn: MaterialButton
    private lateinit var attachmentRow: View
    private lateinit var attachmentPreview: ImageView
    private lateinit var removeAttachmentBtn: MaterialButton
    private lateinit var runStatusText: MaterialTextView
    private lateinit var messageAdapter: ChatMessageAdapter

    private var codeCompletionManager: CodeCompletionManager? = null
    private lateinit var imagePicker: ActivityResultLauncher<String>

    private var executionJob: Job? = null
    private var fileMonitorJob: Job? = null
    private var completionStateMonitorJob: Job? = null
    private var lastMonitoredFile: File? = null
    private var isSettingUpCompletion = false

    private var agentController: AgentController? = null

    /** Phase 1 agent loop entry. Default OFF: legacy one-shot path stays default. */
    private fun isAgentLoopEnabled(): Boolean {
        return try {
            requireContext().getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOOP_ENABLED, false)
        } catch (e: Exception) {
            false
        }
    }

    private var savedContentContainerVisibility = View.GONE

    private val userRootProject: String
        get() = try {
            getProjectRoot().absolutePath.toString()
        } catch (e: Exception) {
            ""
        }

    private val sharedPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "code_completion_enabled") {
                lifecycleScope.launch {
                    handleCompletionStateChange(prefs.getBoolean(key, true))
                }
            }
        }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showMainContent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            savedContentContainerVisibility = it.getInt(KEY_CONTENT_VISIBILITY, View.GONE)
        }
        imagePicker =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // Non-persistable Uris still work while alive.
                    }
                    viewModel.setPendingAttachment(uri.toString())
                }
            }
        viewModel = ViewModelProvider(this)[AIAgentViewModel::class.java]
        restoreAgentMode()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_artificial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        aiAgent = AIAgentManager(requireContext())
        agents = Agents(requireContext())

        chatContainer = view.findViewById(R.id.chatContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        projectNameText = view.findViewById(R.id.aiProjectName)
        backBtn = view.findViewById(R.id.aiBackBtn)
        moreBtn = view.findViewById(R.id.aiMoreBtn)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        projectNameText.text = resolveProjectName()

        backBtn.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        moreBtn.setOnClickListener { anchor -> showHeaderMenu(anchor) }

        // Inflate the chat (conversation + composer) directly into this
        // fragment's own hierarchy so it is always present and full-screen.
        layoutInflater.inflate(R.layout.fragment_ai_chat, chatContainer as ViewGroup, true)
        chatContainer.visibility = View.VISIBLE

        messageList = view.findViewById(R.id.chatMessageList)
        emptyState = view.findViewById(R.id.chatEmptyState)
        chatInput = view.findViewById(R.id.chatInput)
        sendBtn = view.findViewById(R.id.sendBtn)
        sendProgress = view.findViewById(R.id.sendProgress)
        attachBtn = view.findViewById(R.id.attachBtn)
        composerMoreBtn = view.findViewById(R.id.moreBtn)
        modeBtn = view.findViewById(R.id.modeBtn)
        attachmentRow = view.findViewById(R.id.attachmentPreviewRow)
        attachmentPreview = view.findViewById(R.id.attachmentPreview)
        removeAttachmentBtn = view.findViewById(R.id.removeAttachmentBtn)
        runStatusText = view.findViewById(R.id.runStatusText)

        messageAdapter = ChatMessageAdapter(onFileClick = { openFileInEditor(it) })
        messageList.layoutManager = LinearLayoutManager(requireContext())
        messageList.adapter = messageAdapter

        try {
            codeCompletionManager =
                CodeCompletionManager.getInstance(requireContext(), lifecycleScope, aiAgent)
        } catch (e: Exception) {
            codeCompletionManager = null
        }

        observeState()
        setupListeners()
        loadProject()
        registerPreferenceListener()
        updateModeButton()

        if (savedContentContainerVisibility == View.VISIBLE) {
            chatContainer.visibility = View.GONE
            contentContainer.visibility = View.VISIBLE
            backPressedCallback.isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (::projectNameText.isInitialized) {
            projectNameText.text = resolveProjectName()
        }
        startFileMonitoring()
        startCompletionStateMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopFileMonitoring()
        stopCompletionStateMonitoring()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::contentContainer.isInitialized) {
            outState.putInt(KEY_CONTENT_VISIBILITY, contentContainer.visibility)
        }
    }

    private fun resolveProjectName(): String {
        return try {
            val name = getProjectRoot().name
            if (name.isBlank()) getString(R.string.ai_chat_no_project) else name
        } catch (e: Exception) {
            getString(R.string.ai_chat_no_project)
        }
    }

    // --------------------------------------------------------------------------
    // Header menu (real actions only).
    // --------------------------------------------------------------------------

    private fun showHeaderMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_HISTORY, 1, getString(R.string.ai_chat_menu_history))
        popup.menu.add(0, MENU_SETTINGS, 2, getString(R.string.ai_chat_menu_settings))
        popup.menu.add(0, MENU_UNDO, 3, getString(R.string.ai_chat_menu_undo))
        popup.menu.add(0, MENU_CLEAR, 4, getString(R.string.ai_chat_menu_clear))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HISTORY -> {
                    openHistory()
                    true
                }
                MENU_SETTINGS -> {
                    openAIPreferences()
                    true
                }
                MENU_UNDO -> {
                    undoLastModification()
                    true
                }
                MENU_CLEAR -> {
                    clearConversation()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun undoLastModification() {
        val success = aiAgent.undoLastModification()
        view?.let {
            Snackbar.make(
                it,
                if (success) {
                    getString(R.string.ai_chat_undo_done)
                } else {
                    getString(R.string.ai_chat_undo_empty)
                },
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun openAIPreferences() {
        showOverlay(
            AIPreferencesFragment(aiAgent, agents, codeCompletionManager),
            "ai_preferences"
        )
    }

    private fun openHistory() {
        showOverlay(AIHistoryFragment.newInstance(aiAgent), "ai_history")
    }

    private fun showOverlay(fragment: Fragment, backStackName: String) {
        val slideIn = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left)
        val slideOut = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right)

        chatContainer.startAnimation(slideOut)
        chatContainer.visibility = View.GONE

        contentContainer.visibility = View.VISIBLE
        contentContainer.startAnimation(slideIn)
        backPressedCallback.isEnabled = true

        childFragmentManager.commit {
            replace(R.id.contentContainer, fragment)
            addToBackStack(backStackName)
        }
    }

    private fun showMainContent() {
        val slideIn = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left)
        val slideOut = AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right)

        contentContainer.startAnimation(slideOut)
        contentContainer.visibility = View.GONE

        chatContainer.visibility = View.VISIBLE
        chatContainer.startAnimation(slideIn)

        backPressedCallback.isEnabled = false

        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
        }
    }

    // --------------------------------------------------------------------------
    // Conversation + composer state.
    // --------------------------------------------------------------------------

    private fun observeState() {
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            messageAdapter.submitList(messages)
            emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            if (messages.isNotEmpty()) {
                messageList.scrollToPosition(messages.size - 1)
            }
        }

        viewModel.isWorking.observe(viewLifecycleOwner) { working ->
            sendBtn.isEnabled = !working
            sendBtn.alpha = if (working) 0.4f else 1f
            sendProgress.visibility = if (working) View.VISIBLE else View.GONE
        }

        viewModel.pendingAttachment.observe(viewLifecycleOwner) { uri ->
            if (uri != null) {
                attachmentRow.visibility = View.VISIBLE
                attachmentPreview.setImageURI(uri.toUri())
            } else {
                attachmentRow.visibility = View.GONE
                attachmentPreview.setImageDrawable(null)
            }
        }

        viewModel.agentMode.observe(viewLifecycleOwner) { updateModeButton() }

        viewModel.runStatus.observe(viewLifecycleOwner) { status ->
            if (status.isNullOrBlank()) {
                runStatusText.visibility = View.GONE
            } else {
                runStatusText.visibility = View.VISIBLE
                runStatusText.text = status
            }
        }
    }

    private fun setupListeners() {
        sendBtn.setOnClickListener { sendCurrentInput() }

        chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }

        attachBtn.setOnClickListener { imagePicker.launch("image/*") }

        removeAttachmentBtn.setOnClickListener { viewModel.setPendingAttachment(null) }

        composerMoreBtn.setOnClickListener { anchor -> showCapabilitiesMenu(anchor) }

        modeBtn.setOnClickListener { anchor -> showModeMenu(anchor) }
    }

    private fun sendCurrentInput() {
        if (viewModel.isWorking.value == true) return

        val text = chatInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            showSnackbar(getString(R.string.ai_chat_empty_request))
            return
        }

        val attachment = viewModel.pendingAttachment.value
        viewModel.setWorking(true)
        sendBtn.isEnabled = false
        viewModel.addUserMessage(text, attachment)
        chatInput.text?.clear()
        viewModel.setPendingAttachment(null)
        codeCompletionManager?.clearSuggestion()

        // Capture the mode synchronously from storage at send time. Reading
        // LiveData.value here is racy (mode changes post with postValue), which
        // previously let a run start under the wrong profile.
        val mode = readPersistedMode()
        android.util.Log.d(TAG_AGENT, "AGENT_MODE selected=$mode loop=${isAgentLoopEnabled()}")

        // A previous run (if any) is cancelled before the new one starts.
        if (executionJob?.isActive == true) {
            android.util.Log.d(TAG_AGENT, "RUN_CANCEL_REQUESTED reason=new-message")
            agentController?.cancelActiveRun()
        }

        if (isAgentLoopEnabled() || mode == AIAgentViewModel.AgentMode.PLAN) {
            // PLAN must always take the enforcing loop path: the legacy
            // one-shot path has no mode concept and would write freely.
            runAgentLoop(text, mode)
        } else {
            executeRequest(text)
        }
    }

    /**
     * Synchronous mode read for run creation. Never use LiveData.value here:
     * mode changes are posted asynchronously and would race with send.
     */
    private fun readPersistedMode(): AIAgentViewModel.AgentMode {
        return try {
            val saved = requireContext()
                .getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
                .getString(KEY_AGENT_MODE, AIAgentViewModel.AgentMode.BUILD.name)
            runCatching { AIAgentViewModel.AgentMode.valueOf(saved!!) }
                .getOrDefault(AIAgentViewModel.AgentMode.BUILD)
        } catch (e: Exception) {
            AIAgentViewModel.AgentMode.BUILD
        }
    }

    private var lastRunId: String? = null

    /**
     * Phase 1 agent-loop entry: drives the controller and maps AgentEvents
     * onto the existing chat UI (working bubble, file activity rows, final
     * text, errors). Legacy executeRequest() below stays the default path.
     */
    private fun runAgentLoop(userRequest: String, uiMode: AIAgentViewModel.AgentMode) {
        if (userRootProject.isBlank()) {
            showSnackbar("Project path not set")
            viewModel.setWorking(false)
            sendBtn.isEnabled = true
            return
        }
        executionJob?.cancel()
        executionJob = lifecycleScope.launch {
            viewModel.setWorking(true)
            val workingIndex = viewModel.startWorkingMessage(getString(R.string.ai_chat_working))
            try {
                AgentTools.registerAll(requireContext().applicationContext)
                val controller = agentController
                    ?: AgentController(aiAgent).also { agentController = it }
                val mode = if (uiMode == AIAgentViewModel.AgentMode.PLAN) {
                    RunMode.PLAN
                } else {
                    RunMode.BUILD
                }
                controller.runAgent(
                    userRequest = userRequest,
                    mode = mode,
                    projectRoot = File(userRootProject),
                    events = { event -> onAgentEvent(event, workingIndex) },
                    onConfirm = { call -> askToolApproval(call) }
                )
            } catch (e: CancellationException) {
                android.util.Log.d(
                    TAG_AGENT,
                    "TOOL_CANCELLED lastTool=${viewModel.runStatus.value} run=$lastRunId"
                )
                viewModel.finalizeWorkingMessage(workingIndex, "Run cancelled.")
            } catch (e: Exception) {
                viewModel.failWorkingMessage(workingIndex, "❌ Error: ${e.message}")
            } finally {
                viewModel.setWorking(false)
                viewModel.setRunStatus(null)
            }
        }
    }

    private fun onAgentEvent(event: AgentEvents, workingIndex: Int) {
        when (event) {
            is AgentEvents.RunStarted -> {
                lastRunId = event.runId
                android.util.Log.d(
                    TAG_AGENT,
                    "RUN_CREATED id=${event.runId} mode=${event.mode}"
                )
            }
            is AgentEvents.Thinking ->
                viewModel.updateWorkingMessage(workingIndex, event.status)
            is AgentEvents.ToolsProposed ->
                event.calls.firstOrNull()?.let {
                    viewModel.setRunStatus(it.name)
                }
            is AgentEvents.ApprovalRequired ->
                viewModel.updateWorkingMessage(
                    workingIndex,
                    "Waiting for approval: ${event.call.name}…"
                )
            is AgentEvents.ToolStarted -> {
                android.util.Log.d(
                    TAG_AGENT,
                    "TOOL_STARTED id=${event.call.callId} name=${event.call.name} run=$lastRunId"
                )
                viewModel.updateWorkingMessage(workingIndex, "Running ${event.call.name}…")
                viewModel.setRunStatus(event.call.name)
                (event.call.args["path"] as? String)?.let { path ->
                    val fileName = try {
                        File(path).name
                    } catch (e: Exception) {
                        path
                    }
                    viewModel.addFileActivity(workingIndex, fileName)
                }
            }
            is AgentEvents.ToolFinished -> {
                (event.call.args["path"] as? String)?.let { path ->
                    val fileName = try {
                        File(path).name
                    } catch (e: Exception) {
                        path
                    }
                    viewModel.updateFileActivity(workingIndex, fileName, event.result.ok)
                }
                if (event.result.ok) {
                    maybeOpenModifiedFile(event.call)
                }
            }
            is AgentEvents.ModelReply -> { /* thinking continues; nothing to show */ }
            is AgentEvents.FinalAnswer -> {
                android.util.Log.d(
                    TAG_AGENT,
                    "RUN_FINAL_STATE state=DONE run=$lastRunId legacy=${event.legacyModifications}"
                )
                viewModel.finalizeWorkingMessage(workingIndex, event.text)
            }
            is AgentEvents.Failed -> {
                android.util.Log.d(
                    TAG_AGENT,
                    "RUN_FINAL_STATE state=FAILED run=$lastRunId reason=${event.reason.take(200)}"
                )
                viewModel.failWorkingMessage(workingIndex, event.reason)
            }
            is AgentEvents.Cancelled -> {
                android.util.Log.d(
                    TAG_AGENT,
                    "RUN_FINAL_STATE state=CANCELLED run=$lastRunId steps=${event.partialSteps}"
                )
                viewModel.setRunStatus(null)
                viewModel.finalizeWorkingMessage(workingIndex, "Run cancelled.")
            }
            is AgentEvents.Retrying ->
                viewModel.updateWorkingMessage(
                    workingIndex,
                    "🔄 Retry #${event.attempt}: ${event.message}"
                )
        }
    }

    private fun maybeOpenModifiedFile(call: ToolCall) {
        val path = call.args["path"] as? String ?: return
        if (!call.name.endsWith("write_file") && !call.name.endsWith("edit_file")) return
        lifecycleScope.launch {
            try {
                val root = File(userRootProject)
                val file = if (File(path).isAbsolute) {
                    File(path)
                } else {
                    File(root, path)
                }
                if (file.exists() && file.isFile) {
                    openFileInEditor(file.name)
                    refreshCurrentEditor()
                }
            } catch (e: Exception) {
                // Best effort only.
            }
        }
    }

    private suspend fun askToolApproval(call: ToolCall): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val argsSummary = call.args.entries.joinToString("\n") { (k, v) ->
            val rendered = v.toString().take(300)
            "$k: $rendered"
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Allow tool '${call.name}'?")
            .setMessage(argsSummary.ifBlank { "No arguments." })
            .setPositiveButton("Allow") { _, _ ->
                if (!answer.complete(true)) {
                    answer.cancel()
                }
            }
            .setNegativeButton("Deny") { _, _ ->
                if (!answer.complete(false)) {
                    answer.cancel()
                }
            }
            .setOnCancelListener {
                answer.cancel()
            }
            .show()
        return try {
            answer.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun executeRequest(userRequest: String) {
        executionJob?.cancel()
        executionJob = lifecycleScope.launch {
            viewModel.setWorking(true)
            val workingIndex = viewModel.startWorkingMessage(getString(R.string.ai_chat_working))
            try {
                aiAgent.executeRequest(userRequest, object : AIAgentManager.AIAgentCallback {
                    override fun onProcessing(message: String) {
                        viewModel.updateWorkingMessage(workingIndex, message)
                    }

                    override fun onFileModifying(filePath: String, fileName: String) {
                        viewModel.addFileActivity(workingIndex, fileName)
                    }

                    override fun onFileModified(
                        filePath: String,
                        fileName: String,
                        success: Boolean
                    ) {
                        viewModel.updateFileActivity(workingIndex, fileName, success)
                        if (getCurrentFile()?.name == fileName && success) {
                            refreshCurrentEditor()
                        }
                    }

                    override fun onSuccess(
                        response: String,
                        modifications: List<AIAgentManager.ModificationResult>,
                        summary: AIAgentManager.ModificationSummary
                    ) {
                        val finalText = buildString {
                            if (response.isNotBlank()) append(response)
                            val summaryText = buildSummaryText(summary)
                            if (summaryText.isNotBlank()) {
                                if (isNotEmpty()) append("\n\n")
                                append(summaryText)
                            }
                        }.ifBlank { getString(R.string.ai_chat_working) }
                        viewModel.finalizeWorkingMessage(workingIndex, finalText)

                        if (modifications.isNotEmpty()) {
                            val file = File(modifications.first().filePath)
                            if (file.exists()) {
                                openFileInEditor(file.name)
                            }
                        }
                    }

                    override fun onTextResponse(
                        response: String,
                        summary: AIAgentManager.ModificationSummary
                    ) {
                        viewModel.finalizeWorkingMessage(workingIndex, response)
                    }

                    override fun onError(message: String) {
                        viewModel.failWorkingMessage(workingIndex, message)
                    }

                    override fun onRetry(attemptNumber: Int, message: String) {
                        viewModel.updateWorkingMessage(
                            workingIndex,
                            "🔄 Retry #$attemptNumber: $message"
                        )
                    }
                })
            } catch (e: Exception) {
                viewModel.failWorkingMessage(workingIndex, "❌ Error: ${e.message}")
            } finally {
                viewModel.setWorking(false)
            }
        }
    }

    private fun buildSummaryText(summary: AIAgentManager.ModificationSummary): String {
        if (summary.totalFiles == 0) return ""
        val builder = StringBuilder()
        builder.append("📊 Total Files: ${summary.totalFiles}\n")
        builder.append("✅ Successful: ${summary.successfulFiles}\n")
        if (summary.failedFiles > 0) {
            builder.append("❌ Failed: ${summary.failedFiles}\n")
        }
        builder.append("🆕 New Files: ${summary.newFiles}\n")
        builder.append("✏️ Modified Files: ${summary.modifiedFiles}\n")
        summary.fileDetails.forEach { detail ->
            val icon = if (detail.status == AIAgentManager.FileStatus.SUCCESS) "✅" else "❌"
            val type =
                if (detail.changeType == AIAgentManager.ChangeType.CREATED) "Created"
                else "Modified"
            builder.append("$icon $type: ${detail.fileName}\n")
        }
        return builder.toString().trim()
    }

    fun clearConversation() {
        android.util.Log.d(TAG_AGENT, "RUN_CANCEL_REQUESTED reason=clear-conversation")
        agentController?.cancelActiveRun()
        executionJob?.cancel()
        viewModel.setWorking(false)
        viewModel.setRunStatus(null)
        codeCompletionManager?.clearSuggestion()
        lifecycleScope.launch {
            try {
                aiAgent.clearConversation()
                if (::chatInput.isInitialized) chatInput.text?.clear()
                viewModel.setPendingAttachment(null)
                viewModel.clearMessages()
                showSnackbar(getString(R.string.ai_chat_cleared))
            } catch (e: Exception) {
                showSnackbar("Error clearing: ${e.message}")
            }
        }
    }

    /** Extension point for future agent capabilities. All entries are Coming Soon. */
    private fun showCapabilitiesMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(Menu.NONE, 1, 1, "MCP").isEnabled = false
        popup.menu.add(Menu.NONE, 2, 2, "Skills").isEnabled = false
        popup.menu.add(Menu.NONE, 3, 3, "Tools").isEnabled = false
        popup.menu.add(Menu.NONE, 4, 4, "Sub-agents").isEnabled = false
        popup.setOnMenuItemClickListener { false }
        popup.show()
    }

    private fun showModeMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val buildItem = popup.menu.add(
            Menu.NONE, 1, 1,
            getString(R.string.ai_chat_mode_build)
        )
        val planItem = popup.menu.add(
            Menu.NONE, 2, 2,
            getString(R.string.ai_chat_mode_plan)
        )
        buildItem.isCheckable = true
        planItem.isCheckable = true
        val current = viewModel.agentMode.value ?: AIAgentViewModel.AgentMode.BUILD
        buildItem.isChecked = current == AIAgentViewModel.AgentMode.BUILD
        planItem.isChecked = current == AIAgentViewModel.AgentMode.PLAN
        popup.setOnMenuItemClickListener { item ->
            val mode = if (item.itemId == 2) {
                AIAgentViewModel.AgentMode.PLAN
            } else {
                AIAgentViewModel.AgentMode.BUILD
            }
            viewModel.setAgentMode(mode)
            persistAgentMode(mode)
            true
        }
        popup.show()
    }

    private fun updateModeButton() {
        if (!::modeBtn.isInitialized) return
        val mode = viewModel.agentMode.value ?: AIAgentViewModel.AgentMode.BUILD
        val label = if (mode == AIAgentViewModel.AgentMode.PLAN) {
            getString(R.string.ai_chat_mode_plan)
        } else {
            getString(R.string.ai_chat_mode_build)
        }
        modeBtn.text = "$label ▾"
    }

    private fun restoreAgentMode() {
        val saved = requireContext()
            .getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_MODE, AIAgentViewModel.AgentMode.BUILD.name)
        viewModel.setAgentMode(
            runCatching { AIAgentViewModel.AgentMode.valueOf(saved!!) }
                .getOrDefault(AIAgentViewModel.AgentMode.BUILD)
        )
    }

    private fun persistAgentMode(mode: AIAgentViewModel.AgentMode) {
        requireContext()
            .getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_MODE, mode.name)
            .apply()
    }

    // --------------------------------------------------------------------------
    // Project context + code completion (same wiring as the previous chat UI).
    // --------------------------------------------------------------------------

    private fun loadProject() {
        lifecycleScope.launch {
            try {
                if (userRootProject.isBlank()) return@launch
                val success = aiAgent.setProjectRoot(userRootProject)
                if (!success) {
                    viewModel.finalizeWorkingMessage(
                        viewModel.startWorkingMessage(""),
                        getString(R.string.ai_chat_failed_to_load_project)
                    )
                }
            } catch (e: Exception) {
                viewModel.finalizeWorkingMessage(
                    viewModel.startWorkingMessage(""),
                    "Error loading project: ${e.message}"
                )
            }
        }
    }

    private fun registerPreferenceListener() {
        requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    private fun unregisterPreferenceListener() {
        requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    private suspend fun handleCompletionStateChange(enabled: Boolean) {
        if (enabled) {
            delay(200)
            if (getCurrentEditor() != null && getCurrentSuggestionView() != null) {
                setupCodeCompletionForCurrentFile()
            }
        } else {
            codeCompletionManager?.cleanup()
        }
    }

    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        completionStateMonitorJob = lifecycleScope.launch {
            var lastKnownState = requireContext()
                .getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .getBoolean("code_completion_enabled", true)
            while (true) {
                delay(200)
                val currentState = requireContext()
                    .getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                if (currentState != lastKnownState) {
                    lastKnownState = currentState
                    handleCompletionStateChange(currentState)
                }
            }
        }
    }

    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }

    private fun startFileMonitoring() {
        stopFileMonitoring()
        fileMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                if (isSettingUpCompletion) continue
                val prefs = requireContext()
                    .getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("code_completion_enabled", true)) continue
                val currentFile = getCurrentFile()
                if (currentFile != null && currentFile != lastMonitoredFile) {
                    lastMonitoredFile = currentFile
                    setupCodeCompletionForCurrentFile()
                }
            }
        }
    }

    private fun stopFileMonitoring() {
        fileMonitorJob?.cancel()
        fileMonitorJob = null
    }

    private fun setupCodeCompletionForCurrentFile() {
        if (isSettingUpCompletion) return
        val prefs = requireContext()
            .getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("code_completion_enabled", true)) return
        isSettingUpCompletion = true
        lifecycleScope.launch {
            delay(200)
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            val manager = codeCompletionManager
            if (editor != null && suggestionView != null && manager != null) {
                manager.setup(
                    editor,
                    suggestionView,
                    onReady = { isSettingUpCompletion = false },
                    onError = { isSettingUpCompletion = false }
                )
            } else {
                isSettingUpCompletion = false
            }
        }
    }

    private fun openFileInEditor(fileName: String) {
        if (userRootProject.isBlank()) {
            showSnackbar("Project path not set")
            return
        }
        lifecycleScope.launch {
            try {
                val file = File(userRootProject).walkTopDown()
                    .firstOrNull { it.isFile && it.name == fileName }
                if (file == null) {
                    showSnackbar("File not found: $fileName")
                    return@launch
                }
                val activity = requireActivity()
                if (activity is EditorHandlerActivity) {
                    activity.openFile(file)
                    showSnackbar("Opened: ${file.name}")
                    lastMonitoredFile = file
                    delay(500)
                    setupCodeCompletionForCurrentFile()
                }
            } catch (e: Exception) {
                showSnackbar("Error opening file: ${e.message}")
            }
        }
    }

    private fun getCurrentEditor() = try {
        (requireActivity() as? EditorHandlerActivity)?.getCurrentEditor()?.editor
    } catch (e: Exception) {
        null
    }

    private fun getCurrentFile() = try {
        (requireActivity() as? EditorHandlerActivity)?.getCurrentEditor()?.file
    } catch (e: Exception) {
        null
    }

    private fun getCurrentSuggestionView() = try {
        (requireActivity() as? EditorHandlerActivity)?.getCurrentEditor()?.suggestionView
    } catch (e: Exception) {
        null
    }

    private fun refreshCurrentEditor() {
        try {
            val currentEditor =
                (requireActivity() as? EditorHandlerActivity)?.getCurrentEditor() ?: return
            val newContent = currentEditor.file?.readText() ?: return
            currentEditor.editor?.text?.replace(0, currentEditor.editor!!.text.length, newContent)
        } catch (e: Exception) {
            // Best-effort refresh; editor may be gone.
        }
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content) ?: view ?: return
        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        executionJob?.cancel()
        fileMonitorJob?.cancel()
        completionStateMonitorJob?.cancel()
        unregisterPreferenceListener()
        backPressedCallback.remove()
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        EditorSidebarActions.removeFragmentFromCache("ide.editor.sidebar.ai_agent")
    }

    companion object {
        private const val TAG_AGENT = "HMXAgent"
        private const val PREFS_CHAT = "ai_chat_prefs"
        private const val KEY_AGENT_MODE = "agent_mode"
        private const val KEY_LOOP_ENABLED = "agent_loop_enabled"
        private const val KEY_CONTENT_VISIBILITY = "content_visibility"
        private const val MENU_HISTORY = 1
        private const val MENU_SETTINGS = 2
        private const val MENU_UNDO = 3
        private const val MENU_CLEAR = 4
    }
}
