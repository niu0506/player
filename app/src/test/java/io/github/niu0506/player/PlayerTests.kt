/** 本地 JVM 单元测试：扫描对账、进度状态机、更新校验、排序等纯逻辑。 */
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** SHA-256("abc") 的已知向量。 */
private const val SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

/** 仅用于比较 URI 的列表条目。 */
private fun item(uri: String): MediaItemData = MediaItemData(FakeUri(uri), uri)

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

class ProgressDecisionTest {

    private val duration = 180_000L

    @Test
    fun `position well before end is stored`() {
        assertEquals(ProgressWriteDecision.Store(60_000L), decideProgressWrite(60_000, duration))
    }

    @Test
    fun `position inside tail is stored as completion`() {
        assertEquals(
            ProgressWriteDecision.Store(duration),
            decideProgressWrite(duration - 2_000, duration)
        )
    }

    @Test
    fun `exact end and beyond store completion at duration`() {
        assertEquals(ProgressWriteDecision.Store(duration), decideProgressWrite(duration, duration))
        assertEquals(
            ProgressWriteDecision.Store(duration),
            decideProgressWrite(duration + 1, duration)
        )
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

    @Test
    fun `resumable position ignores missing zero and finished entries`() {
        assertEquals(60_000L, resumablePositionMs(60_000, duration))
        assertNull(resumablePositionMs(null, duration))
        assertNull(resumablePositionMs(0, duration))
        assertNull(resumablePositionMs(duration, duration))
        assertNull(resumablePositionMs(duration - 1_000, duration))
    }

    @Test
    fun `unknown duration still allows resuming`() {
        assertEquals(60_000L, resumablePositionMs(60_000, 0))
    }

    @Test
    fun `auto transition restores even when callback is delayed`() {
        assertTrue(
            shouldRestoreResume(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, 60_000, 1_000)
        )
        assertFalse(
            shouldRestoreResume(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, 60_000, 1_000)
        )
        assertTrue(
            shouldRestoreResume(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, 500, 1_000)
        )
    }
}

class MediaListAdapterCurrentPlayingTest {

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
        adapter = MediaListAdapter(onClick = {}, onDelete = {}, getProgress = { 0L })
        observer = RecordingObserver()
        attachObserver(adapter, observer)
    }

    /** 挂载观察者；桩化 android.jar 下 `Observable.mObservers` 恒为 null，需手动初始化。 */
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

class ReleaseNotesFilterTest {

    @Test
    fun `checksum line appended by CI is removed`() {
        val raw = "修复若干问题\n\nSHA-256: $SHA256_ABC"
        assertEquals("修复若干问题", filterReleaseNotes(raw))
    }

    @Test
    fun `list style full width colon and bare hash lines are removed`() {
        val raw = "- SHA-256：$SHA256_ABC\n- $SHA256_ABC"
        assertEquals("", filterReleaseNotes(raw))
    }

    @Test
    fun `bold marker around checksum is still removed`() {
        assertEquals("正文", filterReleaseNotes("正文\n\n**SHA-256**: $SHA256_ABC"))
    }

    @Test
    fun `normal notes are preserved`() {
        val raw = "1.6.5：修复重置数据后旧播放进度回写\n- 新增字幕选择"
        assertEquals(raw, filterReleaseNotes(raw))
    }

    @Test
    fun `blank input stays empty`() {
        assertEquals("", filterReleaseNotes("  \n\n"))
    }
}

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

class PlaylistLoadMergeTest {

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

        val result = reconcilePlaylist(current, scanned, null, emptySet())

        assertEquals(1, result.addedCount)
        assertEquals(0, result.removedCount)
        assertEquals(2, result.playlist.size)
        assertTrue(result.playlist.any { it.name == "第2集.mp4" })
    }

    @Test
    fun `short clips are not imported`() {
        val scanned = listOf(item("content://video/short", "提示音.mp4", 4_999))

        val result = reconcilePlaylist(emptyList(), scanned, null, emptySet())

        assertEquals(0, result.addedCount)
        assertTrue(result.playlist.isEmpty())
    }

