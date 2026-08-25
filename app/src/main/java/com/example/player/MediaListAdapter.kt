package com.example.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.player.databinding.ItemMediaBinding

/**
 * 播放列表的 RecyclerView 适配器。
 *
 * 负责把 [MediaItemData] 列表渲染为 UI，并处理三项用户交互：
 * - 点击列表项播放对应媒体（通过 [onClick] 回调）
 * - 点击删除按钮移除列表项（通过 [onDelete] 回调）
 * - 实时刷新当前播放项 / 进度条 / 时长等信息
 */
class MediaListAdapter(
    /** 点击某个列表项时的回调，参数为被点击项的下标 */
    private val onClick: (Int) -> Unit,
    /** 点击删除按钮时的回调，参数为被删除项的下标 */
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<MediaListAdapter.VH>() {

    /** 当前展示的数据副本（与外部 playlist 保持一致但不直接引用） */
    private val items = mutableListOf<MediaItemData>()
    /** 当前正在播放的列表项下标，-1 表示无 */
    private var currentPlayingIndex = -1
    /** 当前是否处于播放状态（决定图标展示） */
    private var isPlaying = false

    /**
     * 用新列表刷新数据，并借助 DiffUtil 计算增量后最小化刷新 UI。
     * @param list 新的数据列表
     */
    fun submitList(list: List<MediaItemData>) {
        // DiffUtil 通过 uri 判断「是否同一项」，通过 data class 判等判断「内容是否变化」
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition].uri == list[newItemPosition].uri
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition] == list[newItemPosition]
        })
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    /**
     * 设置当前正在播放的列表项。
     * 只通知「旧项」与「新项」两个位置刷新，避免表刷新。
     */
    fun setCurrentPlaying(index: Int, playing: Boolean = false) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        isPlaying = playing
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    /** 更新某个列表项的时长（仅当原先未知时为 0 时写入），并刷新该项 */
    fun updateDuration(index: Int, duration: Long) {
        if (index in items.indices && items[index].duration == 0L) {
            items[index] = items[index].copy(duration = duration)
            notifyItemChanged(index)
        }
    }

    /** 更新某个列表项的播放进度（毫秒），并刷新该项的进度条 */
    fun updateProgress(index: Int, position: Long) {
        if (index in items.indices) {
            items[index] = items[index].copy(lastPosition = position)
            notifyItemChanged(index)
        }
    }

    /** 创建单个列表项的视图持有者 */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    /** 绑定当前列表项的数据到视图 */
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        // 列表序号（从 1 开始）
        holder.binding.tvIndex.text =
            context.getString(R.string.list_item_index, position + 1)
        holder.binding.tvName.text = item.name
        holder.binding.tvDuration.text = if (item.duration > 0) formatTime(item.duration) else ""
        // 当前正在播放的项高亮展示，并以「播放动画图标」替代序号
        val isActive = position == currentPlayingIndex && isPlaying
        holder.binding.imgPlaying.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.binding.tvIndex.visibility = if (isActive) View.GONE else View.VISIBLE
        holder.binding.tvName.setTextColor(
            context.getColor(if (isActive) R.color.accent else R.color.text_primary)
        )

        // 已有进度信息时展示进度条；正在播放的项显示「已播时长」，否则显示「已播/总时长」区间
        if (item.lastPosition > 0 && item.duration > 0) {
            val percent = (item.lastPosition * 100 / item.duration).toInt().coerceIn(0, 100)
            holder.binding.progressRow.visibility = View.VISIBLE
            holder.binding.pbItem.progress = percent
            holder.binding.tvProgress.visibility = View.VISIBLE
            if (isActive) {
                holder.binding.tvProgress.text = context.getString(
                    R.string.playing_progress, formatTime(item.lastPosition)
                )
            } else {
                holder.binding.tvProgress.text = context.getString(
                    R.string.progress_range,
                    formatTime(item.lastPosition), formatTime(item.duration)
                )
            }
        } else {
            holder.binding.progressRow.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size

    /** 列表项视图持有者（inner 以便在 init 中绑定一次点击监听，避免每次绑定重建 lambda） */
    inner class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemRoot.setOnClickListener { onClick(bindingAdapterPosition) }
            binding.btnDelete.setOnClickListener { onDelete(bindingAdapterPosition) }
        }
    }
}