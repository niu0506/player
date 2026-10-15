package com.example.player

import android.net.FakeUri
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 测试 [formatTime] 的时间格式化规则。 */
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

/** 测试 [mergeProgressMap] 的进度合并语义（正数覆盖、0 不覆盖、删除优先等）。 */
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

/** 测试进度写入决策 [decideProgressWrite] 与“已播完”判定 [isProgressFinished]。 */
class ProgressDecisionTest {

    /** 测试用总时长：180 秒。 */
    private val duration = 180_000L

    @Test
    fun `position well before end is stored`() {
        assertEquals(ProgressWriteDecision.Store(60_000L), decideProgressWrite(60_000, duration))
    }

    @Test
    fun `position inside tail is treated as finished`() {

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

        assertFalse(isProgressFinished(8_900, 10_000))
        assertTrue(isProgressFinished(9_000, 10_000))
    }
}

/** 测试 [orderedDownloadUrls] 的下载地址排序（直连优先、镜像兜底）。 */
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

/** 测试旧版 SharedPreferences 数据（播放列表/进度 JSON）的迁移解析。 */
class LegacyPrefsMigrationTest {

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
 * 测试 [MediaListAdapter.setCurrentPlaying] 的刷新行为：
 * 仅对命中/变更的行发出通知，且能正确记录状态。
 * 由于用例在本地 JVM 运行，通过反射直接访问 Adapter 的私有字段。
 */
class MediaListAdapterCurrentPlayingTest {

    /** 记录被通知刷新的位置，便于断言。 */
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

    /** 通过反射把观察者挂到 Adapter 内部的 mObservable.mObservers 上。 */
    @Suppress("UNCHECKED_CAST")
    private fun attachObserver(adapter: MediaListAdapter, observer: RecordingObserver) {
        val observableField = RecyclerView.Adapter::class.java.getDeclaredField("mObservable")
            .apply { isAccessible = true }
        val observable = observableField.get(adapter)
            ?: throw IllegalStateException("未取到 Adapter.mObservable")

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

    /** 通过反射读取 Adapter 的私有 items 列表。 */
    @Suppress("UNCHECKED_CAST")
    private fun items(): MutableList<Any> {
        val field = MediaListAdapter::class.java.getDeclaredField("items")
        field.isAccessible = true
        return field.get(adapter) as MutableList<Any>
    }

    /** 通过反射读取 Adapter 的私有 currentPlayingUri。 */
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

        adapter.setCurrentPlaying("u2", true)
        assertEquals(listOf(1, 1, 2), observer.changedPositions)
    }

    @Test
    fun `state is recorded even when uri is not in items yet`() {

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

        adapter.setCurrentPlaying("u0")
        assertTrue(observer.changedPositions.isEmpty())
    }
}

/** 测试 [UpdateChecker.isNewer] 的版本号比较逻辑。 */
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

/** 测试文件夹分组键的派生规则 [folderKeyFromRelativePath] / [folderKeyFromAbsolutePath] / [folderDisplayName]。 */
class FolderKeyTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `relative path trims slashes and blanks`() {
        assertEquals("Movies/剧A", folderKeyFromRelativePath("Movies/剧A/"))
        assertEquals("Movies/剧A", folderKeyFromRelativePath(" Movies/剧A/ "))
        assertEquals("Movies/剧A/Season 1", folderKeyFromRelativePath("Movies/剧A/Season 1/"))
    }

    @Test
    fun `blank relative path means ungrouped`() {
        assertEquals("", folderKeyFromRelativePath(null))
        assertEquals("", folderKeyFromRelativePath(""))
        assertEquals("", folderKeyFromRelativePath("   "))
        assertEquals("", folderKeyFromRelativePath("/"))
    }

    @Test
    fun `absolute path parent is relative to storage root`() {
        assertEquals("Movies/剧A", folderKeyFromAbsolutePath("$root/Movies/剧A/第01集.mp4", root))
        assertEquals("Movies", folderKeyFromAbsolutePath("$root/Movies/第01集.mp4", root))
    }

    @Test
    fun `file directly in storage root is ungrouped`() {
        assertEquals("", folderKeyFromAbsolutePath("$root/散装.mp4", root))
    }

    @Test
    fun `another volume keeps the absolute parent`() {
        assertEquals(
            "/storage/1234-5678/Movies/剧A",
            folderKeyFromAbsolutePath("/storage/1234-5678/Movies/剧A/第01集.mp4", root)
        )
    }

