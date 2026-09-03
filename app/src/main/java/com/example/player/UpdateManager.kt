package com.example.player

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ==================== 更新检查器 ====================

/**
 * 应用内更新检查器。
 *
 * 数据源（按优先级依次尝试，任一成功即返回）：
 * 1. GitHub 官方 Releases API —— 数据始终最新，不经过任何 CDN 缓存
 * 2. jsDelivr @main 分支的 version.json —— 兜底，大陆可达性较好，但可能有最长约 12 小时的 CDN 缓存
 *
 * version.json 结构：{ "version": "1.2.1", "apkUrl": "<GitHub 直链>", "notes": "..." }
 *
 * 必须在 IO 线程调用 [UpdateChecker.checkLatest]（内含阻塞网络请求）。
 */
class UpdateChecker {

    /** 一次 Release 检查结果：版本号 + APK 直链 + 更新说明 */
    data class Release(val version: String, val apkUrl: String, val notes: String)

    /**
     * 从 JSONObject 取字符串：JSON null / 缺失 / 非字符串一律返回空串。
     * 避免 [JSONObject.optString] 把 JSON null 变成字面量 "null"，导致界面显示 "null"。
     */
    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    /** 请求远端最新版本信息；失败/解析不到时返回 null */
    fun checkLatest(): Release? {
        // GitHub API 为唯一权威来源（Releases 里才是真正已发布、可下载的 APK）
        fetchFromGitHubApi()?.let { return it }
        // 兜底：jsDelivr @main 的 version.json（大陆可达性好，但受 CDN 缓存影响）
        return fetchVersionJson()
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

    /** 请求 jsDelivr 上的 version.json 并解析；失败返回 null */
    private fun fetchVersionJson(): Release? =
        httpGet(JS_VERSION_JSON_URL)?.let { parseVersionJson(it) }

    /** 优先来源：GitHub Releases latest 接口 */
    private fun fetchFromGitHubApi(): Release? {
        val body = httpGet(GITHUB_API_LATEST, "Accept" to "application/vnd.github+json")
            ?: return null
        // 解析时把 version.json 的字段与 GitHub API 的字段都兼容掉
        val obj = JSONObject(body)
        val version = obj.text("version").ifBlank {
            obj.text("tag_name").removePrefix("v").removePrefix("V")
        }.trim().removePrefix("v").removePrefix("V").ifBlank { return null }
        val apkUrl = obj.text("apkUrl").ifBlank {
            findApkUrl(obj.optJSONArray("assets"))
        }.ifBlank { return null }
        val notes = obj.text("notes").ifBlank { obj.text("body") }
        return Release(version, apkUrl, notes)
    }

    /** 解析 version.json 的固定字段 */
    private fun parseVersionJson(body: String): Release? = try {
        val obj = JSONObject(body)
        val version = obj.text("version").trim()
            .removePrefix("v").removePrefix("V").trim()
        val apkUrl = obj.text("apkUrl").trim()
        if (version.isEmpty() || apkUrl.isEmpty()) null
        else Release(version, apkUrl, obj.text("notes"))
    } catch (_: Exception) {
        null
    }

    /** 从 GitHub API assets 数组中取出第一个 .apk 的下载直链；无则返回空串 */
    private fun findApkUrl(assets: JSONArray?): String {
        if (assets == null) return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.text("name").endsWith(".apk", ignoreCase = true)) {
                return asset.text("browser_download_url")
            }
        }
        return ""
    }

    companion object {
        private const val JS_CDN = "https://cdn.jsdelivr.net/gh/niu0506/player"
        private const val JS_VERSION_JSON_URL = "$JS_CDN@main/version.json"
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

// ==================== 更新管家 ====================

/**
 * 应用内更新管家：从 MainActivity 拆出的更新全流程。
 *
 * 职责：版本检查（GitHub Releases）→ 更新确认对话框 → APK 下载
 * （系统 DownloadManager，CDN 加速源在前、GitHub 直连兜底，失败自动换源）
 * → 下载完成调起安装器（含 Android 8+ 的「安装未知应用」授权接力）
 * → 应用被新版本替换后清理公共 Download 里的更新包。
 *
 * 需要在 Activity onCreate 中构造（内部注册权限回调），
 * 并在生命周期中调用 [registerReceivers]/[unregisterReceivers]/[resumePendingInstall]。
 */
class UpdateManager(private val activity: AppCompatActivity) {

    /** 更新检查器 */
    private val updateChecker = UpdateChecker()
    /** 系统下载管理器（懒加载，更新包下载用它，自带通知栏进度与重试） */
    private val downloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    /** 最近一次发起的下载 id，用于过滤广播 */
    private var lastDownloadId = -1L
    /** 等待用户授予「安装未知应用」权限后再安装的 APK 文件 */
    private var pendingInstallFile: File? = null
    /** 本次要写入的 APK 目标文件（换源重试时复用同一路径） */
    private var pendingDownloadFile: File? = null
    /** 本次下载的版本号，用于通知栏标题 */
    private var pendingDownloadVersion = ""
    /** 剩余待尝试的下载源队列（CDN 加速在前，GitHub 直连兜底） */
    private var pendingDownloadUrls: ArrayDeque<String> = ArrayDeque()

    /** 写外部存储权限（仅 Android 9 及以下需要，用于把更新包存到公共 Download） */
    private val writePermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingDownloadUrls.removeFirstOrNull()?.let { enqueueDownload(it) }
        } else {
            Toast.makeText(activity, "缺少存储权限，无法保存到下载目录", Toast.LENGTH_SHORT).show()
        }
    }

    /** 应用被新版本替换后清理公共 Download 里的更新包 */
    private val packageReplacedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) deleteInstalledUpdateApk()
        }
    }

    /** 下载完成监听：成功则调起系统安装器；失败且有备用下载源则换源重试 */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != lastDownloadId) return
            // 失败：换下一个源（CDN→…→GitHub 直连）重试
            if (queryDownloadStatus(id) == DownloadManager.STATUS_FAILED) {
                val next = pendingDownloadUrls.removeFirstOrNull()
                if (next != null) {
                    enqueueDownload(next)
                    return
                }
                Toast.makeText(context, "下载失败，请手动下载安装", Toast.LENGTH_SHORT).show()
                return
            }
            val file = downloadedApkFile() ?: return
            installApk(file)
        }
    }

    /** 查询某次下载的系统状态；查询失败按失败处理 */
    private fun queryDownloadStatus(id: Long): Int {
        val cursor = try {
            downloadManager.query(DownloadManager.Query().setFilterById(id))
        } catch (_: Exception) {
            return DownloadManager.STATUS_FAILED
        } ?: return DownloadManager.STATUS_FAILED
        return cursor.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            } else DownloadManager.STATUS_FAILED
        }
    }

    /**
     * 本次更新包可用下载源：CDN 加速代理在前，GitHub 直连兜底。
     * 仅用于下载安装包；版本检查永远走 GitHub API（一步到位、无 CDN 缓存干扰）。
     */
    private val downloadAccelerators = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
    )
    private val downloadSources: List<String> =
        downloadAccelerators.map { it.trimEnd('/') + "/" } + "" // 末尾空串 = 直连

    /** 注册更新相关的系统广播（onCreate 中调用） */
    fun registerReceivers() {
        // 监听更新包下载完成（API 33+ 需 RECEIVER_NOT_EXPORTED 标志）
        ContextCompat.registerReceiver(
            activity, downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 本应用被新版本替换后清理公共 Download 里的更新包
        ContextCompat.registerReceiver(
            activity, packageReplacedReceiver,
            IntentFilter(Intent.ACTION_MY_PACKAGE_REPLACED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** 注销广播（onDestroy 中调用） */
    fun unregisterReceivers() {
        try {
            activity.unregisterReceiver(downloadCompleteReceiver)
        } catch (_: Exception) {
        }
        try {
            activity.unregisterReceiver(packageReplacedReceiver)
        } catch (_: Exception) {
        }
    }

    /**
     * onStart 中调用：用户已在设置页授权「安装未知应用」时，
     * 继续完成被挂起的更新安装。
     */
    fun resumePendingInstall() {
        pendingInstallFile?.let { file ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.packageManager.canRequestPackageInstalls()
            ) {
                pendingInstallFile = null
                installApk(file)
            }
        }
    }

    /** 检查更新：请求 GitHub latest Release 并与本地版本比较。失败时仅手动触发给提示 */
    fun checkForUpdate(manual: Boolean) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val release = try {
                updateChecker.checkLatest()
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                val current = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
                when {
                    release == null -> if (manual) {
                        Toast.makeText(activity, "检查更新失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                    !UpdateChecker.isNewer(release.version, current) -> if (manual) {
                        Toast.makeText(activity, "已是最新版本 v$current", Toast.LENGTH_SHORT).show()
                    }
                    else -> showUpdateDialog(release)
                }
            }
        }
    }

    /** 弹出更新确认框：新版本号 + Release Notes，确认后下载 */
    private fun showUpdateDialog(release: UpdateChecker.Release) {
        val notes = release.notes.trim().ifEmpty { "优化体验并修复已知问题" }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${release.version}")
            .setMessage(notes)
            .setPositiveButton("立即更新") { _, _ -> downloadApk(release) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用系统 DownloadManager 把更新包下载到应用专属目录；CDN 加速，失败自动换源 */
    @Suppress("DEPRECATION")
    private fun downloadApk(release: UpdateChecker.Release) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        pendingDownloadFile = File(dir, "player-v${release.version}-release.apk")
        pendingDownloadVersion = release.version
        // CDN 加速在前，GitHub 直连兜底
        pendingDownloadUrls = ArrayDeque(
            downloadSources.map { prefix -> prefix + release.apkUrl }
        )
        // Android 9 及以下需 WRITE_EXTERNAL_STORAGE 才能写入公共 Download
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasWritePermission()) {
            writePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            Toast.makeText(activity, "需要存储权限以保存更新包到下载目录", Toast.LENGTH_SHORT).show()
            return
        }
        enqueueDownload(pendingDownloadUrls.removeFirst())
        Toast.makeText(activity, "开始下载，完成后自动弹出安装", Toast.LENGTH_SHORT).show()
    }

    /** 用 DownloadManager 发起一次下载（每次下载前清掉旧文件，避免目标已存在被拒） */
    private fun enqueueDownload(url: String) {
        val file = pendingDownloadFile ?: return
        // 清掉旧的同名包，避免 DownloadManager 因目标文件已存在而拒绝覆盖
        file.delete()
        val request = DownloadManager.Request(url.toUri())
            .setTitle("影音盒 v$pendingDownloadVersion")
            .setDescription("正在下载更新包")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        lastDownloadId = downloadManager.enqueue(request)
    }

    /** 取回刚下载实现的更新包文件：直接用启动下载时记下的目标文件，避免「按最新 .apk」误取历史版本包 */
    private fun downloadedApkFile(): File? =
        pendingDownloadFile?.takeIf { it.exists() }

    /** 是否已具备写入公共 Download 的权限（Android 9 及以下需 WRITE_EXTERNAL_STORAGE） */
    private fun hasWritePermission(): Boolean =
        Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    /** 安装成功后清理公共 Download 下的更新包（含历史版本），避免堆积 */
    @Suppress("DEPRECATION")
    private fun deleteInstalledUpdateApk() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.listFiles { f -> f.name.startsWith("player-v") && f.name.endsWith("-release.apk") }
            ?.forEach { it.delete() }
    }

    /** 安装更新包：FileProvider 暴露 APK 给系统安装器；Android 8+ 需「安装未知应用」授权 */
    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !activity.packageManager.canRequestPackageInstalls()
        ) {
            // 未授权：跳到设置页，用户授权返回前台后自动继续安装
            pendingInstallFile = file
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${activity.packageName}".toUri()
                    )
                )
            } catch (_: Exception) {
            }
            Toast.makeText(activity, "请允许本应用安装未知应用，返回后将自动继续", Toast.LENGTH_LONG).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.file-provider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, "无法启动安装器，请在下载通知中手动安装", Toast.LENGTH_SHORT).show()
        }
    }
}
