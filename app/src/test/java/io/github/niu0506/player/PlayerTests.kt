/**
 * 本地 JVM 单元测试（不依赖设备环境）：
 * 扫描对账与元数据合并、进度状态机、安装包完整性校验、
 * 缺失记录序列化、文件夹分组键与集数自然序等。
 */
package io.github.niu0506.player

import android.net.FakeUri
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** SHA-256("abc")：更新校验相关测试共用的已知向量。 */
private const val SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

/** 时长格式化：分秒 / 时分秒、毫秒截断等。 */
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

/** 进度合并语义：0 值不覆盖、删除优先级、写删并存等。 */
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

/** 进度写入裁决：末尾容差、已看完清除、0 跳过、时长未知等。 */
class ProgressDecisionTest {

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

/** 适配器「当前播放项」更新：越界防护、仅刷新受影响条目。 */
class MediaListAdapterCurrentPlayingTest {

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
        adapter = MediaListAdapter(onClick = {}, onDelete = {}, getProgress = { 0L })
        observer = RecordingObserver()
        attachObserver(adapter, observer)
    }

    /**
     * 把观察者挂到 adapter 上。
     *
     * 不能用 `registerAdapterDataObserver`：它最终写入框架类 `android.database.Observable.mObservers`，
     * 而单元测试运行在桩化的 android.jar 上（`isReturnDefaultValues = true`），框架构造器为空，
     * `mObservers` 恒为 null，notify 时直接 NPE。这里显式初始化该字段再挂上观察者。
     */
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
        repeat(3) { adapter.items.add(item("u$it")) }
        adapter.setCurrentPlaying("u1")
        assertEquals(listOf(1), observer.changedPositions)

        adapter.setCurrentPlaying("u2", true)
        assertEquals(listOf(1, 1, 2), observer.changedPositions)
    }

    @Test
    fun `state is recorded even when uri is not in items yet`() {
        adapter.setCurrentPlaying("u5", true)
        assertEquals("u5", adapter.currentPlayingUri)
        assertTrue(observer.changedPositions.isEmpty())

        repeat(6) { adapter.items.add(item("u$it")) }
        adapter.setCurrentPlaying("u2", true)
        assertEquals(listOf(5, 2), observer.changedPositions)
    }

    @Test
    fun `stale old uri is suppressed after list shrinks`() {
        repeat(2) { adapter.items.add(item("u$it")) }
        adapter.setCurrentPlaying("u1")
        assertEquals(listOf(1), observer.changedPositions)

        adapter.items.clear()
        observer.changedPositions.clear()

        adapter.setCurrentPlaying("u0")
        assertTrue(observer.changedPositions.isEmpty())
    }

    @Test
    fun `same uri and unchanged playing state notifies nothing`() {
        repeat(3) { adapter.items.add(item("u$it")) }
        adapter.setCurrentPlaying("u1", true)
        observer.changedPositions.clear()

        adapter.setCurrentPlaying("u1", true)
        assertTrue(observer.changedPositions.isEmpty())
    }

    @Test
    fun `same uri with playing toggle notifies the row once`() {
        repeat(3) { adapter.items.add(item("u$it")) }
        adapter.setCurrentPlaying("u1", false)
        observer.changedPositions.clear()

        adapter.setCurrentPlaying("u1", true)
        assertEquals(listOf(1), observer.changedPositions)
    }
}

/** 缺失文件记录的序列化与解析容错。 */
class MissingRecordsTest {

    @Test
    fun `null or empty json returns empty map`() {
        assertEquals(emptyMap<String, MissingInfo>(), parseMissingJson(null))
        assertEquals(emptyMap<String, MissingInfo>(), parseMissingJson(""))
    }

    @Test
    fun `garbage json returns empty map`() {
        assertEquals(emptyMap<String, MissingInfo>(), parseMissingJson("{not valid json"))
    }

    @Test
    fun `encode then parse round trips`() {
        val state = mapOf(
            "content://video/1" to MissingInfo(1_700_000_000_000L, 3),
            "content://audio/2" to MissingInfo(1_700_000_001_000L, 0)
        )
        assertEquals(state, parseMissingJson(encodeMissingJson(state)))
    }

    @Test
    fun `empty map round trips to empty string map`() {
        assertEquals(emptyMap<String, MissingInfo>(), parseMissingJson(encodeMissingJson(emptyMap())))
    }