    @Test
    fun `unknown storage root keeps the absolute parent`() {
        assertEquals(
            "$root/Movies/剧A",
            folderKeyFromAbsolutePath("$root/Movies/剧A/第01集.mp4", null)
        )
    }

    @Test
    fun `null or unusable absolute path is ungrouped`() {
        assertEquals("", folderKeyFromAbsolutePath(null, root))
        assertEquals("", folderKeyFromAbsolutePath("", root))
        assertEquals("", folderKeyFromAbsolutePath("第01集.mp4", root))
    }

    @Test
    fun `display name is the last segment`() {
        assertEquals("剧A", folderDisplayName("Movies/剧A"))
        assertEquals("剧A", folderDisplayName("剧A"))
        assertEquals("", folderDisplayName(""))
    }
}

/** 测试 [naturalCompare] 的数字感知排序与全序性质。 */
class NaturalOrderTest {

    private val comparator = Comparator<String> { a, b -> naturalCompare(a, b) }

    private fun sign(x: Int): Int = if (x > 0) 1 else if (x < 0) -1 else 0

    @Test
    fun `episode numbers compare by value not by text`() {
        assertTrue(naturalCompare("第2集", "第10集") < 0)
        assertTrue(naturalCompare("第10集", "第100集") < 0)
        assertTrue(naturalCompare("E02", "E10") < 0)
    }

    @Test
    fun `episodes are ordered naturally`() {
        val names = listOf("第10集", "第2集", "第11集", "第1集", "第9集")
        assertEquals(
            listOf("第1集", "第2集", "第9集", "第10集", "第11集"),
            names.sortedWith(comparator)
        )
    }

    @Test
    fun `leading zeros are resolved deterministically`() {
        // 数值相同（"1" 与 "01"）时靠长度兜底：位数少者在前
        assertTrue(naturalCompare("1", "01") < 0)
        assertEquals(
            sign(naturalCompare("01", "1")),
            -sign(naturalCompare("1", "01"))
        )
    }

    @Test
    fun `shorter name with the same prefix comes first`() {
        assertTrue(naturalCompare("第2集", "第2集B") < 0)
        assertTrue(naturalCompare("第2集", "第2集 完") < 0)
        assertEquals(
            sign(naturalCompare("第2集B", "第2集 完")),
            -sign(naturalCompare("第2集 完", "第2集B"))
        )
    }

    @Test
    fun `case differences are broken deterministically`() {
        assertTrue(naturalCompare("e02", "E02") != 0)
        assertEquals(sign(naturalCompare("e02", "E02")), -sign(naturalCompare("E02", "e02")))
        assertTrue(naturalCompare("ep01", "EP01") != 0)
    }

    @Test
    fun `mixed languages stay deterministic`() {
        assertEquals(
            sign(naturalCompare("第1集", "EP01")),
            -sign(naturalCompare("EP01", "第1集"))
        )
    }

    @Test
    fun `names without digits do not crash and stay deterministic`() {
        assertTrue(naturalCompare("番外.mp4", "第1集.mp4") != 0)
        assertEquals(
            sign(naturalCompare("番外.mp4", "第1集.mp4")),
            -sign(naturalCompare("第1集.mp4", "番外.mp4"))
        )
    }

    @Test
    fun `very long digit runs compare without overflow`() {
        val huge = "第999999999999999999999集"
        assertTrue(naturalCompare("第2集", huge) < 0)
        assertTrue(naturalCompare(huge, "第2集") > 0)
    }

    @Test
    fun `natural compare is a total order`() {
        val names = listOf(
            "第1集", "第2集", "第10集", "第11集", "第2集 完",
            "E02", "E10", "ep01", "番外.mp4", "01", "1"
        )
        for (a in names) {
            assertEquals(0, naturalCompare(a, a))
            for (b in names) {
                assertEquals(sign(naturalCompare(a, b)), -sign(naturalCompare(b, a)))
            }
        }
        // 结果与输入排列无关，并且幂等 —— 否则每次扫描都会误判“顺序变了”
        val sorted = names.sortedWith(comparator)
        assertEquals(sorted, names.reversed().sortedWith(comparator))
        assertEquals(sorted, sorted.sortedWith(comparator))
    }
}

/** 测试播放列表的「文件夹分组 + 组内自然序」重排 [sortPlaylist] / [comparePlaylistItems]。 */
class PlaylistSortTest {

