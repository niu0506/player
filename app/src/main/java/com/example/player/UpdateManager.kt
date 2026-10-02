package com.example.player

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
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
 * 应用内更新检查器。数据源按优先级：
 * 1. GitHub Releases API（数据最新，无 CDN 缓存）
 * 2. jsDelivr @main 的 version.json（大陆可达性好，可能有约 12 小时缓存）
 * 必须在 IO 线程调用 [checkLatest]（含阻塞网络请求）。
 */
class UpdateChecker {

    /** 一次 Release 检查结果：版本号 + APK 直链 + 更新说明 */
    data class Release(val version: String, val apkUrl: String, val notes: String)

    /** 取字符串字段：JSON null/缺失/非字符串一律返回空串（勿用 optString 把 null 变字面量） */
    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    /** 版本号归一化：去首尾空白并去掉 v/V 前缀（如 "v1.2.1" → "1.2.1"） */
    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /** 请求远端最新版本信息；失败/解析不到返回 null */
    fun checkLatest(): Release? {
        fetchFromGitHubApi()?.let { return it }
        return fetchVersionJson()
    }

    /** GET 请求返回响应体；非 200 或异常返回 null */
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

    private fun fetchVersionJson(): Release? =
        httpGet(JS_VERSION_JSON_URL)?.let { parseVersionJson(it) }

    /**
     * 优先来源：GitHub Releases latest 接口（兼容 version.json 字段）。
     * 解析失败（代理/强制门户返回 200 + HTML、DNS 劫持等）返回 null，
     * 让 [checkLatest] 继续走 jsDelivr 兜底，而不是把异常抛穿整条回退链。
     */
    private fun fetchFromGitHubApi(): Release? {
        val body = httpGet(GITHUB_API_LATEST, "Accept" to "application/vnd.github+json")
            ?: return null
        return try {
            val obj = JSONObject(body)
            val version = normalizeVersion(
                obj.text("version").ifBlank { obj.text("tag_name") }
            ).ifBlank { return null }
            val apkUrl = obj.text("apkUrl").ifBlank {
                findApkUrl(obj.optJSONArray("assets"))
            }.ifBlank { return null }
            val notes = obj.text("notes").ifBlank { obj.text("body") }
            Release(version, apkUrl, notes)
        } catch (_: Exception) {
            null
        }
    }

    /** 解析 version.json 的固定字段 */
    private fun parseVersionJson(body: String): Release? = try {
        val obj = JSONObject(body)
        val version = normalizeVersion(obj.text("version"))
        val apkUrl = obj.text("apkUrl").trim()
        if (version.isEmpty() || apkUrl.isEmpty()) null
        else Release(version, apkUrl, obj.text("notes"))
    } catch (_: Exception) {
        null
    }

    /** 从 GitHub assets 数组取第一个 .apk 的下载直链；无则返回空串 */
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

        /** 语义化版本比较：remote > current 返回 true（如 1.1.3 > 1.1.2） */
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
 * 更新包下载源顺序（纯函数，便于单测）：**直连在前**，第三方反代只作加速兜底。
 * 直连是 GitHub 官方域名，链路可信、少一跳中间人；反代仅在直连失败/不可达时启用。
 * 传入空串前缀表示直连；反代前缀统一补一个 '/' 再拼接。
 */
internal fun orderedDownloadUrls(apkUrl: String, accelerators: List<String>): List<String> =
    (listOf("") + accelerators).map { prefix ->
        if (prefix.isEmpty()) apkUrl else prefix.trimEnd('/') + "/" + apkUrl
    }

/**
 * 应用内更新管家：版本检查 → 确认对话框 → DownloadManager 下载
 * （目标为应用外部私有 Download 目录，全版本免存储权限；直连优先、失败自动换源/换反代）
 * → 调起安装器（含 Android 8+ 安装未知应用授权接力）。
 * 替换后的更新包清理由 manifest 静态注册的 [PackageReplacedReceiver] 负责
 * （替换时旧进程已被杀，动态注册的 receiver 收不到该广播）。
 * 需在 Activity onCreate 构造，并调用 registerReceivers/unregisterReceivers/resumePendingInstall。
 */
class UpdateManager(private val activity: AppCompatActivity) {

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        /** 更新包文件名：player-v<版本>-release.apk（清理历史版本按此模式匹配） */
        const val APK_NAME_PREFIX = "player-v"
        const val APK_NAME_SUFFIX = "-release.apk"