    @Test
    fun `entries with non positive first missing time are dropped`() {
        val json = """{"a":{"first":0,"count":5},"b":{"first":-1,"count":2},"c":{"first":123,"count":1}}"""
        assertEquals(mapOf("c" to MissingInfo(123L, 1)), parseMissingJson(json))
    }

    @Test
    fun `entry with missing time but no count defaults count to zero`() {
        assertEquals(mapOf("a" to MissingInfo(100L, 0)), parseMissingJson("""{"a":{"first":100}}"""))
    }

    @Test
    fun `non object values are skipped`() {
        val json = """{"a":123,"b":"x"}"""
        assertEquals(emptyMap<String, MissingInfo>(), parseMissingJson(json))
    }
}

/** SHA-256 解析与十六进制编码。 */
class UpdateIntegrityTest {

    private val hex64 = SHA256_ABC

    @Test
    fun `null blank and garbage digests return null`() {
        assertEquals(null, parseSha256Digest(null))
        assertEquals(null, parseSha256Digest(""))
        assertEquals(null, parseSha256Digest("   "))
        assertEquals(null, parseSha256Digest("sha256:"))
        assertEquals(null, parseSha256Digest("not-a-hash"))
    }

    @Test
    fun `wrong length or non hex digests are rejected`() {
        assertEquals(null, parseSha256Digest("abcdef"))
        assertEquals(null, parseSha256Digest("z".repeat(64)))
        assertEquals(null, parseSha256Digest(hex64.dropLast(1)))
    }

    @Test
    fun `bare hex and sha256 prefixed hex are both accepted`() {
        assertEquals(hex64, parseSha256Digest(hex64))
        assertEquals(hex64, parseSha256Digest("sha256:$hex64"))
        assertEquals(hex64, parseSha256Digest("  sha256:$hex64  "))
    }

    @Test
    fun `uppercase digest is normalized to lowercase`() {
        assertEquals(hex64, parseSha256Digest(hex64.uppercase()))
        assertEquals(hex64, parseSha256Digest("SHA256:${hex64.uppercase()}"))
    }

    @Test
    fun `toHex encodes bytes as lowercase two digit pairs`() {
        assertEquals("", toHex(ByteArray(0)))
        assertEquals("616263", toHex(byteArrayOf(0x61, 0x62, 0x63)))
        assertEquals("00ff10", toHex(byteArrayOf(0x00, 0xff.toByte(), 0x10)))
    }

    @Test
    fun `toHex matches SHA-256 of known input`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals(hex64, toHex(digest))
    }
}

/** 安装包校验结论：缺失 / 匹配 / 不匹配。 */
class ApkValidationTest {

    private val hex64 = SHA256_ABC

    @Test
    fun `missing expected checksum is rejected`() {
        assertEquals(ApkValidation.MISSING_CHECKSUM, verifyApkChecksum(null, hex64))
        assertEquals(ApkValidation.MISSING_CHECKSUM, verifyApkChecksum("", hex64))
    }

    @Test
    fun `matching checksum is accepted`() {
        assertEquals(ApkValidation.OK, verifyApkChecksum(hex64, hex64))
    }

    @Test
    fun `checksum comparison ignores case`() {
        assertEquals(ApkValidation.OK, verifyApkChecksum(hex64.uppercase(), hex64))
        assertEquals(ApkValidation.OK, verifyApkChecksum(hex64, hex64.uppercase()))
    }

    @Test
    fun `mismatched checksum is flagged`() {
        assertEquals(ApkValidation.INVALID, verifyApkChecksum(hex64.dropLast(1) + "0", hex64))
    }
}

/** 已下载版本是否已安装（用于区分「下载失败」与「安装完成」）。 */
class DownloadedVersionInstalledTest {

    @Test
    fun `same version counts as installed`() {
        assertTrue(isDownloadedVersionInstalled("1.5.15", "1.5.15"))
    }

    @Test
    fun `newer installed version counts as installed`() {
        assertTrue(isDownloadedVersionInstalled("1.5.15", "1.5.16"))
    }

    @Test
    fun `older installed version is not installed yet`() {
        assertFalse(isDownloadedVersionInstalled("1.5.15", "1.5.14"))
    }

    @Test
    fun `unknown installed version is not treated as installed`() {
        assertFalse(isDownloadedVersionInstalled("1.5.15", null))
    }
}

