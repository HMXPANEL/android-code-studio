package com.tom.rv2ide.fragments.sidebar

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * UI state holder for the AI Chat screen.
 *
 * The AI request itself is still executed through [com.tom.rv2ide.artificial.agents.AIAgentManager];
 * this ViewModel only owns presentation state: the message list, the send/working
 * state, the Build/Plan mode selected for the current chat, and the pending
 * image attachment.
 */
class AIAgentViewModel : ViewModel() {

  /** Execution mode selected for the current chat. Consumed by the future Agent Engine. */
  enum class AgentMode { BUILD, PLAN }

  /** Presentation kind of a chat message. ACTIVITY rows are reserved for future tool output. */
  enum class MessageKind { USER, AI, WORKING, ERROR }

  /** Status of a single file touched by the agent, shown inside an AI message card. */
  data class FileActivity(val fileName: String, var success: Boolean? = null)

  data class ChatMessage(
      var text: String,
      val isUser: Boolean,
      var isStreaming: Boolean,
      var kind: MessageKind = if (isUser) MessageKind.USER else MessageKind.AI,
      var attachmentUri: String? = null,
      val activity: MutableList<FileActivity> = mutableListOf()
  )

  private val _messages = MutableLiveData<MutableList<ChatMessage>>(mutableListOf())
  val messages: LiveData<MutableList<ChatMessage>> = _messages

  private val _isWorking = MutableLiveData(false)
  val isWorking: LiveData<Boolean> = _isWorking

  private val _agentMode = MutableLiveData(AgentMode.BUILD)
  val agentMode: LiveData<AgentMode> = _agentMode

  private val _pendingAttachment = MutableLiveData<String?>(null)
  val pendingAttachment: LiveData<String?> = _pendingAttachment

  /** Current agent-run status line (step/tool), null when idle. */
  private val _runStatus = MutableLiveData<String?>(null)
  val runStatus: LiveData<String?> = _runStatus

  fun setRunStatus(status: String?) {
    _runStatus.postValue(status)
  }

  fun addUserMessage(text: String, attachmentUri: String? = null) {
    val list = _messages.value ?: mutableListOf()
    list.add(ChatMessage(text, true, false, MessageKind.USER, attachmentUri))
    _messages.postValue(list)
  }

  /** Appends an AI "working" placeholder and returns its index for later updates. */
  fun startWorkingMessage(status: String): Int {
    val list = _messages.value ?: mutableListOf()
    list.add(ChatMessage(status, false, true, MessageKind.WORKING))
    _messages.postValue(list)
    return list.lastIndex
  }

  fun updateWorkingMessage(index: Int, status: String) {
    updateMessage(index, status, MessageKind.WORKING, true)
  }

  fun finalizeWorkingMessage(index: Int, text: String) {
    updateMessage(index, text, MessageKind.AI, false)
  }

  fun failWorkingMessage(index: Int, error: String) {
    updateMessage(index, error, MessageKind.ERROR, false)
  }

  private fun updateMessage(index: Int, text: String, kind: MessageKind, streaming: Boolean) {
    val list = _messages.value ?: return
    if (index in list.indices) {
      list[index].text = text
      list[index].kind = kind
      list[index].isStreaming = streaming
      _messages.postValue(list)
    }
  }

  fun addFileActivity(index: Int, fileName: String) {
    val list = _messages.value ?: return
    if (index in list.indices) {
      list[index].activity.add(FileActivity(fileName))
      _messages.postValue(list)
    }
  }

  fun updateFileActivity(index: Int, fileName: String, success: Boolean) {
    val list = _messages.value ?: return
    if (index in list.indices) {
      list[index].activity.firstOrNull { it.fileName == fileName }?.success = success
      _messages.postValue(list)
    }
  }

  fun startAssistantStreaming(): Int {
    val list = _messages.value ?: mutableListOf()
    list.add(ChatMessage("", false, true))
    _messages.postValue(list)
    return list.lastIndex
  }

  fun updateStreaming(index: Int, text: String) {
    val list = _messages.value ?: return
    if (index in list.indices) {
      list[index].text = text
      _messages.postValue(list)
    }
  }

  fun finalizeStreaming(index: Int) {
    val list = _messages.value ?: return
    if (index in list.indices) {
      list[index].isStreaming = false
      _messages.postValue(list)
    }
  }

  fun setWorking(working: Boolean) {
    _isWorking.postValue(working)
  }

  fun setAgentMode(mode: AgentMode) {
    _agentMode.postValue(mode)
  }

  fun setPendingAttachment(uri: String?) {
    _pendingAttachment.postValue(uri)
  }

  fun clearMessages() {
    _messages.postValue(mutableListOf())
  }
}