    @Test
    fun `scan appends new files without manual trigger`() {
        val current = listOf(item("content://video/1", "第1集.mp4"))
        val scanned = listOf(
            item("content://video/1", "第1集.mp4"),
            item("content://video/2", "第2集.mp4"),
        )

        val result = reconcilePlaylist(current, scanned, null, emptySet())

        assertEquals(1, result.addedCount)
        assertEquals(2, result.playlist.size)
        assertTrue(result.playlist.any { it.uri.toString() == "content://video/2" })
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

        val result = reconcilePlaylist(emptyList(), scanned, null, emptySet())

        assertEquals(1, result.addedCount)
        assertEquals(1, result.playlist.size)
    }

    @Test
    fun `existing metadata is refreshed without reporting a reorder`() {
        val current = listOf(item("content://video/1", "旧名.mp4", 10_000, "剧A"))
        val scanned = listOf(item("content://video/1", "新名.mp4", 20_000, "剧A"))

        val result = reconcilePlaylist(current, scanned, null, emptySet())

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

        val result = reconcilePlaylist(current, scanned, null, emptySet())

        assertEquals(listOf("A.mp4", "Z.mp4"), result.playlist.map { it.name })
        assertTrue(result.orderChanged)
    }

    @Test
    fun `items missing from scan keep their old metadata`() {
        val current = listOf(item("content://video/9", "离线.mp4", 10_000, "剧A"))
        val scanned = listOf(item("content://video/1", "在线.mp4", 10_000, "剧A"))

        val result = reconcilePlaylist(current, scanned, null, emptySet())

        val offline = result.playlist.first { it.uri.toString() == "content://video/9" }
        assertEquals("离线.mp4", offline.name)
        assertEquals("剧A", offline.folderKey)
        // 新扫描到的文件照常导入，不再因为静默扫描而丢弃
        assertEquals(1, result.addedCount)
        assertEquals(2, result.playlist.size)
    }
}

class PruneDecisionTest {

    private val videoPrefix = "content://media/external/video/media/"
    private val audioPrefix = "content://media/external/audio/media/"
    private val graceMs = 24 * 60 * 60 * 1000L

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

class ProgressCacheTest {

    private fun newCache(uri: String = "u", position: Long = 10_000, duration: Long = 60_000) =
        ProgressCache().apply { cache(uri, position, duration) }

    @Test
    fun `stored position becomes a write`() {
        val progress = newCache()

        assertEquals(mapOf("u" to 10_000L), progress.writes())
    }

    @Test
    fun `position inside the tail is stored as a completion marker`() {
        val progress = newCache(position = 59_000)

        assertEquals(mapOf("u" to 60_000L), progress.writes())
        assertTrue(progress.pendingRemovals().isEmpty())
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
        val progress = newCache()
        progress.clear("u")

        progress.markPersisted(progress.snapshot(), progress.pendingRemovals())

        assertTrue(progress.pendingRemovals().isEmpty())
    }

    @Test
    fun `failed persist keeps removals for a retry`() {
        val progress = newCache()
        progress.clear("u")

        progress.markFailed()

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

    @Test
    fun `music rows are kept`() {
        assertFalse(isExcludedAudioRow(0, 0, 0, isMusic = 1, isRecording = 0))
    }

    @Test
    fun `non music rows are excluded`() {
        assertTrue(isExcludedAudioRow(0, 0, 0, isMusic = 0, isRecording = 0))
    }

    @Test
    fun `recording rows are excluded`() {
        assertTrue(isExcludedAudioRow(0, 0, 0, isMusic = 1, isRecording = 1))
    }

    @Test
    fun `ringtone rows are excluded even when marked as music`() {
        assertTrue(isExcludedAudioRow(1, 0, 0, isMusic = 1, isRecording = 0))
    }
}

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

class QueueDiffTest {

    private fun snap(uri: String, title: String = uri) = QueueItemSnapshot(uri, title)