/** 版本号比较规则。 */
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

/** 文件夹分组键提取：相对路径 / 绝对路径 / 不同存储卷。 */
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

}

/** 自然排序：集数按数值、前导零、大小写、超长数字段的确定性。 */
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
        val sorted = names.sortedWith(comparator)
        assertEquals(sorted, names.reversed().sortedWith(comparator))
        assertEquals(sorted, sorted.sortedWith(comparator))
    }
}

/** 播放列表排序：同目录相邻、未分组置后、排序幂等。 */
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

/** 扫描元数据合并：文件名 / 时长 / 文件夹刷新，缺失字段不覆盖。 */
class ScannedMetadataMergeTest {

    private fun item(
        uri: String,
        name: String,
        duration: Long = 0L,
        folder: String = "",
    ) = MediaItemData(FakeUri(uri), name, duration, folder)

    @Test
    fun `existing item metadata is refreshed from scan`() {
        val items = listOf(item("content://video/1", "旧名.mp4", 10_000, "旧目录"))
        val scanned = listOf(item("content://video/1", "新名.mp4", 20_000, "新目录"))

        val result = mergeScannedMetadata(items, scanned)

        assertEquals("新名.mp4", result.single().name)
        assertEquals(20_000L, result.single().duration)
        assertEquals("新目录", result.single().folderKey)
    }

    @Test
    fun `blank name and zero duration keep existing values`() {
        val items = listOf(item("content://video/1", "旧名.mp4", 10_000, "目录"))
        val scanned = listOf(item("content://video/1", "  ", 0, "目录"))

        val result = mergeScannedMetadata(items, scanned)

        assertEquals("旧名.mp4", result.single().name)
        assertEquals(10_000L, result.single().duration)
    }

    @Test
    fun `moving to root clears the folder key`() {
        val items = listOf(item("content://video/1", "剧.mp4", 10_000, "Movies/剧A"))
        val scanned = listOf(item("content://video/1", "剧.mp4", 10_000, ""))

        assertEquals("", mergeScannedMetadata(items, scanned).single().folderKey)
    }

    @Test
    fun `items not present in scan keep their metadata`() {
        val items = listOf(item("content://video/9", "第9集.mp4", 10_000, "Movies/剧A"))
        val scanned = listOf(item("content://video/1", "第1集.mp4", 10_000, "Movies/剧A"))

        val result = mergeScannedMetadata(items, scanned)

        assertEquals(items, result)
        assertTrue(result === items)
    }

    @Test
    fun `empty scan returns the original list`() {
        val items = listOf(item("content://video/1", "第1集.mp4"))
        assertTrue(mergeScannedMetadata(items, emptyList()) === items)
    }

    @Test
    fun `uri set and size are preserved`() {
        val items = listOf(
            item("content://video/1", "第1集.mp4"),
            item("content://video/2", "第2集.mp4"),
        )
        val scanned = listOf(item("content://video/1", "第1集（重命名）.mp4", 10_000, "Movies"))
        val result = mergeScannedMetadata(items, scanned)

        assertEquals(items.size, result.size)
        assertEquals(
            items.map { it.uri.toString() }.toSet(),
            result.map { it.uri.toString() }.toSet(),
        )
    }
}

/** 首次加载合并：数据库顺序优先、扫描独有条目追加、按归一化 URI 去重。 */
class PlaylistLoadMergeTest {

    private fun item(uri: String) = MediaItemData(FakeUri(uri), uri)

    @Test
    fun `stored order wins and duplicates are dropped`() {
        val stored = listOf(item("content://video/2"), item("content://video/1"))
        val scanned = listOf(item("content://video/1"), item("content://video/2"))
        assertEquals(
            listOf("content://video/2", "content://video/1"),
            mergePlaylistOnLoad(stored, scanned).map { it.uri.toString() }
        )
    }

    @Test
    fun `scanned only items are appended`() {
        val stored = listOf(item("content://video/1"))
        val scanned = listOf(item("content://video/2"))
        assertEquals(
            listOf("content://video/1", "content://video/2"),
            mergePlaylistOnLoad(stored, scanned).map { it.uri.toString() }
        )
    }

    @Test
    fun `duplicate stored entries are collapsed`() {
        val stored = listOf(item("content://video/1"), item("content://video/1"))
        assertEquals(1, mergePlaylistOnLoad(stored, emptyList()).size)
    }
}

