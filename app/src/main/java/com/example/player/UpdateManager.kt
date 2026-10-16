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
import android.util.Log
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
import java.security.MessageDigest
import androidx.core.content.edit

/**
 * 更新检查器：从 GitHub Releases 或 CDN 上的 version.json 获取最新版本信息。
 */
class UpdateChecker {

    /** 一次版本发布信息：版本号、APK 下载地址、更新说明、可选的 SHA-256 校验和。 */
    data class Release(
        val version: String,
        val apkUrl: String,
        val notes: String,
        val sha256: String? = null,
    )

    /** 安全读取 JSON 字符串字段，非字符串或缺失时返回空串。 */
    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    /** 规范化版本号：去掉首尾空白及前缀 v/V。 */
    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /** 查询最新版本：优先 GitHub API，失败后回退到 CDN 的 version.json。 */
    fun checkLatest(): Release? {
        parseRelease(GITHUB_API_LATEST, "Accept" to "application/vnd.github+json")?.let { return it }

        return parseRelease(JS_VERSION_JSON_URL)
    }

    /** 发起一次 HTTP GET 请求并返回响应体，异常或非 200 时返回 null。 */
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

    /** 解析返回的 JSON 为 [Release]，字段缺失或解析失败时返回 null。 */
    private fun parseRelease(url: String, vararg headers: Pair<String, String>): Release? {
        val body = httpGet(url, *headers) ?: return null
        return try {
            val obj = JSONObject(body)
            // 版本号：兼容 version / tag_name 两种字段
            val version = normalizeVersion(
                obj.text("version").ifBlank { obj.text("tag_name") }
            ).ifBlank { return null }
            // 下载地址：兼容 apkUrl 字段或从 assets 中寻找 .apk
            val asset = findApkAsset(obj.optJSONArray("assets"))
            val apkUrl = obj.text("apkUrl").ifBlank {
                asset?.text("browser_download_url").orEmpty()
            }.trim().ifBlank { return null }
            // 更新说明：兼容 notes / body 两种字段
            val notes = obj.text("notes").ifBlank { obj.text("body") }
            // 校验和（可选）：version.json 的 sha256/apkSha256，或 GitHub 资产的 digest（"sha256:..."）
            val sha256 = parseSha256Digest(obj.text("sha256"))
                ?: parseSha256Digest(obj.text("apkSha256"))
                ?: parseSha256Digest(asset?.text("digest"))
            Release(version, apkUrl, notes, sha256)
        } catch (_: Exception) {
            null
        }
    }

    /** 从 GitHub release 的 assets 数组中找出 APK 资产对象。 */
    private fun findApkAsset(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.text("name").endsWith(".apk", ignoreCase = true)) {
                return asset
            }
        }
        return null
    }

    companion object {
        private const val JS_CDN = "https://cdn.jsdelivr.net/gh/niu0506/player"
        private const val JS_VERSION_JSON_URL = "$JS_CDN@main/version.json"
        private const val GITHUB_API_LATEST = "https://api.github.com/repos/niu0506/player/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /** 比较版本号：remote 是否比 current 更新（逐段按数字比较，忽略 - / + 后缀）。 */
        fun isNewer(remote: String, current: String): Boolean {

            fun normalized(value: String) = value.substringBefore('-').substringBefore('+')
            val r = normalized(remote).split('.').map { it.toIntOrNull() ?: 0 }
            val c = normalized(current).split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }
    }
}

/** 根据加速前缀生成候选下载地址列表：首个为直连地址，其后为各镜像加速地址。 */
internal fun orderedDownloadUrls(apkUrl: String, accelerators: List<String>): List<String> =
    (listOf("") + accelerators).map { prefix ->
        if (prefix.isEmpty()) apkUrl else prefix.trimEnd('/') + "/" + apkUrl
    }

/**
 * 解析 SHA-256 摘要：支持 "sha256:<hex>" 前缀（大小写不敏感）或裸 hex。
 * 仅当结果为 64 位十六进制时才返回，否则返回 null（视为未提供校验和）。
 */
