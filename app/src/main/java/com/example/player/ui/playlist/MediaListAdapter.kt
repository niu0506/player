package com.example.player.ui.playlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.player.R
import com.example.player.databinding.ItemMediaBinding
import com.example.player.model.MediaItemData
import com.example.player.model.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /**
     * 各条目的播放进度（uri -> 位置毫秒），独立于条目数据单独维护。
     * 进度不再放进 [MediaItemData]，这里是列表 UI 展示进度的唯一来源，随 [updateProgress]
     * 局部更新，跨列表刷新(submitList)也保持存活。
     */
    private val progressMap = mutableMapOf<String, Long>()
    /** 当前正在播放的列表项下标，-1 表示无 */
    private var currentPlayingIndex = -1
    /** 当前是否处于播放状态（决定图标展示） */
    private var isPlaying = false
    /** 后台线程作用域：用于把 DiffUtil 计算移出主线程，避免大列表掉帧 */
    private val diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** 正在进行的 diff 任务句柄，新的 submitList 会取消旧的，避免乱序 dispatch 覆盖 */
    private var diffJob: Job? = null

    /**
     * 用新列表刷新数据，并借助 DiffUtil 计算增量后最小化刷新 UI。
     * DiffUtil 计算在 [Dispatchers.Default] 后台线程执行，结果回主线程 dispatch，
     * 避免列表较大时在主线程同步计算导致掉帧。
     * @param list 新的数据列表
     */
    fun submitList(list: List<MediaItemData>) {
        // 仅在主线程调用，此处无需加锁：items 只在下方 withContext(Main) 内修改
        val oldItems = ArrayList(items)
        diffJob?.cancel()
        diffJob = diffScope.launch {
            val myJob = coroutineContext[Job]
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition].uri == list[newItemPosition].uri
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition] == list[newItemPosition]
            })
            withContext(Dispatchers.Main) {
                if (myJob?.isActive != true) return@withContext // 已被更新的提交取消，丢弃本次结果
                items.clear()
                items.addAll(list)
                diff.dispatchUpdatesTo(this@MediaListAdapter)
            }
        }
    }

    /**
     * 设置当前正在播放的列表项。
     * 只通知「旧项」与「新项」两个位置刷新，避免全表刷新。
     *
     * 调用方传入的下标来自 ExoPlayer 队列，而 [items] 经 submitList 异步 diff 后才回写，
     * 存在「队列已同步、items 未跟上」的窗口（如 Activity 重连时 items 尚为空、
     * 扫描新增后 diff 未完成），因此与 updateDuration/updateProgress 一律做双向边界检查，
     * 越界时仅记录状态，待下次 diff dispatch 或状态调用时自然刷新。
     */
    fun setCurrentPlaying(index: Int, playing: Boolean = false) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        isPlaying = playing
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    /** 更新某个列表项的时长（仅当原先未知时为 0 时写入），并刷新该项 */
    fun updateDuration(index: Int, duration: Long) {
        if (index in items.indices && items[index].duration == 0L) {
            items[index] = items[index].copy(duration = duration)
            notifyItemChanged(index)
        }
    }

    /**
     * 批量同步各 uri 的播放进度（用于冷启动/回前台把内存或磁盘进度刷进列表，供进度条展示）。
     * 只收录 >0 的有效进度，0 视为无进度。
     */
    fun setProgress(progress: Map<String, Long>) {
        progressMap.clear()
        for ((uri, pos) in progress) {
            if (pos > 0) progressMap[uri] = pos
        }
    }

    /** 更新某个列表项的播放进度（毫秒），并刷新该项的进度条 */
    fun updateProgress(index: Int, position: Long) {
        if (index in items.indices) {
            progressMap[items[index].uri.toString()] = position
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
        val progress = progressMap[item.uri.toString()] ?: 0L
        if (progress > 0 && item.duration > 0) {
            val percent = (progress * 100 / item.duration).toInt().coerceIn(0, 100)
            holder.binding.progressRow.visibility = View.VISIBLE
            holder.binding.pbItem.progress = percent
            holder.binding.tvProgress.visibility = View.VISIBLE
            if (isActive) {
                holder.binding.tvProgress.text = context.getString(
                    R.string.playing_progress, formatTime(progress)
                )
            } else {
                holder.binding.tvProgress.text = context.getString(
                    R.string.progress_range,
                    formatTime(progress), formatTime(item.duration)
                )
            }
        } else {
            holder.binding.progressRow.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        diffScope.cancel()
    }

    /** 列表项视图持有者（inner 以便在 init 中绑定一次点击监听，避免每次绑定重建 lambda） */
    inner class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemRoot.setOnClickListener { onClick(bindingAdapterPosition) }
            binding.btnDelete.setOnClickListener { onDelete(bindingAdapterPosition) }
        }
    }
}