/** 缺失记录状态迁移：首次、累加、恢复与删除优先级。 */
class MissingRecordsMergeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `first miss starts at count one`() {
        val result = mergeMissingRecords(emptyMap(), listOf("a"), emptyList(), emptySet(), now)
        assertEquals(mapOf("a" to MissingInfo(now, 1)), result)
    }

    @Test
    fun `repeated miss keeps first time and increments count`() {
        val current = mapOf("a" to MissingInfo(now - 1_000, 2))
        val result = mergeMissingRecords(current, listOf("a"), emptyList(), emptySet(), now)
        assertEquals(mapOf("a" to MissingInfo(now - 1_000, 3)), result)
    }

    @Test
    fun `observed uri is removed`() {
        val current = mapOf(
            "a" to MissingInfo(now - 1_000, 1),
            "b" to MissingInfo(now - 1_000, 1)
        )
        val result = mergeMissingRecords(current, emptyList(), listOf("a"), emptySet(), now)
        assertEquals(mapOf("b" to MissingInfo(now - 1_000, 1)), result)
    }

    @Test
    fun `removed uri wins over missing and observed`() {
        val current = mapOf("a" to MissingInfo(now - 1_000, 1))
        val result = mergeMissingRecords(current, listOf("a"), listOf("a"), setOf("a"), now)
        assertEquals(emptyMap<String, MissingInfo>(), result)
    }

    @Test
    fun `same uri observed and missing stays missing and increments`() {
        val current = mapOf("a" to MissingInfo(now - 1_000, 1))
        val result = mergeMissingRecords(current, listOf("a"), listOf("a"), emptySet(), now)
        assertEquals(mapOf("a" to MissingInfo(now - 1_000, 2)), result)
    }
}

/** 显式 seek 回 0 的判定：只清当前条目从非零位置回到 0 的断点。 */
class SeekToZeroTest {

    @Test
    fun `same item seek from positive to zero clears`() {
        assertTrue(
            isExplicitSeekToZero(Player.DISCONTINUITY_REASON_SEEK, true, 5_000, 0)
        )
    }

    @Test
    fun `seek from zero does not clear`() {
        assertFalse(
            isExplicitSeekToZero(Player.DISCONTINUITY_REASON_SEEK, true, 0, 0)
        )
    }

    @Test
    fun `cross item seek does not clear`() {
        assertFalse(
            isExplicitSeekToZero(Player.DISCONTINUITY_REASON_SEEK, false, 5_000, 0)
        )
    }

    @Test
    fun `non seek discontinuity does not clear`() {
        assertFalse(
            isExplicitSeekToZero(Player.DISCONTINUITY_REASON_INTERNAL, true, 5_000, 0)
        )
    }
}

/** 落盘重试必须在锁内等待，后续写入不能越过失败重试。 */
class OrderedRetryTest {

    @Test
    fun `retry holds the lock so later writes cannot overtake`() = runBlocking {
        val mutex = Mutex()
        val events = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        var firstAttempts = 0

        val first = launch(Dispatchers.Default) {
            withOrderedRetry(mutex, maxAttempts = 2, retryDelayMs = 20) {
                firstAttempts++
                if (firstAttempts == 1) {
                    firstStarted.complete(Unit)
                    throw IllegalStateException("first attempt fails")
                }
                events += "first-success"
            }
        }
        firstStarted.await()
        val second = launch(Dispatchers.Default) {
            withOrderedRetry(mutex, maxAttempts = 1, retryDelayMs = 0) {
                events += "second-success"
            }
        }
        first.join()
        second.join()

        assertEquals(listOf("first-success", "second-success"), events)
    }
}

/** 系统亮度归一化：0..255 映射到 0..1，未知值回退 0.5。 */
class BrightnessFractionTest {

    @Test
    fun `zero and max map to ends`() {
        assertEquals(0f, systemBrightnessToFraction(0), 0.001f)
        assertEquals(1f, systemBrightnessToFraction(255), 0.001f)
    }

    @Test
    fun `unknown falls back to half`() {
        assertEquals(0.5f, systemBrightnessToFraction(null), 0.001f)
    }

    @Test
    fun `out of range is clamped`() {
        assertEquals(0f, systemBrightnessToFraction(-10), 0.001f)
        assertEquals(1f, systemBrightnessToFraction(999), 0.001f)
    }
}

