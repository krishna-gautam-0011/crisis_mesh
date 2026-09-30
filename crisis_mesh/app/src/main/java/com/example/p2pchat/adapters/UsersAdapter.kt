package com.example.p2pchat.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.p2pchat.R
import com.example.p2pchat.databinding.ItemUserBinding

/**
 * A person shown in the "Nearby" list: either currently in range ([online] = true)
 * or a recent private-chat partner still within the 24h message TTL but out of
 * range right now ([online] = false, greyed out, still tappable to read history).
 */
data class PeerItem(
    val userId: String,
    val online: Boolean
)

class UsersAdapter(
    private val onPeerClick: (String) -> Unit
) : RecyclerView.Adapter<UsersAdapter.VH>() {

    private val users = mutableListOf<PeerItem>()
    private var selectedUserId: String? = null

    fun submit(newUsers: List<PeerItem>) {
        users.clear()
        users.addAll(newUsers)
        notifyDataSetChanged()
    }

    fun setSelected(userId: String?) {
        selectedUserId = userId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = users[position]
        holder.bind(item, item.userId == selectedUserId, onPeerClick)
    }

    override fun getItemCount() = users.size

    class VH(private val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PeerItem, isSelected: Boolean, onPeerClick: (String) -> Unit) {
            val ctx = binding.root.context
            binding.userIdText.text = item.userId
            binding.avatarInitial.text = item.userId.take(1).uppercase()

            binding.onlineDot.visibility = if (item.online) android.view.View.VISIBLE else android.view.View.GONE
            binding.avatarInitial.alpha = if (item.online) 1f else 0.45f
            binding.userIdText.alpha = if (item.online) 1f else 0.55f
            binding.statusLabel.text = if (item.online) "In range" else "Recent • out of range"

            binding.root.setBackgroundColor(
                ContextCompat.getColor(ctx, if (isSelected) R.color.private_accent_bg else android.R.color.transparent)
            )

            binding.root.isClickable = true
            binding.root.isFocusable = true
            binding.root.setOnClickListener { onPeerClick(item.userId) }
        }
    }
}
