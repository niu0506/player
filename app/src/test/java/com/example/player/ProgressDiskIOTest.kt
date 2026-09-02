package com.example.player

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * 进度「读盘→合并→写盘」落盘路径的单元测试：
 * 守护 writeToDisk / writeProgressBatch 共用 progressPrefsLock 的串行化修复。
 *
 * 背景：Service 主线程（writeToDisk）与 Activity IO 线程（writeProgressBatch）
 * 各自的「读-合并-写」都不是原子的；不加锁时并发合并会互相丢更新——
 * 丢 removes 时已删进度「复活」，丢 writes 时新进度被旧快照覆盖。
 * 本测试通过线程安全的内存版 SharedPreferences 直接并发调用 writeProgressBatch，
 * 若锁被移除，读-合并-写交错会导致断言失败。
 */
class ProgressDiskIOTest {

    /** 线程安全的内存版 SharedPreferences：单次读写原子，但不保证调用方的「读-改-写」序列原子 */
    private class FakePrefs : SharedPreferences {
        private val lock = Any()
        private val store = HashMap<String?, Any?>()

        override fun getAll(): Map<String?, Any?> = synchronized(lock) { store.toMap() }
        override fun getString(key: String?, defValue: String?): String? =
            synchronized(lock) { store[key] as? String ?: defValue }

        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            synchronized(lock) { store[key] as? Set<*> as? Set<String> ?: defValues }

        override fun getInt(key: String?, defValue: Int): Int =
            synchronized(lock) { (store[key] as? Int) ?: defValue }

        override fun getLong(key: String?, defValue: Long): Long =
            synchronized(lock) { (store[key] as? Long) ?: defValue }

        override fun getFloat(key: String?, defValue: Float): Float =
            synchronized(lock) { (store[key] as? Float) ?: defValue }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            synchronized(lock) { (store[key] as? Boolean) ?: defValue }

        override fun contains(key: String?): Boolean = synchronized(lock) { store.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String?, Any?>()

            // 注意不能用 Kotlin 标准库的 apply { ... } 链式写法：与 Editor.apply() 撞名会解析错乱
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                synchronized(this@FakePrefs.lock) { store.clear() }
                return this
            }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() {
                flush()
            }

            private fun flush() {
                synchronized(this@FakePrefs.lock) {
                    for ((k, v) in pending) {
                        if (v == null) store.remove(k) else store[k] = v
                    }
                }
            }
        }
    }

    @Test
    fun `concurrent writes lose no updates`() {
        val prefs = FakePrefs()
        val threads = (0 until THREADS).map { t ->
            thread {
                repeat(ITERS) { i ->
                    PlayerService.writeProgressBatch(prefs, mapOf("t$t-$i" to (i + 1).toLong()))
                }
            }
        }
        threads.forEach { it.join() }

        // 每次写入 key 唯一且值 >0：若合并丢失任何一次写入，size 与对应值都会对不上
        val result = PlayerService.readProgressMap(prefs)
        assertEquals(THREADS * ITERS, result.size)
        for (t in 0 until THREADS) {
            for (i in 0 until ITERS) {
                assertEquals((i + 1).toLong(), result["t$t-$i"])
            }
        }
    }

    @Test
    fun `concurrent removes are not resurrected`() {
        val prefs = FakePrefs()
        val seed = (0 until REMOVE_TOTAL).associate { "k$it" to (it + 1).toLong() }
        PlayerService.writeProgressBatch(prefs, seed)

        // 线程 t 负责移除下标 ≡ t (mod THREADS) 的 key：全部条目都会被某线程移除一次。
        // 若「读-合并-写」未串行化，一个线程的移除会被另一线程的旧快照写回（进度复活）。
        val threads = (0 until THREADS).map { t ->
            thread {
                for (i in t until REMOVE_TOTAL step THREADS) {
                    PlayerService.writeProgressBatch(prefs, emptyMap(), setOf("k$i"))
                }
            }
        }
        threads.forEach { it.join() }

        assertTrue(PlayerService.readProgressMap(prefs).isEmpty())
    }

    @Test
    fun `write then remove round trip keeps read cache fresh`() {
        val prefs = FakePrefs()
        PlayerService.writeProgressBatch(prefs, mapOf("a" to 100L, "b" to 200L))
        assertEquals(mapOf("a" to 100L, "b" to 200L), PlayerService.readProgressMap(prefs))

        // 移除后读侧解析缓存必须失效重读：readProgressMap 不能返回移除前的旧缓存
        PlayerService.writeProgressBatch(prefs, emptyMap(), setOf("a"))
        assertEquals(mapOf("b" to 200L), PlayerService.readProgressMap(prefs))
    }

    private companion object {
        const val THREADS = 8
        const val ITERS = 100
        const val REMOVE_TOTAL = 200
    }
}