/** 扫描对账纯函数：新增、删除、元数据刷新、静默模式与排序。 */
class ScanReconcileTest {

    private fun item(
        uri: String,
        name: String,
        duration: Long = 10_000L,
        folder: String = "",
    ) = MediaItemData(FakeUri(uri), name, duration, folder)

    @Test
    fun `manual scan appends new items that are long enough`() {
        val current = listOf(item("content://video/1", "第1集.mp4", 10_000, "剧A"))
        val scanned = listOf(
            item("content://video/1", "第1集.mp4", 10_000, "剧A"),
            item("content://video/2", "第2集.mp4", 10_000, "剧A"),
        )

        val result = reconcilePlaylist(current, scanned, null, emptySet(), silent = false)

        assertEquals(1, result.addedCount)
        assertEquals(0, result.removedCount)
        assertEquals(2, result.playlist.size)
        assertTrue(result.playlist.any { it.name == "第2集.mp4" })
    }

    @Test
    fun `short clips are not imported`() {
        val scanned = listOf(item("content://video/short", "提示音.mp4", 4_999))

        val result = reconcilePlaylist(emptyList(), scanned, null, emptySet(), silent = false)

        assertEquals(0, result.addedCount)
        assertTrue(result.playlist.isEmpty())
    }

    @Test
    fun `silent scan never appends files`() {
        val current = listOf(item("content://video/1", "第1集.mp4"))
        val scanned = listOf(
            item("content://video/1", "第1集.mp4"),
            item("content://video/2", "第2集.mp4"),
        )

        val result = reconcilePlaylist(current, scanned, null, emptySet(), silent = true)

        assertEquals(0, result.addedCount)
        assertEquals(1, result.playlist.size)
        assertEquals("content://video/1", result.playlist.single().uri.toString())
    }

    @Test
    fun `removed uris are pruned and counted`() {
        val current = listOf(
            item("content://video/1", "第1集.mp4"),
            item("content://video/2", "第2集.mp4"),
        )
        val scanned = listOf(item("content://video/2", "第2集.mp4"))

        val result = reconcilePlaylist(
            current,
            scanned,
            null,
            removedUris = setOf("content://video/1"),
            silent = true,
        )

        assertEquals(1, result.removedCount)
        assertEquals(listOf("content://video/2"), result.playlist.map { it.uri.toString() })
    }

    @Test
    fun `duplicate scan entries are added once`() {
        val scanned = listOf(
            item("content://video/1", "第1集.mp4"),
            item("content://video/1", "第1集.mp4"),
        )

        val result = reconcilePlaylist(emptyList(), scanned, null, emptySet(), silent = false)

        assertEquals(1, result.addedCount)
        assertEquals(1, result.playlist.size)
    }

    @Test
    fun `existing metadata is refreshed without reporting a reorder`() {
        val current = listOf(item("content://video/1", "旧名.mp4", 10_000, "剧A"))
        val scanned = listOf(item("content://video/1", "新名.mp4", 20_000, "剧A"))

        val result = reconcilePlaylist(current, scanned, null, emptySet(), silent = true)

        assertEquals("新名.mp4", result.playlist.single().name)
        assertEquals(20_000L, result.playlist.single().duration)
        assertFalse(result.orderChanged)
        assertEquals(0, result.addedCount)
    }

    @Test
    fun `folder changes reorder grouped items before ungrouped ones`() {
        val current = listOf(
            item("content://video/1", "Z.mp4", 10_000, ""),
            item("content://video/2", "A.mp4", 10_000, ""),
        )
        val scanned = listOf(
            item("content://video/1", "Z.mp4", 10_000, "Movies"),
            item("content://video/2", "A.mp4", 10_000, "Movies"),
        )

        val result = reconcilePlaylist(current, scanned, null, emptySet(), silent = true)

        assertEquals(listOf("A.mp4", "Z.mp4"), result.playlist.map { it.name })
        assertTrue(result.orderChanged)
    }

    @Test
    fun `items missing from scan keep their old metadata`() {
        val current = listOf(item("content://video/9", "离线.mp4", 10_000, "剧A"))
        val scanned = listOf(item("content://video/1", "在线.mp4", 10_000, "剧A"))

        val result = reconcilePlaylist(current, scanned, null, emptySet(), silent = true)

        assertEquals("离线.mp4", result.playlist.single().name)
        assertEquals("剧A", result.playlist.single().folderKey)
    }
}