        /**
         * 安装成功后清理更新包（含历史版本）：删除应用私有 Download 目录下的文件；
         * Android 10+ 顺带清理旧版本遗留的 MediaStore.Downloads 0 字节占位行（闪退版本的残留）。
         */
        fun cleanupUpdateApks(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("$APK_NAME_PREFIX%$APK_NAME_SUFFIX")
                    )
                } catch (_: Exception) {
                }
            }
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?.listFiles { f -> f.name.startsWith(APK_NAME_PREFIX) && f.name.endsWith(APK_NAME_SUFFIX) }
                ?.forEach { it.delete() }
        }
    }

    private val updateChecker = UpdateChecker()
    private val downloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    private var lastDownloadId = -1L
    /** 等待「安装未知应用」授权后再安装的 APK（content URI） */
    private var pendingInstallUri: Uri? = null
    /** 下载目标（应用外部私有 Download 目录下的文件） */
    private var pendingDownloadFile: File? = null
    private var pendingDownloadVersion = ""
    /** 待尝试的下载源队列（当前源失败时换下一个） */
    private var pendingDownloadUrls: ArrayDeque<String> = ArrayDeque()
    /** 待展示的更新提示：检查完成时 Activity 不在前台就先攒下（见 [resumePendingUpdateDialog]） */
    private var pendingUpdate: UpdateChecker.Release? = null

    /** 下载完成：成功调起安装器；失败或产物不是有效 APK 时换下一个源重试 */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != lastDownloadId) return
            val success = queryDownloadStatus(id) != DownloadManager.STATUS_FAILED &&
                downloadedApkIsValid()
            if (!success) {
                val next = pendingDownloadUrls.removeFirstOrNull()
                if (next != null) {
                    enqueueDownload(next)
                    return
                }
                Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val uri = downloadedInstallUri() ?: return
            installApk(uri)
        }
    }

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

    /** APK 下载加速反代：仅作直连失败/不可达时的兜底（版本检查永远走 GitHub API） */
    private val downloadAccelerators = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    fun registerReceivers() {
        ContextCompat.registerReceiver(
            activity, downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceivers() {
        try {
            activity.unregisterReceiver(downloadCompleteReceiver)
        } catch (_: Exception) {
        }
    }

    /** onStart 中调用：用户已授权「安装未知应用」后继续被挂起的安装 */
    fun resumePendingInstall() {
        pendingInstallUri?.let { uri ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.packageManager.canRequestPackageInstalls()
            ) {
                pendingInstallUri = null
                installApk(uri)
            }
        }
    }

    /** 检查更新；失败时仅手动触发给提示 */
    fun checkForUpdate(manual: Boolean) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val release = try {
                updateChecker.checkLatest()
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                // 本机版本读不到（异常或空串）时不能当 0.0.0 去比：isNewer 会把任何远端版本都判成
                // 「有新版本」，让已经是最新的用户也看到更新弹窗；按检查失败提示更诚实
                val current = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                        ?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                when {
                    current == null || release == null -> if (manual) {
                        Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    }
                    !UpdateChecker.isNewer(release.version, current) -> if (manual) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.update_up_to_date, current),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> showUpdateDialog(release)
                }
            }
        }
    }

    /**
     * onResume 中调用：把检查期间被挂起的更新提示补弹出来。
     * 检查要联网（超时上限可达几十秒），用户很可能已切走又切回；而弹窗挂在 Activity 的
     * window token 上，加在已不可见的窗口上时回到前台往往只闪一帧，所以不在前台就先攒着。
     */
    fun resumePendingUpdateDialog() {
        val release = pendingUpdate ?: return
        pendingUpdate = null
        if (!activity.isFinishing && !activity.isDestroyed) showUpdateDialog(release)
    }

    /**
     * 展示更新对话框。两点防御：
     * 1. 只在 Activity 已 resume 时弹，否则挂起（见 [resumePendingUpdateDialog]）——避免把窗口
     *    加在不可见的 token 上，用户回来时只看到一帧就没了；
     * 2. 不响应「点外部取消」（AlertDialog 默认响应）——菜单收起等残留触摸会把刚弹出的框立刻关掉；
     *    关闭仍可用「取消」按钮或返回键。
     */
    private fun showUpdateDialog(release: UpdateChecker.Release) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingUpdate = release
            return
        }
        val notes = release.notes.trim().ifEmpty { activity.getString(R.string.update_notes_fallback) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, release.version))
            .setMessage(notes)
            .setPositiveButton(R.string.update_now) { _, _ -> downloadApk(release) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    /**
     * 下载更新包：写入应用外部私有 Download 目录（getExternalFilesDir，免存储权限，
     * 分区存储下 DownloadManager 也无法直接写公共/MediaStore 目标——setDestinationUri 仅接受 file://
     * 传 content:// 会抛 IllegalArgumentException，这就是旧版点「立即更新」闪退的原因）。
     */
    private fun downloadApk(release: UpdateChecker.Release) {
        pendingDownloadVersion = release.version
        pendingDownloadUrls = ArrayDeque(
            orderedDownloadUrls(release.apkUrl, downloadAccelerators)
        )
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir == null) {
            Toast.makeText(activity, R.string.update_storage_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        pendingDownloadFile = File(dir, apkFileName(release.version))
        enqueueDownload(pendingDownloadUrls.removeFirst())
        Toast.makeText(activity, R.string.update_download_started, Toast.LENGTH_SHORT).show()
    }

    private fun apkFileName(version: String): String =
        "$APK_NAME_PREFIX$version$APK_NAME_SUFFIX"

    /** 发起一次下载（先清同名残包；重试沿用同一目标覆写） */
    private fun enqueueDownload(url: String) {
        val file = pendingDownloadFile ?: return
        val request = DownloadManager.Request(url.toUri())
            .setTitle(activity.getString(R.string.update_download_title, pendingDownloadVersion))
            .setDescription(activity.getString(R.string.update_download_description))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        file.delete()
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, file.name)
        lastDownloadId = downloadManager.enqueue(request)
    }

    /** 下载完成后交给安装器的 APK：私有 Download 文件经 FileProvider 暴露。用启动下载时记下的目标，避免取到历史版本包。 */
    private fun downloadedInstallUri(): Uri? =
        pendingDownloadFile?.takeIf { it.exists() }?.let {
            FileProvider.getUriForFile(activity, "${activity.packageName}.file-provider", it)
        }

    /**
     * 下载产物是否为可解析的 APK。
     * 反代可能返回 200 + HTML 错误页或残包，交给安装器只会得到笼统失败提示；
     * 这里先自检，不合格就当本次源失败、换下一个源（直连也在链内）。
     */
    private fun downloadedApkIsValid(): Boolean {
        val file = pendingDownloadFile ?: return false
        if (!file.exists() || file.length() == 0L) return false
        @Suppress("DEPRECATION")
        return activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0) != null
    }

    /** 经 content URI 暴露 APK 给系统安装器；Android 8+ 需「安装未知应用」授权接力 */
    private fun installApk(apkUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !activity.packageManager.canRequestPackageInstalls()
        ) {
            // 未授权：跳设置页，授权返回后自动继续安装
            pendingInstallUri = apkUri
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${activity.packageName}".toUri()
                    )
                )
            } catch (_: Exception) {
            }
            Toast.makeText(activity, R.string.update_install_permission, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.update_installer_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 应用被新版本替换后清理下载目录里的更新包。
 * 必须在 manifest 静态注册：替换安装时系统先杀掉旧进程，随后发出的
 * ACTION_MY_PACKAGE_REPLACED 只会投递给 manifest 声明的 receiver（为其拉起新进程），
 * Activity 动态注册的 receiver 随旧进程一同消亡，永远收不到该广播。
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateManager.cleanupUpdateApks(context)
        }
    }
}