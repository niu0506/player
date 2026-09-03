package com.example.player.ui.playlist

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MediaListAdapter.setCurrentPlaying 边界检查的单元测试：
 * 守护「notifyItemChanged 一律做 items.indices 双向边界检查」的修复。
 *
 * 背景：调用方传入的下标来自 ExoPlayer 队列，而 adapter.items 经 submitList 异步
 * diff 后才回写，存在「队列已同步、items 未跟上」的窗口（Activity 重连时 items
 * 尚为空、扫描新增后 diff 未完成），越界 position 传给 RecyclerView 会在绑定时
 * 抛 IndexOutOfBoundsException。
 *
 * 说明：items 为私有字段且 submitList 需要 Main dispatcher（纯 JVM 测试不可用），
 * 故用反射直接填充。setCurrentPlaying 只读取列表大小、不读取元素内容，
 * 用任意对象占位即可；MediaItemData 依赖 android.net.Uri，在纯 JVM 下无法构造。
 */
class MediaListAdapterCurrentPlayingTest {

    /** 记录 notifyItemChanged 产生回调的列表项位置 */
    private class RecordingObserver : RecyclerView.AdapterDataObserver() {
        val changedPositions = mutableListOf<Int>()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            repeat(itemCount) { changedPositions.add(positionStart + it) }
        }
    }

    private lateinit var adapter: MediaListAdapter
    private lateinit var observer: RecordingObserver

    @Before
    fun setUp() {
        adapter = MediaListAdapter(onClick = {}, onDelete = {})
        observer = RecordingObserver()
        attachObserver(adapter, observer)
    }

    /**
     * 纯 JVM 单测环境限制：AdapterDataObservable 的父类 android.database.Observable
     * 来自 mockable android.jar（空 stub）——构造器不执行（mObservers 永为 null）、
     * registerObserver 是空实现。因此不能用 registerAdapterDataObserver，
     * 需反射注入观察者列表并手动挂上观察者，让 notifyItemChanged 的回调可被记录。
     */
    @Suppress("UNCHECKED_CAST")
    private fun attachObserver(adapter: MediaListAdapter, observer: RecordingObserver) {
        val observableField = RecyclerView.Adapter::class.java.getDeclaredField("mObservable")
            .apply { isAccessible = true }
        val observable = observableField.get(adapter)
            ?: throw IllegalStateException("未取到 Adapter.mObservable")

        // mObservers 声明在父类（android.database.Observable stub），沿类层级向上找
        var cls: Class<*>? = observable.javaClass
        var observersField: java.lang.reflect.Field? = null
        while (cls != null && observersField == null) {
            observersField = cls.declaredFields.firstOrNull { it.name == "mObservers" }
            cls = cls.superclass
        }
        observersField ?: throw IllegalStateException("未找到 mObservers 字段")
        observersField.isAccessible = true
        if (observersField.get(observable) == null) {
            observersField.set(observable, ArrayList<Any>())
        }
        (observersField.get(observable) as ArrayList<Any>).add(observer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(): MutableList<Any> {
        val field = MediaListAdapter::class.java.getDeclaredField("items")
        field.isAccessible = true
        return field.get(adapter) as MutableList<Any>
    }

    private fun currentPlayingIndex(): Int {
        val field = MediaListAdapter::class.java.getDeclaredField("currentPlayingIndex")
        field.isAccessible = true
        return field.getInt(adapter)
    }

    @Test
    fun `index beyond empty items notifies nothing`() {
        // 场景：Activity 重连时 loadPlaylist 未执行、items 为空，
        // 但 Service 存活使 controller.currentMediaItemIndex >= 0
        adapter.setCurrentPlaying(0, true)
        adapter.setCurrentPlaying(5, true)
        assertTrue(observer.changedPositions.isEmpty())
    }

    @Test
    fun `negative index notifies nothing`() {
        adapter.setCurrentPlaying(-1)
        adapter.setCurrentPlaying(-1, true)
        assertTrue(observer.changedPositions.isEmpty())
    }

    @Test
    fun `in bounds index and old index both notify`() {
        repeat(3) { items().add(Any()) }
        adapter.setCurrentPlaying(1)
        assertEquals(listOf(1), observer.changedPositions)
        // 切换到新位置：旧位置 1 与新位置 2 都在界内，均应刷新
        adapter.setCurrentPlaying(2, true)
        assertEquals(listOf(1, 1, 2), observer.changedPositions)
    }

    @Test
    fun `state is recorded even when notify is suppressed`() {
        // 越界时仅记录状态不通知：等 items 追上后，下一次状态变化会把
        // 旧高亮（越界期间的 5）与新位置一并刷新，高亮不丢失
        adapter.setCurrentPlaying(5, true)
        assertEquals(5, currentPlayingIndex())
        assertTrue(observer.changedPositions.isEmpty())

        repeat(6) { items().add(Any()) }
        adapter.setCurrentPlaying(2, true)
        assertEquals(listOf(5, 2), observer.changedPositions)
    }

    @Test
    fun `stale old index is suppressed after list shrinks`() {
        // 场景：列表缩短后 diff 未完成，old 高亮下标已越界
        repeat(2) { items().add(Any()) }
        adapter.setCurrentPlaying(1)
        assertEquals(listOf(1), observer.changedPositions)

        items().clear()
        observer.changedPositions.clear()

        // old=1 与 index=0 均越界（空列表），不应产生任何回调
        adapter.setCurrentPlaying(0)
        assertTrue(observer.changedPositions.isEmpty())
    }
}