/** 缺失文件删除裁决：权限、卷状态、确认查询与 24 小时宽限期。 */
class PruneDecisionTest {

    private val videoPrefix = "content://media/external/video/media/"
    private val audioPrefix = "content://media/external/audio/media/"
    private val graceMs = 24 * 60 * 60 * 1000L

    private fun item(uri: String) = MediaItemData(FakeUri(uri), uri)

    private fun key(uri: String): String = normalizeUri(FakeUri(uri))

    @Test
    fun `confirmed deletion removes the item immediately`() {
        val uri = "${videoPrefix}1"
        var confirmations = 0

        val result = decidePruneCandidates(
            playlistSnapshot = listOf(item(uri)),
            videoKeys = emptySet(),
            audioKeys = null,
            videoPrefix = videoPrefix,
            audioPrefix = audioPrefix,
            canConfirmImmediately = true,
            missingInfo = { null },
            confirmedDeleted = { confirmations++; true },
            now = 1_000,
            graceMs = graceMs,
        )

        assertTrue(result.removedUris.contains(uri))
        assertTrue(result.missingUris.contains(uri))
        assertTrue(result.observedUris.contains(uri))
        assertEquals(1, confirmations)
    }

    @Test
    fun `unconfirmed first miss is kept and recorded`() {
        val uri = "${videoPrefix}1"

        val result = decidePruneCandidates(
            playlistSnapshot = listOf(item(uri)),
            videoKeys = emptySet(),
            audioKeys = null,
            videoPrefix = videoPrefix,
            audioPrefix = audioPrefix,
            canConfirmImmediately = false,
            missingInfo = { null },
            confirmedDeleted = { error("卷不可用时不应做单条确认") },
            now = 1_000,
            graceMs = graceMs,
        )

        assertTrue(result.removedUris.isEmpty())
        assertTrue(result.missingUris.contains(uri))
        assertTrue(result.observedUris.contains(uri))
    }

    @Test
    fun `grace expiry removes an item after repeated misses`() {
        val uri = "${videoPrefix}1"
        val now = 10 * graceMs
        val missing = mapOf(uri to MissingInfo(now - graceMs - 1, 1))

        val result = decidePruneCandidates(
            playlistSnapshot = listOf(item(uri)),
            videoKeys = emptySet(),
            audioKeys = null,
            videoPrefix = videoPrefix,
            audioPrefix = audioPrefix,
            canConfirmImmediately = false,
            missingInfo = { missing[it] },
            confirmedDeleted = { error("卷不可用时不应做单条确认") },
            now = now,
            graceMs = graceMs,
        )

        assertTrue(result.removedUris.contains(uri))
    }

    @Test
    fun `grace not expired still keeps the item`() {
        val uri = "${videoPrefix}1"
        val now = 10 * graceMs
        val missing = mapOf(uri to MissingInfo(now - graceMs + 1, 3))

        val result = decidePruneCandidates(
            playlistSnapshot = listOf(item(uri)),
            videoKeys = emptySet(),
            audioKeys = null,
            videoPrefix = videoPrefix,
            audioPrefix = audioPrefix,
            canConfirmImmediately = false,
            missingInfo = { missing[it] },
            confirmedDeleted = { false },
            now = now,
            graceMs = graceMs,
        )

        assertTrue(result.removedUris.isEmpty())
    }

    @Test
    fun `video is skipped when only partial visual permission is granted`() {
        val videoUri = "${videoPrefix}1"

        val result = decidePruneCandidates(
            playlistSnapshot = listOf(item(videoUri)),
            videoKeys = null,
            audioKeys = emptySet(),
            videoPrefix = videoPrefix,
            audioPrefix = audioPrefix,
            canConfirmImmediately = true,
            missingInfo = { null },
            confirmedDeleted = { error("部分授权时不应查询视频") },
            now = 1_000,
            graceMs = graceMs,
        )

        assertTrue(result.removedUris.isEmpty())
        assertTrue(result.missingUris.isEmpty())
        assertTrue(result.observedUris.isEmpty())
    }

