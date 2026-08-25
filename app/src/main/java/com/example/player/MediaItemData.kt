package com.example.player

import android.net.Uri
import java.util.Locale

/**
 * 播放列表中的单个媒体项描述。
 *
 * 一个完整的播放项既包含媒体本身的元信息（来源、名称、时长），
 * 也包含与「进度恢复」相关的播放位置信息。
 */
data class MediaItemData(
    /** 媒体的来源地址（通常是 ContentResolver 查询到的 Uri） */
    val uri: Uri,
    /** 媒体文件显示名称，用于在列表中展示 */
    val name: String,
    /** 媒体总时长（毫秒），扫描时可能未知，播放后回填 */
    val duration: Long = 0L,
    /** 上次播放到的位置（毫秒），用于下次打开时从断点继续播放 */
    val lastPosition: Long = 0L
)

/**
 * 把毫秒格式化为播放时长文本。
 * 不足 1 小时显示「分:秒」（如 12:34），满 1 小时显示「时:分:秒」（如 1:02:03）。
 * 供播放列表时长、手势进度预览等共用，替代原先分散的格式化方法。
 */
fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}