package com.example.p2pchat.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.p2pchat.ChatMessage
import com.example.p2pchat.R
import com.example.p2pchat.databinding.ItemMessageBinding
import com.example.p2pchat.maps.GeoCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MessageVariant { PUBLIC, PRIVATE }

class MessagesAdapter(
    private val variant: MessageVariant,
    private val showTarget: Boolean = false,
    private val onMessageClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submit(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(messages[position], timeFormat, showTarget, variant, onMessageClick)
    }

    override fun getItemCount() = messages.size

    class VH(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            message: ChatMessage,
            timeFormat: SimpleDateFormat,
            showTarget: Boolean,
            variant: MessageVariant,
            onMessageClick: ((ChatMessage) -> Unit)?
        ) {
            val ctx = binding.root.context
            val time = timeFormat.format(Date(message.timestamp))
            val fromWho = if (showTarget && message.targetId != null) {
                "${message.senderId} -> ${message.targetId}"
            } else {
                message.senderId
            }
            binding.senderText.text = "$fromWho  •  $time"
            binding.avatarInitial.text = message.senderId.take(1).uppercase()

            val geo = GeoCodec.decode(message.content)
            if (geo != null) {
                binding.contentText.text = "📍 Location shared — tap to view on map"
                binding.root.setOnClickListener { onMessageClick?.invoke(message) }
                binding.avatarInitial.backgroundTintList = null
                binding.avatarInitial.setBackgroundResource(R.drawable.circle_avatar_location)
                binding.senderText.setTextColor(ContextCompat.getColor(ctx, R.color.location_accent))
                binding.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.location_accent_bg))
                return
            }

            binding.contentText.text = message.content
            binding.root.setOnClickListener(null)
            binding.root.isClickable = false

            if (variant == MessageVariant.PUBLIC) {
                binding.avatarInitial.setBackgroundResource(R.drawable.circle_avatar_public)
                binding.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.public_accent_bg))
                binding.avatarInitial.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.public_accent))
                binding.senderText.setTextColor(ContextCompat.getColor(ctx, R.color.public_accent))
            } else {
                binding.avatarInitial.setBackgroundResource(R.drawable.circle_avatar_private)
                binding.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.private_accent_bg))
                binding.avatarInitial.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.private_accent))
                binding.senderText.setTextColor(ContextCompat.getColor(ctx, R.color.private_accent))
            }
        }
    }
}
