package com.example.player

import android.net.FakeUri
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** formatTime 时长格式化测试 */
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

/** mergeProgressMap「合并非覆盖 + 0 值不覆盖 + removes 优先剔除」语义测试 */
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

/**
 * 进度写入裁决测试：末尾容差内视为「已看完」（回归：单曲循环只重复片尾）。
 *
 * 周期落盘的采样点几乎必然落在末尾几秒内；若只把「位置 ≥ 总时长」当播完，
 * 这个接近末尾的值会被当成断点存下来——REPEAT_MODE_ONE 回绕时位置已归 0
 * （判 Skip，抹不掉旧值），于是每一轮都被 seek 回片尾。
 */
class ProgressDecisionTest {

    private val duration = 180_000L

    @Test
    fun `position well before end is stored`() {
        assertEquals(ProgressWriteDecision.Store(60_000L), decideProgressWrite(60_000, duration))
    }

    @Test
    fun `position inside tail is treated as finished`() {
        // 178s / 180s：距末尾 2s，落在 5s 容差内
        assertEquals(ProgressWriteDecision.Clear, decideProgressWrite(duration - 2_000, duration))
    }

    @Test
    fun `exact end and beyond are finished`() {
        assertEquals(ProgressWriteDecision.Clear, decideProgressWrite(duration, duration))
        assertEquals(ProgressWriteDecision.Clear, decideProgressWrite(duration + 1, duration))
    }

    @Test
    fun `zero position is skipped rather than cleared`() {
        assertEquals(ProgressWriteDecision.Skip, decideProgressWrite(0, duration))
    }

    @Test
    fun `unknown duration keeps legacy semantics`() {
        assertEquals(ProgressWriteDecision.Skip, decideProgressWrite(0, 0))
        assertEquals(ProgressWriteDecision.Store(5_000L), decideProgressWrite(5_000, 0))
        assertEquals(ProgressWriteDecision.Store(5_000L), decideProgressWrite(5_000, -1))
    }

    @Test
    fun `tail is capped at ten percent of short media`() {
        // 10s 媒体：容差取 1s（而非 5s），阈值 9.0s，避免整段被当成「已看完」
        assertFalse(isProgressFinished(8_900, 10_000))
        assertTrue(isProgressFinished(9_000, 10_000))
    }
}

/** 更新包下载源顺序测试：直连在前、反代兜底 */
class DownloadSourceOrderTest {

    private val apk =
        "https://github.com/niu0506/player/releases/download/v1.5.0/player-v1.5.0-release.apk"
    private val accelerators = listOf("https://gh-proxy.com/", "https://ghproxy.net")

    @Test
    fun `direct url comes first`() {
        assertEquals(apk, orderedDownloadUrls(apk, accelerators).first())
    }

    @Test
    fun `accelerators follow as fallback with normalized prefix`() {
        assertEquals(
            listOf(apk, "https://gh-proxy.com/$apk", "https://ghproxy.net/$apk"),
            orderedDownloadUrls(apk, accelerators)
        )
    }

    @Test
    fun `without accelerators only the direct url remains`() {
        assertEquals(listOf(apk), orderedDownloadUrls(apk, emptyList()))
    }
}

/** 旧 SharedPreferences 迁移解析测试：null/空/坏 JSON、进度 >0 过滤、列表去重 */
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

/**
 * MediaListAdapter.setCurrentPlaying 边界检查测试。
 *
 * uri 是稳定身份：items 经异步 diff 后才回写，下标存在错位窗口，高亮按 uri 定位；
 * 不在 items 中的 uri 只记录状态不通知，待 items 追上后高亮不丢失。
 *
 * 纯 JVM 限制：MediaItemData 依赖 android.net.Uri，stub jar 无法构造真实实例，
 * 用同包占位实现 [FakeUri]（仅 toString 参与）；AdapterDataObservable 来自
 * mockable android.jar（空 stub），需反射注入观察者列表让 notifyItemChanged 回调可被记录。
 */
class MediaListAdapterCurrentPlayingTest {

    /** 记录 notifyItemChanged 产生回调的列表项位置 */
    private class RecordingObserver : RecyclerView.AdapterDataObserver() {
        val changedPositions = mutableListOf<Int>()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            repeat(itemCount) { changedPositions.add(positionStart + it) }
        }
    }

    private fun item(s: String) = MediaItemData(FakeUri(s), s)

    private lateinit var adapter: MediaListAdapter
    private lateinit var observer: RecordingObserver

    @Before
    fun setUp() {
        adapter = MediaListAdapter(onClick = {}, onDelete = {})
        observer = RecordingObserver()
        attachObserver(adapter, observer)
    }

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

    private fun currentPlayingUri(): String? {
        val field = MediaListAdapter::class.java.getDeclaredField("currentPlayingUri")
        field.isAccessible = true
        return field.get(adapter) as String?
    }

    @Test
    fun `uri beyond empty items notifies nothing`() {
        adapter.setCurrentPlaying("u0", true)
        adapter.setCurrentPlaying("u5", true)
        assertTrue(observer.changedPositions.isEmpty())
    }

    @Test
    fun `null uri notifies nothing`() {
        adapter.setCurrentPlaying(null)
        adapter.setCurrentPlaying(null, true)
        assertTrue(observer.changedPositions.isEmpty())
    }

    @Test
    fun `known uri and old uri both notify`() {
        repeat(3) { items().add(item("u$it")) }
        adapter.setCurrentPlaying("u1")
        assertEquals(listOf(1), observer.changedPositions)
        // 切换到新位置：旧位置 1 与新位置 2 都在界内，均应刷新
        adapter.setCurrentPlaying("u2", true)
        assertEquals(listOf(1, 1, 2), observer.changedPositions)
    }

    @Test
    fun `state is recorded even when uri is not in items yet`() {
        // items 未含该 uri 时仅记录状态不通知：等 items 追上后，下一次状态变化会把
        // 旧高亮（追上前的 u5）与新位置一并刷新，高亮不丢失
        adapter.setCurrentPlaying("u5", true)
        assertEquals("u5", currentPlayingUri())
        assertTrue(observer.changedPositions.isEmpty())

        repeat(6) { items().add(item("u$it")) }
        adapter.setCurrentPlaying("u2", true)
        assertEquals(listOf(5, 2), observer.changedPositions)
    }

    @Test
    fun `stale old uri is suppressed after list shrinks`() {
        repeat(2) { items().add(item("u$it")) }
        adapter.setCurrentPlaying("u1")
        assertEquals(listOf(1), observer.changedPositions)

        items().clear()
        observer.changedPositions.clear()

        // 空列表时旧 u1 与新 u0 均找不到，不应产生任何回调
        adapter.setCurrentPlaying("u0")
        assertTrue(observer.changedPositions.isEmpty())
    }
}

/** UpdateChecker.isNewer 语义化版本比较测试 */
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
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.1"))
    }

    @Test
    fun `non numeric segments treated as zero and do not crash`() {
        assertTrue(UpdateChecker.isNewer("1.3", "1.2.5"))
        assertFalse(UpdateChecker.isNewer("1.x", "1.9"))
    }
}