    private fun applyEdits(
        current: List<QueueItemSnapshot>,
        target: List<QueueItemSnapshot>,
        edits: List<QueueEdit>,
    ): List<QueueItemSnapshot> {
        val working = current.toMutableList()
        for (edit in edits) {
            when (edit) {
                is QueueEdit.Add -> {
                    assertEquals(edit.uri, target[edit.index].uri)
                    working.add(edit.index, target[edit.index])
                }
                is QueueEdit.Remove -> {
                    assertEquals(edit.uri, working.removeAt(edit.index).uri)
                }
                is QueueEdit.Move -> {
                    val moved = working.removeAt(edit.fromIndex)
                    assertEquals(edit.uri, moved.uri)
                    working.add(edit.toIndex, moved)
                }
                is QueueEdit.Replace -> {
                    assertEquals(edit.uri, target[edit.index].uri)
                    working[edit.index] = target[edit.index]
                }
            }
        }
        return working
    }

    private fun assertPlanTransforms(
        current: List<QueueItemSnapshot>,
        target: List<QueueItemSnapshot>,
        currentUri: String? = null,
    ) {
        val plan = planQueueDiff(current, target, currentUri)
        assertFalse("当前项仍在目标中时不应整体重建", plan.requiresFullReset)
        assertEquals(target, applyEdits(current, target, plan.edits))
    }

    @Test
    fun `equal queues need no edits`() {
        val queue = listOf(snap("a"), snap("b"))
        val plan = planQueueDiff(queue, queue, "a")

        assertTrue(plan.edits.isEmpty())
        assertFalse(plan.requiresFullReset)
    }

    @Test
    fun `inserts item at target position`() {
        assertPlanTransforms(
            listOf(snap("a"), snap("c")),
            listOf(snap("a"), snap("b"), snap("c")),
            currentUri = "a",
        )
    }

    @Test
    fun `removes item missing from target`() {
        assertPlanTransforms(
            listOf(snap("a"), snap("b"), snap("c")),
            listOf(snap("a"), snap("c")),
            currentUri = "a",
        )
    }

    @Test
    fun `moves item to new position`() {
        assertPlanTransforms(
            listOf(snap("a"), snap("b"), snap("c")),
            listOf(snap("c"), snap("a"), snap("b")),
            currentUri = "a",
        )
    }

    @Test
    fun `insert before current keeps current item`() {
        val plan = planQueueDiff(
            listOf(snap("a"), snap("b")),
            listOf(snap("x"), snap("a"), snap("b")),
            currentUri = "a",
        )

        assertFalse(plan.requiresFullReset)
        assertEquals(listOf(QueueEdit.Add(0, "x")), plan.edits)
    }

    @Test
    fun `title only change uses replace`() {
        val plan = planQueueDiff(
            listOf(snap("a", "旧标题")),
            listOf(snap("a", "新标题")),
            currentUri = "a",
        )

        assertFalse(plan.requiresFullReset)
        assertEquals(listOf(QueueEdit.Replace(0, "a")), plan.edits)
    }

    @Test
    fun `reorder and title changes apply in order`() {
        assertPlanTransforms(
            listOf(snap("a", "旧 A"), snap("b", "旧 B")),
            listOf(snap("b", "新 B"), snap("a", "新 A")),
            currentUri = "a",
        )
    }

    @Test
    fun `current item removed requires full reset`() {
        val plan = planQueueDiff(
            listOf(snap("a"), snap("b")),
            listOf(snap("b")),
            currentUri = "a",
        )

        assertTrue(plan.requiresFullReset)
        assertTrue(plan.edits.isEmpty())
    }

    @Test
    fun `empty queue fills from target`() {
        assertPlanTransforms(
            emptyList(),
            listOf(snap("a"), snap("b")),
            currentUri = null,
        )
    }

    @Test
    fun `empty target removes everything`() {
        assertPlanTransforms(
            listOf(snap("a"), snap("b")),
            emptyList(),
            currentUri = null,
        )
    }

    @Test
    fun `duplicate uris still reach target order`() {
        assertPlanTransforms(
            listOf(snap("a"), snap("b"), snap("a")),
            listOf(snap("a"), snap("a")),
            currentUri = "a",
        )
    }
}