    @Test
    fun `audio-only permission still checks audio entries`() {
        val videoUri = "${videoPrefix}1"
        val audioUri = "${audioPrefix}2"

        val result = decidePruneCandidates(
            playlistSnapshot = listOf(item(videoUri), item(audioUri)),
            videoKeys = null,
            audioKeys = emptySet(),
            videoPrefix = videoPrefix,
            audioPrefix = audioPrefix,
            canConfirmImmediately = false,
            missingInfo = { null },
            confirmedDeleted = { false },
            now = 1_000,
            graceMs = graceMs,
        )

        assertTrue(result.missingUris.contains(audioUri))
        assertFalse(result.missingUris.contains(videoUri))
        assertTrue(result.observedUris.contains(audioUri))
        assertFalse(result.observedUris.contains(videoUri))
    }

    @Test
    fun `unrelated uris are ignored`() {
        val uri = "file:///sdcard/movie.mp4"

        val result = decidePruneCandidates(
            playlistSnapshot = listOf(item(uri)),
            videoKeys = emptySet(),
            audioKeys = emptySet(),
            videoPrefix = videoPrefix,
            audioPrefix = audioPrefix,
            canConfirmImmediately = true,
            missingInfo = { null },
            confirmedDeleted = { error("非 MediaStore 条目不应查询") },
            now = 1_000,
            graceMs = graceMs,
        )

        assertTrue(result.missingUris.isEmpty())
        assertTrue(result.observedUris.isEmpty())
        assertTrue(result.removedUris.isEmpty())
    }
}

/** 进度缓存状态机：末尾裁决、删除抑制、快照与失败重试。 */
class ProgressCacheTest {

    private fun newCache(uri: String = "u", position: Long = 10_000, duration: Long = 60_000) =
        ProgressCache().apply { cache(uri, position, duration) }

    @Test
    fun `stored position becomes a write`() {
        val progress = newCache()

        assertEquals(mapOf("u" to 10_000L), progress.writes())
    }

    @Test
    fun `position inside the tail is cleared instead of written`() {
        val progress = newCache(position = 59_000)

        assertTrue(progress.writes().isEmpty())
        assertEquals(setOf("u"), progress.pendingRemovals())
    }

    @Test
    fun `zero position is skipped`() {
        val progress = newCache(position = 0)

        assertTrue(progress.writes().isEmpty())
        assertTrue(progress.pendingRemovals().isEmpty())
    }

    @Test
    fun `dropped current item is not recached until it leaves the queue`() {
        val progress = newCache()
        progress.drop(listOf("u"), currentUri = "u")

        progress.cache("u", 20_000, 60_000)
        assertTrue(progress.writes().isEmpty())
        assertTrue(progress.hasPendingDrops())

        progress.retainPending(emptySet())
        progress.cache("u", 30_000, 60_000)
        assertEquals(mapOf("u" to 30_000L), progress.writes())
    }

    @Test
    fun `dropping a non current item does not suppress it`() {
        val progress = newCache()
        progress.drop(listOf("u"), currentUri = "other")

        progress.cache("u", 20_000, 60_000)
        assertEquals(mapOf("u" to 20_000L), progress.writes())
    }

    @Test
    fun `writes skip positions already persisted`() {
        val progress = newCache()
        progress.markPersisted(progress.snapshot(), emptySet())

        assertTrue(progress.writes().isEmpty())

        progress.cache("u", 20_000, 60_000)
        assertEquals(mapOf("u" to 20_000L), progress.writes())
    }

    @Test
    fun `successful persist clears submitted removals`() {
        val progress = newCache(position = 59_000)

        progress.markPersisted(progress.snapshot(), progress.pendingRemovals())

        assertTrue(progress.pendingRemovals().isEmpty())
    }

    @Test
    fun `failed persist keeps removals for a retry`() {
        val progress = newCache(position = 59_000)

        progress.markFailed(progress.pendingRemovals())

        assertEquals(setOf("u"), progress.pendingRemovals())
    }

    @Test
    fun `drop removes cached value and snapshot entry`() {
        val progress = newCache()
        progress.markPersisted(progress.snapshot(), emptySet())
        progress.drop(listOf("u"), currentUri = null)

        progress.cache("u", 20_000, 60_000)
        assertEquals(mapOf("u" to 20_000L), progress.writes())
    }

    @Test
    fun `clearAllPendingDrops releases all suppression`() {
        val progress = newCache()
        progress.drop(listOf("u"), currentUri = "u")
        progress.clearAllPendingDrops()

        progress.cache("u", 20_000, 60_000)
        assertEquals(mapOf("u" to 20_000L), progress.writes())
    }

