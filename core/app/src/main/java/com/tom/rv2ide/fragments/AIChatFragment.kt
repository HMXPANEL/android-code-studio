package com.tom.rv2ide.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.fragments.sidebar.AIAgentViewModel
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * New AI Chat screen body: scrollable conversation (user right / AI left),
 * rounded composer with image attachment, capabilities menu, Build/Plan mode
 * selector and a send button wired to the real [AIAgentManager] request state.
 *
 * All AI behavior (providers, project context, file modification, history
 * storage, code completion) is reused, not reimplemented.
 */
class AIChatFragment : Fragment() {

  private lateinit var aiAgent: AIAgentManager
  private lateinit var viewModel: AIAgentViewModel
  private lateinit var messageList: RecyclerView
  private lateinit var emptyState: View
  private lateinit var chatInput: TextInputEditText
  private lateinit var sendBtn: MaterialButton
  private lateinit var sendProgress: CircularProgressIndicator
  private lateinit var attachBtn: MaterialButton
  private lateinit var moreBtn: MaterialButton
  private lateinit var modeBtn: MaterialButton
  private lateinit var attachmentRow: View
  private lateinit var attachmentPreview: ImageView
  private lateinit var removeAttachmentBtn: MaterialButton
  private lateinit var messageAdapter: ChatMessageAdapter

  private lateinit var codeCompletionManager: CodeCompletionManager

  private var executionJob: Job? = null
  private var fileMonitorJob: Job? = null
  private var completionStateMonitorJob: Job? = null
  private var lastMonitoredFile: File? = null
  private var isSettingUpCompletion = false

  private val userRootProject: String
    get() = try {
      getProjectRoot().absolutePath.toString()
    } catch (e: Exception) {
      ""
    }

  private val imagePicker =
      registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
          try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
          } catch (e: Exception) {
            // Non-persistable Uris (e.g. from some pickers) still work while alive.
          }
          viewModel.setPendingAttachment(uri.toString())
        }
      }

  private val sharedPrefsListener =
      SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "code_completion_enabled") {
          lifecycleScope.launch {
            handleCompletionStateChange(prefs.getBoolean(key, true))
          }
        }
      }

  companion object {
    private const val PREFS_CHAT = "ai_chat_prefs"
    private const val KEY_AGENT_MODE = "agent_mode"

    fun newInstance(aiAgent: AIAgentManager): AIChatFragment {
      return AIChatFragment().apply { this.aiAgent = aiAgent }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!::aiAgent.isInitialized) {
      aiAgent = AIAgentManager(requireContext())
    }
    viewModel = ViewModelProvider(this)[AIAgentViewModel::class.java]
    restoreAgentMode()
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
  ): View? {
    return inflater.inflate(R.layout.fragment_ai_chat, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    messageList = view.findViewById(R.id.chatMessageList)
    emptyState = view.findViewById(R.id.chatEmptyState)
    chatInput = view.findViewById(R.id.chatInput)
    sendBtn = view.findViewById(R.id.sendBtn)
    sendProgress = view.findViewById(R.id.sendProgress)
    attachBtn = view.findViewById(R.id.attachBtn)
    moreBtn = view.findViewById(R.id.moreBtn)
    modeBtn = view.findViewById(R.id.modeBtn)
    attachmentRow = view.findViewById(R.id.attachmentPreviewRow)
    attachmentPreview = view.findViewById(R.id.attachmentPreview)
    removeAttachmentBtn = view.findViewById(R.id.removeAttachmentBtn)

    messageAdapter = ChatMessageAdapter(onFileClick = { openFileInEditor(it) })
    messageList.layoutManager = LinearLayoutManager(requireContext())
    messageList.adapter = messageAdapter

    codeCompletionManager =
        CodeCompletionManager.getInstance(requireContext(), lifecycleScope, aiAgent)

    observeState()
    setupListeners()
    loadProject()
    registerPreferenceListener()
    updateModeButton()
  }

  override fun onResume() {
    super.onResume()
    startFileMonitoring()
    startCompletionStateMonitoring()
  }

  override fun onPause() {
    super.onPause()
    stopFileMonitoring()
    stopCompletionStateMonitoring()
  }

  fun getViewModel(): AIAgentViewModel = viewModel

  fun getCodeCompletionManager(): CodeCompletionManager = codeCompletionManager

  // ---------------------------------------------------------------------------
  // UI state
  // ---------------------------------------------------------------------------

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

    moreBtn.setOnClickListener { anchor -> showCapabilitiesMenu(anchor) }

    modeBtn.setOnClickListener { anchor -> showModeMenu(anchor) }
  }

  // ---------------------------------------------------------------------------
  // Sending — tied to the real AIAgentManager request state
  // ---------------------------------------------------------------------------

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
    codeCompletionManager.clearSuggestion()

    executeRequest(text)
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

          override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
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
            viewModel.updateWorkingMessage(workingIndex, "🔄 Retry #$attemptNumber: $message")
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
      val type = if (detail.changeType == AIAgentManager.ChangeType.CREATED) "Created" else "Modified"
      builder.append("$icon $type: ${detail.fileName}\n")
    }
    return builder.toString().trim()
  }

  fun clearConversation() {
    executionJob?.cancel()
    viewModel.setWorking(false)
    codeCompletionManager.clearSuggestion()
    lifecycleScope.launch {
      try {
        aiAgent.clearConversation()
        chatInput.text?.clear()
        viewModel.setPendingAttachment(null)
        viewModel.clearMessages()
        showSnackbar(getString(R.string.ai_chat_cleared))
      } catch (e: Exception) {
        showSnackbar("Error clearing: ${e.message}")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Composer menus
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // Project context + code completion (same wiring as the previous chat UI)
  // ---------------------------------------------------------------------------

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
      codeCompletionManager.cleanup()
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
      if (editor != null && suggestionView != null) {
        codeCompletionManager.setup(
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
    super.onDestroyView()
  }
}
