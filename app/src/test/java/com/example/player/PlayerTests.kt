package com.example.player

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ==================== formatTime 时长格式化 ====================

/**
 * formatTime 的时长格式化测试。
 */
class FormatTimeTest {

    @Test
    fun zero_is_00_00() {
        assertEquals("00:00", formatTime(0))
    }

    @Test
    fun under_one_minute_formats_m_s() {
        assertEquals("00:05", formatTime(5_000))
    }

    @Test
    fun minutes_and_seconds() {
        assertEquals("12:34", formatTime(12 * 60_000 + 34_000))
    }

    @Test
    fun over_one_hour_includes_hours() {
        assertEquals("1:02:03", formatTime((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun truncates_milliseconds() {
        assertEquals("00:07", formatTime(7_999))
    }
}

// ==================== mergeProgressMap 进度合并 ====================

/**
 * mergeProgressMap 的单元测试：覆盖「合并非覆盖 + 0 值不覆盖 + removes 优先剔除」语义，
 * 防止该进度合并逻辑的历史 bug 回归。
 */
class PlaybackProgressTest {

    @Test
    fun `empty disk keeps only positive writes`() {
        val result = mergeProgressMap(emptyMap(), mapOf("a" to 1000L, "b" to 0L), emptySet())
        assertEquals(mapOf("a" to 1000L), result)
    }

    @Test
    fun `zero value does not overwrite existing positive progress`() {
        val result = mergeProgressMap(mapOf("a" to 1000L), mapOf("a" to 0L), emptySet())
        assertEquals(mapOf("a" to 1000L), result)
    }

    @Test
    fun `positive write overwrites existing value`() {
        val result = mergeProgressMap(
            mapOf("a" to 1000L, "b" to 500L),
            mapOf("a" to 8000L),
            emptySet()
        )
        assertEquals(mapOf("a" to 8000L, "b" to 500L), result)
    }

    @Test
    fun `removed then written same uri ends up written`() {
        // 契约是「先剔除 removes，再合并 writes(>0)」：同名条目写入方胜出
        val result = mergeProgressMap(
            emptyMap(),
            mapOf("a" to 1000L),
            setOf("a")
        )
        assertEquals(mapOf("a" to 1000L), result)
    }

    @Test
    fun `removes take precedence over existing disk value`() {
        val result = mergeProgressMap(
            mapOf("a" to 1000L, "b" to 500L),
            mapOf("b" to 5000L),
            setOf("a")
        )
        assertEquals(mapOf("b" to 5000L), result)
    }

    @Test
    fun `empty writes and removes leave disk unchanged`() {
        val disk = mapOf("a" to 1000L)
        assertEquals(disk, mergeProgressMap(disk, emptyMap(), emptySet()))
    }
}

// ==================== 旧 SharedPreferences 数据迁移解析 ====================

/**
 * 旧 SharedPreferences 数据迁移解析器的单元测试：
 * 覆盖 null/空/坏 JSON、进度仅保留 >0 值、列表按 uri 去重等迁移契约，
 * 防止「覆盖安装后旧数据丢失/脏数据入库」回归。
 */
class LegacyPrefsMigrationTest {

    // ---- parseLegacyProgressJson ----

    @Test
    fun `null progress json returns empty map`() {
        assertEquals(emptyMap<String, Long>(), parseLegacyProgressJson(null))
    }

    @Test
    fun `empty progress json returns empty map`() {
        assertEquals(emptyMap<String, Long>(), parseLegacyProgressJson(""))
    }

    @Test
    fun `garbage progress json returns empty map`() {
        assertEquals(emptyMap<String, Long>(), parseLegacyProgressJson("{not valid json"))
    }

    @Test
    fun `zero and negative progress values are dropped`() {
        val json = """{"a":1000,"b":0,"c":-5}"""
        assertEquals(mapOf("a" to 1000L), parseLegacyProgressJson(json))
    }

    @Test
    fun `positive progress values are kept`() {
        val json = """{"a":1000,"b":250000}"""
        assertEquals(mapOf("a" to 1000L, "b" to 250000L), parseLegacyProgressJson(json))
    }

    // ---- parseLegacyPlaylistJson ----

    @Test
    fun `null playlist json returns empty list`() {
        assertEquals(emptyList<LegacyPlaylistItem>(), parseLegacyPlaylistJson(null))
    }

    @Test
    fun `garbage playlist json returns empty list`() {
        assertEquals(emptyList<LegacyPlaylistItem>(), parseLegacyPlaylistJson("[broken"))
    }

    @Test
    fun `playlist items parsed in order with duration default`() {
        val json = """[
            {"uri":"content://video/1","name":"a.mp4","duration":60000},
            {"uri":"content://audio/2","name":"b.mp3"}
        ]"""
        val items = parseLegacyPlaylistJson(json)
        assertEquals(2, items.size)
        assertEquals("content://video/1", items[0].uri)
        assertEquals("a.mp4", items[0].name)
        assertEquals(60000L, items[0].duration)
        assertEquals(0L, items[1].duration)
    }

    @Test
    fun `duplicate uri entries are deduped keeping first occurrence`() {
        val json = """[
            {"uri":"content://video/1","name":"first.mp4","duration":1000},
            {"uri":"content://video/1","name":"dup.mp4","duration":2000}
        ]"""
        val items = parseLegacyPlaylistJson(json)
        assertEquals(1, items.size)
        assertEquals("first.mp4", items[0].name)
        assertEquals(1000L, items[0].duration)
    }
}

// ==================== MediaListAdapter.setCurrentPlaying 边界检查 ====================

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

// ==================== UpdateChecker.isNewer 版本比较 ====================

/**
 * UpdateChecker.isNewer 的语义化版本比较测试。
 */
class UpdateCheckerTest {

    @Test
    fun `newer patch is newer`() {
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2.0"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"))
    }

    @Test
    fun `older patch is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.1.9", "1.2.0"))
    }

    @Test
    fun `major takes precedence`() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `minor takes precedence over patch`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
    }

    @Test
    fun `shorter version treated as zero padded`() {
        // "1.2" 与 "1.2.0" 补零后相等，不视为更新
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.1"))
    }

    @Test
    fun `non numeric segments treated as zero and do not crash`() {
        assertTrue(UpdateChecker.isNewer("1.3", "1.2.5"))
        assertFalse(UpdateChecker.isNewer("1.x", "1.9"))
    }
}
