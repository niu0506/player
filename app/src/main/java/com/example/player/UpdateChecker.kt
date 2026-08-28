package com.example.player

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查器：请求 GitHub Releases 的 latest 接口，
 * 解析最新 tag（v 开头）与 APK 资产下载地址，用于与本地版本比较。
 *
 * 必须在 IO 线程调用 [checkLatest]（内含阻塞网络请求）。
 */
class UpdateChecker {

    /** 一次 Release 检查结果：版本号 + APK 直链 + 更新说明 */
    data class Release(val version: String, val apkUrl: String, val notes: String)

    /** 请求 GitHub API 获取最新 Release；失败/无 APK 资产时返回 null */
    fun checkLatest(): Release? {
        val conn = URL(API_LATEST).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 releases/latest 的 JSON：tag_name 去掉 v 前缀作为版本号，取第一个 .apk 资产 */
    private fun parse(body: String): Release? {
        val obj = JSONObject(body)
        val version = obj.optString("tag_name").trim()
            .removePrefix("v").removePrefix("V").trim()
        if (version.isEmpty()) return null
        val assets = obj.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return Release(version, asset.optString("browser_download_url"), obj.optString("body"))
            }
        }
        return null
    }

    companion object {
        private const val API_LATEST = "https://api.github.com/repos/niu0506/player/releases/latest"

        /** 语义化版本比较：remote > current 时返回 true（如 1.1.3 > 1.1.2） */
        fun isNewer(remote: String, current: String): Boolean {
            val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
            val c = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }
    }
}
