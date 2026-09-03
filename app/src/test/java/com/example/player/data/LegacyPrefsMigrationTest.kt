package com.example.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