    private fun item(name: String, folder: String = "") =
        MediaItemData(FakeUri("content://video/$folder/$name"), name, 0L, folder)

    @Test
    fun `episodes of one folder stay together and naturally ordered`() {
        val items = listOf(
            item("第10集.mp4", "Movies/剧B"),
            item("第2集.mp4", "Movies/剧A"),
            item("第1集.mp4", "Movies/剧B"),
            item("第10集.mp4", "Movies/剧A"),
            item("第1集.mp4", "Movies/剧A")
        )
        assertEquals(
            listOf(
                "Movies/剧A" to "第1集.mp4",
                "Movies/剧A" to "第2集.mp4",
                "Movies/剧A" to "第10集.mp4",
                "Movies/剧B" to "第1集.mp4",
                "Movies/剧B" to "第10集.mp4"
            ),
            sortPlaylist(items).map { it.folderKey to it.name }
        )
    }

    @Test
    fun `ungrouped items are placed last`() {
        val items = listOf(
            item("散2.mp4"),
            item("第1集.mp4", "Movies/剧A"),
            item("散1.mp4"),
            item("第2集.mp4", "Movies/剧A")
        )
        val sorted = sortPlaylist(items)
        assertEquals("Movies/剧A", sorted[0].folderKey)
        assertEquals("Movies/剧A", sorted[1].folderKey)
        assertTrue(sorted.drop(2).all { it.folderKey.isEmpty() })
    }

    @Test
    fun `sorting is independent of input order and idempotent`() {
        val items = listOf(
            item("第3集.mp4", "Movies/剧A"),
            item("第1集.mp4", "Movies/剧A"),
            item("第10集.mp4", "Movies/剧B"),
            item("第2集.mp4", "Movies/剧B")
        )
        val sorted = sortPlaylist(items)
        assertEquals(sorted, sortPlaylist(items.reversed()))
        assertEquals(sorted, sortPlaylist(sorted))
    }

    @Test
    fun `same name in one folder falls back to uri`() {
        val a = MediaItemData(FakeUri("content://video/1"), "同名.mp4", 0L, "Movies/剧A")
        val b = MediaItemData(FakeUri("content://video/2"), "同名.mp4", 0L, "Movies/剧A")
        assertTrue(comparePlaylistItems(a, b) < 0)
        assertEquals(0, comparePlaylistItems(a, a))
    }
}

/** 测试用扫描结果回填文件夹键 [backfillFolderKeys]。 */
class FolderBackfillTest {

    private fun item(uri: String, name: String, folder: String = "") =
        MediaItemData(FakeUri(uri), name, 0L, folder)

    private fun key(uri: String): String = normalizeUri(FakeUri(uri))

    @Test
    fun `missing folder keys are filled from the scan result`() {
        val items = listOf(item("content://video/1", "第1集.mp4"), item("content://video/2", "第2集.mp4"))
        val map = mapOf(key("content://video/1") to "Movies/剧A", key("content://video/2") to "Movies/剧A")
        assertEquals(listOf("Movies/剧A", "Movies/剧A"), backfillFolderKeys(items, map).map { it.folderKey })
    }

    @Test
    fun `existing folder keys are not overwritten`() {
        val items = listOf(item("content://video/1", "第1集.mp4", "Movies/旧目录"))
        val map = mapOf(key("content://video/1") to "Movies/新目录")
        assertEquals(listOf("Movies/旧目录"), backfillFolderKeys(items, map).map { it.folderKey })
    }

    @Test
    fun `items without a scan mapping stay ungrouped`() {
        val items = listOf(item("content://video/9", "第9集.mp4"))
        val map = mapOf(key("content://video/1") to "Movies/剧A")
        assertEquals(listOf(""), backfillFolderKeys(items, map).map { it.folderKey })
    }

    @Test
    fun `empty mapping returns the original list`() {
        val items = listOf(item("content://video/1", "第1集.mp4"))
        assertTrue(backfillFolderKeys(items, emptyMap()) === items)
    }

    @Test
    fun `uri set and size are preserved`() {
        val items = listOf(item("content://video/1", "第1集.mp4"), item("content://video/2", "第2集.mp4"))
        val result = backfillFolderKeys(items, mapOf(key("content://video/1") to "Movies/剧A"))
        assertEquals(items.size, result.size)
        assertEquals(
            items.map { it.uri.toString() }.toSet(),
            result.map { it.uri.toString() }.toSet()
        )
    }
}
