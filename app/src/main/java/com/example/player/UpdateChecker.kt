package com.example.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查器。
 *
 * 数据源（按优先级依次尝试，任一成功即返回）：
 * 1. jsDelivr + 最新提交 hash 的 version.json —— 发版即时生效，且大陆可达性较好
 * 2. jsDelivr @main 分支的 version.json —— 可能有最长约 12 小时的 CDN 缓存
 * 3. GitHub 官方 Releases API —— 原实现，作为最后兜底
 *
 * version.json 结构：{ "version": "1.2.1", "apkUrl": "<GitHub 直链>", "notes": "..." }
 *
 * 必须在 IO 线程调用 [checkLatest]（内含阻塞网络请求）。
 */
class UpdateChecker {

    /** 一次 Release 检查结果：版本号 + APK 直链 + 更新说明 */
    data class Release(val version: String, val apkUrl: String, val notes: String)

    /** 请求远端最新版本信息；失败/解析不到时返回 null */
    fun checkLatest(): Release? {
        // 1) 先取 jsDelivr 上的最新提交 hash，请求带 hash 的 URL，避免 @main 的长缓存
        getLatestCommitHash()?.let { hash ->
            fetchVersionJson("$JS_CDN@$hash/version.json")?.let { return it }
        }
        // 2) 直接请求 @main 分支的 version.json（可能有缓存延迟）
        fetchVersionJson("$JS_CDN@main/version.json")?.let { return it }
        // 3) 兜底：GitHub 官方 API
        return fetchFromGitHubApi()
    }

    /** 发起 GET 请求并返回响应体字符串；非 200 或异常时返回 null */
    private fun httpGet(url: String, vararg headers: Pair<String, String>): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 从 data.jsdelivr.com 获取当前 main 分支的最新提交 hash；失败返回 null */
    private fun getLatestCommitHash(): String? =
        httpGet(JS_LATEST_COMMIT)?.let { body ->
            try {
                JSONObject(body).optString("default").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

    /** 请求 jsDelivr 上的 version.json 并解析；失败返回 null */
    private fun fetchVersionJson(url: String): Release? =
        httpGet(url)?.let { parseVersionJson(it) }

    /** 兜底：GitHub Releases latest 接口 */
    private fun fetchFromGitHubApi(): Release? {
        val body = httpGet(GITHUB_API_LATEST, "Accept" to "application/vnd.github+json")
            ?: return null
        // 解析时把 version.json 的字段与 GitHub API 的字段都兼容掉
        val obj = JSONObject(body)
        val version = obj.optString("version").ifBlank {
            obj.optString("tag_name").removePrefix("v").removePrefix("V")
        }.trim().removePrefix("v").removePrefix("V").ifBlank { return null }
        val apkUrl = obj.optString("apkUrl").ifBlank {
            findApkUrl(obj.optJSONArray("assets"))
        }.ifBlank { return null }
        val notes = obj.optString("notes").ifBlank { obj.optString("body") }
        return Release(version, apkUrl, notes)
    }

    /** 解析 version.json 的固定字段 */
    private fun parseVersionJson(body: String): Release? = try {
        val obj = JSONObject(body)
        val version = obj.optString("version").trim()
            .removePrefix("v").removePrefix("V").trim()
        val apkUrl = obj.optString("apkUrl").trim()
        if (version.isEmpty() || apkUrl.isEmpty()) null
        else Release(version, apkUrl, obj.optString("notes"))
    } catch (_: Exception) {
        null
    }

    /** 从 GitHub API assets 数组中取出第一个 .apk 的下载直链；无则返回空串 */
    private fun findApkUrl(assets: JSONArray?): String {
        if (assets == null) return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url")
            }
        }
        return ""
    }

    companion object {
        private const val JS_CDN = "https://cdn.jsdelivr.net/gh/niu0506/player"
        private const val JS_LATEST_COMMIT = "https://data.jsdelivr.com/v1/package/gh/niu0506/player@main"
        private const val GITHUB_API_LATEST = "https://api.github.com/repos/niu0506/player/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

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