package com.example.player.model

import org.junit.Assert.assertEquals
import org.junit.Test

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