internal fun parseSha256Digest(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val hex = trimmed.substringAfterLast(':').trim().lowercase()
    return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

/** 字节数组转小写十六进制字符串。 */
internal fun toHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return sb.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"

/** 依据期望校验和与实际哈希得出的完整性结论。 */
internal enum class ApkChecksumVerdict {
    /** 校验和匹配。 */
    OK,

    /** 发布元数据未提供校验和，无法验证（拒绝安装）。 */
    MISSING,

    /** 实际哈希与期望不符（完整性被破坏）。 */
    MISMATCH,
}

/**
 * 判定下载包的完整性：期望校验和缺失时返回 [ApkChecksumVerdict.MISSING]（fail-closed），
 * 不一致时返回 [ApkChecksumVerdict.MISMATCH]，一致时返回 [ApkChecksumVerdict.OK]。
 * 哈希比较忽略大小写。
 */
internal fun verifyApkChecksum(expected: String?, actual: String): ApkChecksumVerdict {
    if (expected.isNullOrEmpty()) return ApkChecksumVerdict.MISSING
    return if (actual.equals(expected, ignoreCase = true)) {
        ApkChecksumVerdict.OK
    } else {
        ApkChecksumVerdict.MISMATCH
    }
}

/** 下载包校验的最终结论：通过、缺少校验和（不可验证）、或无效（可换源重试）。 */
internal enum class ApkValidation {
    /** 校验通过，可安装。 */
    OK,

    /** 缺少可信校验和，换源无济于事，应中止安装。 */
    MISSING_CHECKSUM,

    /** 文件无效、包名/版本不符或校验和不匹配，可尝试其它镜像。 */
    INVALID,
}

/**
 * 更新管理器：负责检查更新、下载 APK、处理下载结果并触发安装。
 *
 * 下载使用系统 [DownloadManager]，支持多镜像重试、断点续传场景下的状态恢复，
 * 以及 Android 8.0+ 的“未知来源安装权限”处理。
 */
class UpdateManager(private val activity: AppCompatActivity) {

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val TAG = "UpdateManager"
        private const val DOWNLOAD_PREFS = "update_download"
        private const val KEY_DOWNLOAD_ID = "downloadId"
        private const val KEY_DOWNLOAD_VERSION = "downloadVersion"
        private const val KEY_DOWNLOAD_RETRY_URLS = "downloadRetryUrls"
        private const val KEY_DOWNLOAD_SHA256 = "downloadSha256"
        private const val KEY_PENDING_INSTALL_PATH = "pendingInstallPath"

        const val APK_NAME_PREFIX = "player-v"
        const val APK_NAME_SUFFIX = "-release.apk"

        /** 清理历史残留的更新 APK（MediaStore 下载目录 + 应用外部下载目录）。 */
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

    /** 系统下载管理器（懒加载）。 */
    private val downloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /** 最近一次入队的下载任务 id，-1 表示无。 */
    private var lastDownloadId = -1L

    /** 下载相关持久化偏好，用于进程重启后恢复下载状态。 */
    private val downloadPrefs by lazy {
        activity.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
    }

    /** 因缺少安装权限而暂存的待安装 APK uri。 */
    private var pendingInstallUri: Uri? = null

    /** 本次下载的目标文件。 */
    private var pendingDownloadFile: File? = null

    /** 本次下载的目标版本号。 */
    private var pendingDownloadVersion = ""

    /** 本次下载期望的 SHA-256 校验和（来自发布元数据，可能为空表示未提供）。 */
    private var pendingDownloadSha256: String? = null

    /** 防止重复发起下载。 */
    private var downloadStartInFlight = false

    /** 防止重复执行下载重试。 */
    private var downloadRetryInFlight = false

    /** 待尝试的下载地址队列（用于镜像依次重试）。 */
    private var pendingDownloadUrls: ArrayDeque<String> = ArrayDeque()

    /** 因界面未处于 RESUMED 而暂存的待展示更新弹窗。 */
    private var pendingUpdate: UpdateChecker.Release? = null

    /** 下载完成广播接收器：校验结果并触发安装，失败则重试镜像。 */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != lastDownloadId) return
            // 校验涉及 APK 解析与整包哈希，放到 IO 线程执行，避免阻塞主线程
            activity.lifecycleScope.launch {
                // 下载任务本身失败：换源重试
                if (withContext(Dispatchers.IO) { queryDownloadStatus(id) }
                    != DownloadManager.STATUS_SUCCESSFUL
                ) {
                    retryDownloadOrFinish(context)
                    return@launch
                }
                when (withContext(Dispatchers.IO) { validateDownloadedApk() }) {
                    ApkValidation.OK -> {
                        clearPersistedDownload()
                        val uri = downloadedInstallUri() ?: return@launch
                        installApk(uri)
                    }
                    // 缺少可信校验和：换源也拿不到校验和，直接中止并提示
                    ApkValidation.MISSING_CHECKSUM -> abortUnverifiedInstall()
                    // 无效包：可能是镜像返回错误内容，换源重试
                    ApkValidation.INVALID -> retryDownloadOrFinish(context)
                }
            }
        }
    }

    /** 查询下载任务状态，异常时按失败处理。 */
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

    /** GitHub 资源加速镜像前缀列表。 */
    private val downloadAccelerators = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /** 注册下载完成广播接收器。 */
    fun registerReceivers() {
        ContextCompat.registerReceiver(
            activity, downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** 注销下载完成广播接收器。 */
    fun unregisterReceivers() {
        try {
            activity.unregisterReceiver(downloadCompleteReceiver)
        } catch (_: Exception) {
        }
    }

    /** 恢复上次未完成的下载与安装流程（如从“未知来源”设置页返回后，或进程被杀后重启）。 */
    fun resumePendingInstall() {

        activity.lifecycleScope.launch { resumePendingDownload() }
        val uri = restorePendingInstallUri() ?: return
        // 已获安装权限则直接安装
        if (canInstallPackages()) installApk(uri)
    }

    /** 取回待安装 uri：优先内存，其次按持久化路径恢复（进程可被……杀续装）。 */
    private fun restorePendingInstallUri(): Uri? {
        pendingInstallUri?.let { return it }
        val path = downloadPrefs.getString(KEY_PENDING_INSTALL_PATH, null) ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            clearPendingInstall()
            return null
        }
        pendingDownloadFile = file
        return try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.file-provider", file)
        } catch (_: Exception) {
            clearPendingInstall()
            null
        }
    }

    /** 是否已具备安装“未知来源”应用的权限。 */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    /** 持久化“待安装”状态，供进程被杀后恢复。 */
    private fun persistPendingInstall() {
        val path = pendingDownloadFile?.absolutePath ?: return
        downloadPrefs.edit { putString(KEY_PENDING_INSTALL_PATH, path) }
    }

    /** 清除“待安装”状态。 */
    private fun clearPendingInstall() {
        pendingInstallUri = null
        downloadPrefs.edit { remove(KEY_PENDING_INSTALL_PATH) }
    }

    /** 检查更新。[manual] 为 true 时（手动触发）会通过 Toast 反馈失败/已是最新等结果。 */
    fun checkForUpdate(manual: Boolean) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val release = try {
                updateChecker.checkLatest()
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                // 读取当前已安装版本号
                val current = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                        ?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                when {
                    // 查询失败或版本未知：仅手动检查时提示
                    current == null || release == null -> if (manual) {
                        Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    }
                    // 已是最新版本
                    !UpdateChecker.isNewer(release.version, current) -> if (manual) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.update_up_to_date, current),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // 有新版本：弹出更新对话框
                    else -> showUpdateDialog(release)
                }
            }
        }
    }

    /** 恢复之前因界面未 RESUMED 而暂存的更新弹窗。 */
    fun resumePendingUpdateDialog() {
        val release = pendingUpdate ?: return
        pendingUpdate = null
        if (!activity.isFinishing && !activity.isDestroyed) showUpdateDialog(release)
    }

    /** 展示更新对话框；若界面未 RESUMED 则暂存，待恢复后再展示。 */
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

    /** 开始下载 APK：检查是否已有下载在跑，随后初始化下载文件与地址队列并入队。 */
    private fun downloadApk(release: UpdateChecker.Release) {
        if (downloadStartInFlight) {
            Toast.makeText(activity, R.string.update_download_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        downloadStartInFlight = true
        activity.lifecycleScope.launch {
            try {
                if (withContext(Dispatchers.IO) { hasActiveDownload() }) {
                    Toast.makeText(activity, R.string.update_download_in_progress, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                pendingDownloadVersion = release.version
                pendingDownloadSha256 = release.sha256
                pendingDownloadUrls = ArrayDeque(
                    orderedDownloadUrls(release.apkUrl, downloadAccelerators)
                )
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (dir == null) {
                    Toast.makeText(activity, R.string.update_storage_unavailable, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                pendingDownloadFile = File(dir, apkFileName(release.version))
                enqueueDownload(pendingDownloadUrls.removeFirst())
                Toast.makeText(activity, R.string.update_download_started, Toast.LENGTH_SHORT).show()
            } finally {
                downloadStartInFlight = false
            }
        }
    }

    /** 生成 APK 文件名，如 player-v1.2.3-release.apk。 */
    private fun apkFileName(version: String): String =
        "$APK_NAME_PREFIX$version$APK_NAME_SUFFIX"

    /** 用系统下载管理器入队一次下载，并持久化下载状态。 */
    private suspend fun enqueueDownload(url: String) {
        val file = pendingDownloadFile ?: return
        withContext(Dispatchers.IO) { file.delete() }
        val request = DownloadManager.Request(url.toUri())
            .setTitle(activity.getString(R.string.update_download_title, pendingDownloadVersion))
            .setDescription(activity.getString(R.string.update_download_description))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, file.name)
        lastDownloadId = downloadManager.enqueue(request)
        persistDownload()
    }

    /** 是否已有进行中的下载任务。 */
    private fun hasActiveDownload(): Boolean {
        val file = pendingDownloadFile
        return !(file == null || lastDownloadId < 0) && queryDownloadStatus(lastDownloadId) != DownloadManager.STATUS_FAILED
    }

    /** 持久化下载状态（id、版本、校验和、剩余重试地址）。 */
    private fun persistDownload() {
        downloadPrefs.edit {
            putLong(KEY_DOWNLOAD_ID, lastDownloadId)
                .putString(KEY_DOWNLOAD_VERSION, pendingDownloadVersion)
                .putString(KEY_DOWNLOAD_SHA256, pendingDownloadSha256.orEmpty())
                .putString(KEY_DOWNLOAD_RETRY_URLS, JSONArray(pendingDownloadUrls.toList()).toString())
        }
    }

    /** 清除持久化的下载状态。 */
    private fun clearPersistedDownload() {
        lastDownloadId = -1L
        pendingDownloadSha256 = null
        downloadPrefs.edit {
            remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION)
                .remove(KEY_DOWNLOAD_SHA256).remove(KEY_DOWNLOAD_RETRY_URLS)}
    }

    /** 下载失败时尝试下一个镜像地址；若已无可用地址则提示失败并清理状态。 */
    private suspend fun retryDownloadOrFinish(context: Context = activity) {
        if (downloadRetryInFlight) return
        downloadRetryInFlight = true
        try {
            val next = pendingDownloadUrls.removeFirstOrNull()
            if (next != null) {
                enqueueDownload(next)
            } else {
                Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                clearPersistedDownload()
            }
        } finally {
            downloadRetryInFlight = false
        }
    }

    /** 恢复上次持久化的下载：根据状态决定安装、重试或忽略。 */
    private suspend fun resumePendingDownload() {
        val id = downloadPrefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return
        lastDownloadId = id
        pendingDownloadVersion = downloadPrefs.getString(KEY_DOWNLOAD_VERSION, "") ?: ""
        pendingDownloadSha256 = parseSha256Digest(downloadPrefs.getString(KEY_DOWNLOAD_SHA256, null))
        if (pendingDownloadVersion.isBlank()) {
            clearPersistedDownload()
            return
        }
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        pendingDownloadFile = File(dir, apkFileName(pendingDownloadVersion))

        pendingDownloadUrls = readRetryUrls() ?: ArrayDeque()
        when (withContext(Dispatchers.IO) { queryDownloadStatus(id) }) {
            DownloadManager.STATUS_SUCCESSFUL -> when (
                withContext(Dispatchers.IO) { validateDownloadedApk() }
            ) {
                ApkValidation.OK -> {
                    val uri = downloadedInstallUri()
                    clearPersistedDownload()
                    if (uri != null) installApk(uri)
                }
                ApkValidation.MISSING_CHECKSUM -> abortUnverifiedInstall()
                ApkValidation.INVALID -> retryDownloadOrFinish()
            }
            DownloadManager.STATUS_FAILED -> retryDownloadOrFinish()
            else -> Unit // 仍在进行中：保持现状，等待广播回调
        }
    }

    /** 读取持久化的剩余重试地址队列。 */
    private fun readRetryUrls(): ArrayDeque<String>? {
        val raw = downloadPrefs.getString(KEY_DOWNLOAD_RETRY_URLS, null) ?: return null
        return try {
            val array = JSONArray(raw)
            ArrayDeque((0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            })
        } catch (_: Exception) {
            ArrayDeque()
        }
    }

    /** 获取已下载 APK 的可安装 uri（通过 FileProvider 暴露）。 */
    private fun downloadedInstallUri(): Uri? =
        pendingDownloadFile?.takeIf { it.exists() }?.let {
            FileProvider.getUriForFile(activity, "${activity.packageName}.file-provider", it)
        }

    /**
     * 校验下载的 APK（存在、非空、可解析，且包名/版本/校验和符合预期）。
     *
     * 采用 fail-closed：发布元数据未提供 SHA-256 时返回 [ApkValidation.MISSING_CHECKSUM]，
     * 拒绝安装无法验证完整性的包（仅凭可伪造的包名/版本号不足以信任第三方镜像的返回）。
     */
    private fun validateDownloadedApk(): ApkValidation {
        val file = pendingDownloadFile ?: return ApkValidation.INVALID
        if (!file.exists() || file.length() == 0L) return ApkValidation.INVALID
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return ApkValidation.INVALID
        // 必须是要安装的本应用，避免镜像/劫持返回其它应用
        if (info.packageName != activity.packageName) return ApkValidation.INVALID
        // 版本号需与发布元数据一致，避免装到过期或错误的包
        val expectedVersion = pendingDownloadVersion
        val actualVersion = info.versionName
        if (expectedVersion.isNotBlank() && !actualVersion.isNullOrBlank() &&
            actualVersion != expectedVersion
        ) {
            return ApkValidation.INVALID
        }
        val actualHash = sha256OfFile(file) ?: return ApkValidation.INVALID
        return when (verifyApkChecksum(pendingDownloadSha256, actualHash)) {
            ApkChecksumVerdict.OK -> ApkValidation.OK
            ApkChecksumVerdict.MISSING -> {
                Log.w(TAG, "更新包未提供 SHA-256，无法验证完整性，拒绝安装")
                ApkValidation.MISSING_CHECKSUM
            }
            ApkChecksumVerdict.MISMATCH -> {
                Log.w(TAG, "更新包校验和不匹配：期望 ${pendingDownloadSha256}，实际 $actualHash")
                ApkValidation.INVALID
            }
        }
    }

    /** 更新包缺少可信校验和：中止安装、清理状态并提示手动下载。 */
    private fun abortUnverifiedInstall() {
        clearPersistedDownload()
        Toast.makeText(activity, R.string.update_unverified, Toast.LENGTH_LONG).show()
    }

    /** 流式计算文件的 SHA-256，失败返回 null。 */
    private fun sha256OfFile(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        toHex(digest.digest())
    } catch (_: Exception) {
        null
    }

    /** 触发安装 APK；Android 8.0+ 若无“未知来源”权限则先跳转设置页申请。 */
    private fun installApk(apkUri: Uri) {
        if (!canInstallPackages()) {
            // 暂存待安装 uri 并持久化（防止进程被杀后丢失），跳转到“安装未知应用”设置页等待授权
            pendingInstallUri = apkUri
            persistPendingInstall()
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
            // 安装器已拉起：清除待安装状态，避免下次启动重复安装
            clearPendingInstall()
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.update_installer_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

/** 应用被覆盖安装后，清理历史残留的更新 APK。 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateManager.cleanupUpdateApks(context)
        }
    }
}