    @Test
    fun `drop during in flight write is not resurrected by stale snapshot`() {
        val progress = newCache()
        val snapshot = progress.snapshot()

        progress.drop(listOf("u"), currentUri = null)
        progress.markPersisted(snapshot, emptySet())
        progress.cache("u", 10_000, 60_000)

        assertEquals(mapOf("u" to 10_000L), progress.writes())
    }

    @Test
    fun `clear during in flight write is not resurrected by stale snapshot`() {
        val progress = newCache()
        val snapshot = progress.snapshot()

        progress.clear("u")
        progress.markPersisted(snapshot, emptySet())
        progress.cache("u", 10_000, 60_000)

        assertEquals(mapOf("u" to 10_000L), progress.writes())
    }

    @Test
    fun `clearAll removes every cached state`() {
        val progress = newCache()
        progress.markPersisted(progress.snapshot(), emptySet())
        progress.clearAll()

        assertTrue(progress.writes().isEmpty())
        assertFalse(progress.hasPendingRemovals())
        assertFalse(progress.hasPendingDrops())

        progress.cache("u", 10_000, 60_000)
        assertEquals(mapOf("u" to 10_000L), progress.writes())
    }
}

/** 扫描行解析与音频类型过滤。 */
class MediaScanFilterTest {

    @Test
    fun `null display name uses fallback`() {
        assertEquals("未知文件", scanDisplayNameOrFallback(null, "未知文件"))
    }

    @Test
    fun `blank display name uses fallback`() {
        assertEquals("未知文件", scanDisplayNameOrFallback("  \t ", "未知文件"))
    }

    @Test
    fun `normal display name is kept`() {
        assertEquals("第 1 集.mp4", scanDisplayNameOrFallback("第 1 集.mp4", "未知文件"))
    }

    @Test
    fun `ringtone notification and alarm are excluded`() {
        assertTrue(isExcludedAudioType(1, 0, 0))
        assertTrue(isExcludedAudioType(0, 1, 0))
        assertTrue(isExcludedAudioType(0, 0, 1))
        assertFalse(isExcludedAudioType(0, 0, 0))
    }
}

/** 队列项按 URI 定位：覆盖列表与队列错位时的删除路径。 */
class MediaQueueLookupTest {

    @Test
    fun `finds queue item by normalized uri`() {
        val queue = listOf(FakeUri("content://media/audio/1"), null, FakeUri("content://media/audio/2"))

        assertEquals(
            0,
            findMediaItemIndexByUri(queue.size, { queue[it] }, FakeUri("content://media/audio/1"))
        )
        assertEquals(
            2,
            findMediaItemIndexByUri(queue.size, { queue[it] }, FakeUri("content://media/audio/2?x=1"))
        )
    }

    @Test
    fun `missing uri returns minus one`() {
        val queue = listOf(FakeUri("content://media/audio/1"))
        assertEquals(
            -1,
            findMediaItemIndexByUri(queue.size, { queue[it] }, FakeUri("content://media/audio/9"))
        )
    }

    @Test
    fun `empty queue returns minus one`() {
        assertEquals(-1, findMediaItemIndexByUri(0, { null }, FakeUri("content://media/audio/1")))
    }
}

/** 数据库自愈判定：只对明确的损坏/迁移异常清库。 */
class DatabaseSelfHealTest {

    @Test
    fun `malformed database message is self healable`() {
        assertTrue(
            isSelfHealableDatabaseFailure(
                RuntimeException("database disk image is malformed")
            )
        )
    }

    @Test
    fun `missing migration is self healable`() {
        assertTrue(
            isSelfHealableDatabaseFailure(
                IllegalStateException(
                    "A migration from 1 to 2 was required but not found. " +
                        "Please provide the necessary Migration path."
                )
            )
        )
    }

    @Test
    fun `wrapped migration failure is self healable`() {
        assertTrue(
            isSelfHealableDatabaseFailure(
                IllegalStateException(
                    "open failed",
                    IllegalStateException("Room cannot verify the data integrity.")
                )
            )
        )
    }

    @Test
    fun `disk full is not self healable`() {
        assertFalse(
            isSelfHealableDatabaseFailure(
                RuntimeException("No space left on device")
            )
        )
    }

    @Test
    fun `generic failure is not self healable`() {
        assertFalse(isSelfHealableDatabaseFailure(IllegalStateException("Something else")))
    }
}
