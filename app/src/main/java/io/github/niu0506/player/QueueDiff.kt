/**
 * 播放队列的差分更新计划：只描述「增 / 删 / 移 / 换」四类操作，
 * 不直接依赖 Media3，便于在 JVM 单测中验证下标变化。
 */
package io.github.niu0506.player

/** 队列中一个条目的最小快照：差分只需要 URI 与标题。 */
internal data class QueueItemSnapshot(val uri: String, val title: String?)

/** 队列差异操作；索引均为执行该操作时的实时下标。 */
internal sealed interface QueueEdit {
    /** 在 [index] 插入 [uri]；对应播放列表的同一目标下标。 */
    data class Add(val index: Int, val uri: String) : QueueEdit

    /** 删除 [index] 处的 [uri]。 */
    data class Remove(val index: Int, val uri: String) : QueueEdit

    /** 把 [fromIndex] 处的 [uri] 移到 [toIndex]。 */
    data class Move(val fromIndex: Int, val toIndex: Int, val uri: String) : QueueEdit

    /** 用播放列表同一目标下标的条目替换 [index]（通常只改标题）。 */
    data class Replace(val index: Int, val uri: String) : QueueEdit
}

/**
 * [requiresFullReset] 为 true 表示当前播放项已不在目标列表中，
 * 或者差分无法安全落地；调用方应回退到 setMediaItems。
 */
internal data class QueueDiffPlan(
    val edits: List<QueueEdit>,
    val requiresFullReset: Boolean,
)

/**
 * 计算把 [current] 变成 [target] 的最小操作序列。
 *
 * 算法：
 * 1. 删除目标中不存在的 URI；
 * 2. 从左到右，把目标项从后方移到当前位，找不到则插入；
 * 3. 截掉尾部多余项；
 * 4. 对仍然存在的条目按需替换标题。
 *
 * 所有操作都在「执行到该步时」的队列上计算，因此可以按顺序直接作用到控制器。
 */
internal fun planQueueDiff(
    current: List<QueueItemSnapshot>,
    target: List<QueueItemSnapshot>,
    currentUri: String?,
): QueueDiffPlan {
    // 当前项被移除时增量操作无法保住播放位置，交给调用方整体重建。
    if (currentUri != null && target.none { it.uri == currentUri }) {
        return QueueDiffPlan(emptyList(), requiresFullReset = true)
    }

    val working = current.toMutableList()
    val edits = mutableListOf<QueueEdit>()
    val targetUris = target.map { it.uri }
    val targetUriSet = targetUris.toSet()

    // 先删掉目标里完全不存在的项，从后往前避免下标漂移。
    for (index in working.indices.reversed()) {
        if (working[index].uri !in targetUriSet) {
            val removed = working.removeAt(index)
            edits += QueueEdit.Remove(index, removed.uri)
        }
    }

    // 把前缀逐位对齐：能移动就移动，不能移动就插入。
    for (index in target.indices) {
        if (index < working.size && working[index].uri == target[index].uri) continue

        val found = (index + 1 until working.size)
            .firstOrNull { working[it].uri == target[index].uri }
        if (found != null) {
            val moved = working.removeAt(found)
            working.add(index, moved)
            edits += QueueEdit.Move(found, index, moved.uri)
        } else {
            working.add(index, target[index])
            edits += QueueEdit.Add(index, target[index].uri)
        }
    }

    // 目标更短时删除尾部多余项。
    while (working.size > target.size) {
        val index = working.lastIndex
        val removed = working.removeAt(index)
        edits += QueueEdit.Remove(index, removed.uri)
    }

    if (working.map { it.uri } != targetUris) {
        // 理论上不会发生；出现重复 URI 等异常时宁可整体重建。
        return QueueDiffPlan(emptyList(), requiresFullReset = true)
    }

    // 结构一致后只替换标题变化的条目，避免重建当前媒体源。
    for (index in target.indices) {
        if (working[index].title != target[index].title) {
            working[index] = target[index]
            edits += QueueEdit.Replace(index, target[index].uri)
        }
    }

    return QueueDiffPlan(edits, requiresFullReset = false)
}
