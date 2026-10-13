package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.session.ConversationRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rows for saved AI conversations. Reuses the `item_history` card so both
 * history sections look consistent; a blue icon distinguishes conversations
 * from file-modification records (green/red in [HistoryAdapter]).
 */
class ConversationAdapter(
    private val onClick: (sessionId: String) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

    private val items = mutableListOf<ConversationRow>()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    fun setItems(newItems: List<ConversationRow>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ConversationViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ConversationViewHolder(
        itemView: View,
        private val onClick: (sessionId: String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val historyIcon: ImageView = itemView.findViewById(R.id.historyIcon)
        private val historyTitle: TextView = itemView.findViewById(R.id.historyTitle)
        private val historyTime: TextView = itemView.findViewById(R.id.historyTime)
        private val historyDetails: TextView = itemView.findViewById(R.id.historyDetails)
        private var sessionId: String? = null

        init {
            itemView.setOnClickListener { sessionId?.let(onClick) }
            historyIcon.setColorFilter(itemView.context.getColor(android.R.color.holo_blue_dark))
        }

        fun bind(item: ConversationRow) {
            sessionId = item.sessionId
            historyTitle.text = item.title
            historyTime.text = dateFormat.format(Date(item.updatedAt))
            historyDetails.text = itemView.context.getString(
                R.string.ai_history_conversation_details,
                item.messageCount,
                item.toolCallCount
            )
        }
    }
}
