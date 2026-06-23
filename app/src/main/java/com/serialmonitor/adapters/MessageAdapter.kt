package com.serialmonitor.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.serialmonitor.Message
import com.serialmonitor.databinding.ItemMessageBinding

class MessageAdapter(private val messages: MutableList<Message>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context
        
        val displayContent = when (message.type) {
            Message.Type.RX -> message.content
            Message.Type.TX -> "> ${message.content}"
            Message.Type.SYS -> "[SYS] ${message.content}"
            Message.Type.ERR -> "[ERR] ${message.content}"
        }

        val colorRes = when (message.type) {
            Message.Type.RX -> com.serialmonitor.R.color.terminal_rx
            Message.Type.TX -> com.serialmonitor.R.color.terminal_tx
            Message.Type.SYS -> com.serialmonitor.R.color.terminal_sys
            Message.Type.ERR -> com.serialmonitor.R.color.terminal_err
        }

        holder.binding.messageText.text = displayContent
        holder.binding.messageText.setTextColor(androidx.core.content.ContextCompat.getColor(context, colorRes))
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        if (message.type == Message.Type.RX && messages.isNotEmpty()) {
            val lastMsg = messages.last()
            if (lastMsg.type == Message.Type.RX && !lastMsg.content.endsWith("\n")) {
                lastMsg.content += message.content
                notifyItemChanged(messages.size - 1)
                return
            }
        }
        
        messages.add(message)
        if (messages.size > 1000) {
            messages.removeAt(0)
            notifyItemRemoved(0)
        }
        notifyItemInserted(messages.size - 1)
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }
}
