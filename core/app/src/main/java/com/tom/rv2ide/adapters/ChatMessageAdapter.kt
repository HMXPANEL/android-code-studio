package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.fragments.sidebar.AIAgentViewModel

/**
 * Renders the AI chat conversation.
 *
 * User messages are right-aligned bubbles, assistant messages left-aligned cards.
 * An assistant card can additionally show a working indicator and the per-file
 * activity rows (existing [FileModificationAdapter]) while the agent modifies
 * project files. The activity model is deliberately generic so future tool
 * output can reuse the same slot without redesigning the chat.
 */
class ChatMessageAdapter(
    private val onFileClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

  private val items = mutableListOf<AIAgentViewModel.ChatMessage>()

  companion object {
    private const val TYPE_USER = 0
    private const val TYPE_AI = 1
  }

  fun submitList(messages: List<AIAgentViewModel.ChatMessage>) {
    items.clear()
    items.addAll(messages)
    notifyDataSetChanged()
  }

  override fun getItemCount(): Int = items.size

  override fun getItemViewType(position: Int): Int =
      if (items[position].isUser) TYPE_USER else TYPE_AI

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val inflater = LayoutInflater.from(parent.context)
    return if (viewType == TYPE_USER) {
      UserViewHolder(inflater.inflate(R.layout.item_chat_user_message, parent, false))
    } else {
      AiViewHolder(
          inflater.inflate(R.layout.item_chat_ai_message, parent, false),
          onFileClick
      )
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val message = items[position]
    if (holder is UserViewHolder) holder.bind(message) else (holder as AiViewHolder).bind(message)
  }

  class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val messageText: MaterialTextView = view.findViewById(R.id.userMessageText)
    private val attachmentImage: ImageView = view.findViewById(R.id.userAttachmentImage)

    fun bind(message: AIAgentViewModel.ChatMessage) {
      messageText.text = message.text
      messageText.visibility = if (message.text.isBlank()) View.GONE else View.VISIBLE
      if (message.attachmentUri != null) {
        attachmentImage.visibility = View.VISIBLE
        attachmentImage.setImageURI(message.attachmentUri!!.toUri())
      } else {
        attachmentImage.visibility = View.GONE
        attachmentImage.setImageDrawable(null)
      }
    }
  }

  class AiViewHolder(
      view: View,
      onFileClick: (String) -> Unit
  ) : RecyclerView.ViewHolder(view) {
    private val messageText: MaterialTextView = view.findViewById(R.id.aiMessageText)
    private val workingRow: View = view.findViewById(R.id.aiWorkingRow)
    private val workingText: MaterialTextView = view.findViewById(R.id.aiWorkingText)
    private val workingProgress: CircularProgressIndicator =
        view.findViewById(R.id.aiWorkingProgress)
    private val activityList: RecyclerView = view.findViewById(R.id.aiActivityList)
    private val fileAdapter = FileModificationAdapter()

    init {
      activityList.layoutManager = LinearLayoutManager(view.context)
      activityList.adapter = fileAdapter
      activityList.isNestedScrollingEnabled = false
      fileAdapter.setOnItemClickListener(onFileClick)
    }

    fun bind(message: AIAgentViewModel.ChatMessage) {
      val isWorking = message.kind == AIAgentViewModel.MessageKind.WORKING
      workingRow.visibility = if (isWorking) View.VISIBLE else View.GONE
      if (isWorking) {
        workingText.text = message.text.ifBlank { "AI is working…" }
      }

      messageText.visibility =
          if (!isWorking && message.text.isBlank()) View.GONE else View.VISIBLE
      if (!isWorking) {
        messageText.text = message.text
      } else if (message.text.isNotBlank()) {
        // Keep the latest status visible under the spinner as well.
        messageText.visibility = View.GONE
      }

      if (message.activity.isEmpty()) {
        activityList.visibility = View.GONE
      } else {
        activityList.visibility = View.VISIBLE
        fileAdapter.clear()
        message.activity.forEach { activity ->
          fileAdapter.addItem(activity.fileName)
          activity.success?.let { fileAdapter.updateItemStatus(activity.fileName, it) }
        }
      }
    }
  }
}
