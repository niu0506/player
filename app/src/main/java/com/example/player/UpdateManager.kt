/**
 * 应用内更新。
 *
 * 检查：优先请求 GitHub Releases API，失败时回退 jsDelivr 上的 version.json。
 * 下载：经系统 DownloadManager，GitHub 直连优先、CDN 反代兜底，失败自动换源。
 * 校验：安装前必须通过包名 / 版本号 / SHA-256 校验（fail-closed：拿不到校验和即拒绝安装）。
 */
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

/** 版本检查器：从 GitHub API 或 jsDelivr 回退源解析最新 Release 信息。 */
class UpdateChecker {

    /**
     * 最新版本信息。
     * @property version 版本号（已去掉 v 前缀）
     * @property apkUrl 安装包下载地址
     * @property notes 更新说明
     * @property sha256 安装包 SHA-256（可能为 null，此时将拒绝自动安装）
     */
    data class Release(
        val version: String,
        val apkUrl: String,
        val notes: String,
        val sha256: String? = null,
    )

    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /** 检查最新版本：GitHub API 优先，解析失败回退 jsDelivr version.json。 */
    fun checkLatest(): Release? {
        parseRelease(GITHUB_API_LATEST, "Accept" to "application/vnd.github+json")?.let { return it }

        return parseRelease(JS_VERSION_JSON_URL)
    }

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

    /** 解析 JSON：兼容 GitHub Release 与 version.json 两种字段命名。 */
    private fun parseRelease(url: String, vararg headers: Pair<String, String>): Release? {
        val body = httpGet(url, *headers) ?: return null
        return try {
            val obj = JSONObject(body)
            val version = normalizeVersion(
                obj.text("version").ifBlank { obj.text("tag_name") }
            ).ifBlank { return null }
            val asset = findApkAsset(obj.optJSONArray("assets"))
            val apkUrl = obj.text("apkUrl").ifBlank {
                asset?.text("browser_download_url").orEmpty()
            }.trim().ifBlank { return null }
            val notes = obj.text("notes").ifBlank { obj.text("body") }
            // 校验和来源优先级：version.json 的 sha256 / apkSha256 → Release 资产 digest
            val sha256 = parseSha256Digest(obj.text("sha256"))
                ?: parseSha256Digest(obj.text("apkSha256"))
                ?: parseSha256Digest(asset?.text("digest"))
            Release(version, apkUrl, notes, sha256)
        } catch (_: Exception) {
            null
        }
    }

    /** 在 Release 资产列表中查找 .apk 文件。 */
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

        /** 逐段比较版本号，判断 remote 是否比 current 新（非数字段按 0 处理）。 */
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

/** 生成下载源顺序：直连优先，随后依次拼接各加速前缀兜底；非 GitHub 地址不加速。 */
internal fun orderedDownloadUrls(apkUrl: String, accelerators: List<String>): List<String> {
    if (!apkUrl.startsWith("https://github.com/")) return listOf(apkUrl)
    return (listOf("") + accelerators).map { prefix ->
        if (prefix.isEmpty()) apkUrl else prefix.trimEnd('/') + "/" + apkUrl
    }
}

/** 解析 SHA-256：支持裸 hex 或 `sha256:` 前缀，统一小写；非法返回 null。 */
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

/** 校验和核对结果。 */
internal enum class ApkChecksumVerdict {
    OK,

    /** 期望值缺失（fail-closed：应拒绝安装）。 */
    MISSING,

    MISMATCH,
}

/** 比较期望与实际校验和（忽略大小写）。 */
internal fun verifyApkChecksum(expected: String?, actual: String): ApkChecksumVerdict {
    if (expected.isNullOrEmpty()) return ApkChecksumVerdict.MISSING
    return if (actual.equals(expected, ignoreCase = true)) {
        ApkChecksumVerdict.OK
    } else {
        ApkChecksumVerdict.MISMATCH
    }
}

/** 安装包整体校验结论。 */
internal enum class ApkValidation {
    OK,

    /** 缺少校验和，拒绝安装。 */
    MISSING_CHECKSUM,

    /** 文件残缺 / 元数据不符 / 校验不匹配。 */
    INVALID,
}

/** 应用内更新编排：检查、下载（多源重试）、校验与安装。 */
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

        /** 应用替换成功后清理旧更新包（MediaStore Downloads 目录 + 应用私有下载目录）。 */
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

    private val downloadPrefs by lazy {
        activity.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
    }

    /** 等待授权后要安装的 APK uri。 */
    private var pendingInstallUri: Uri? = null

    /** 当前下载目标文件。 */
    private var pendingDownloadFile: File? = null

    /** 当前下载对应的版本号（用于校验 APK 元数据）。 */
    private var pendingDownloadVersion = ""

    /** 当前下载对应的期望 SHA-256。 */
    private var pendingDownloadSha256: String? = null

    private var downloadStartInFlight = false

    private var downloadRetryInFlight = false

    private var completionInFlight = false

    /** 剩余待尝试的下载源（顺序即优先级）。 */
    private var pendingDownloadUrls: ArrayDeque<String> = ArrayDeque()

    /** 因界面非前台而暂存的更新提示。 */
    private var pendingUpdate: UpdateChecker.Release? = null

    /** 系统下载完成广播：只处理本次发起的下载 id。 */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != lastDownloadId) return
            activity.lifecycleScope.launch {
                handleDownloadOutcome(id, context)
            }
        }
    }

    /** 查询系统下载任务状态；查询失败按 STATUS_FAILED 处理。 */
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

    /** 下载结束处理：失败换源重试；成功则校验（OK 安装 / 缺校验和拒绝 / 无效换源）。 */
    private suspend fun handleDownloadOutcome(id: Long, context: Context = activity) {
        when (withContext(Dispatchers.IO) { queryDownloadStatus(id) }) {
            DownloadManager.STATUS_FAILED -> retryDownloadOrFinish(context)
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (completionInFlight) return
                completionInFlight = true
                try {
                    when (withContext(Dispatchers.IO) { validateDownloadedApk() }) {
                        ApkValidation.OK -> {
                            val uri = downloadedInstallUri()
                            clearPersistedDownload()
                            if (uri != null) installApk(uri)
                        }
                        ApkValidation.MISSING_CHECKSUM -> abortUnverifiedInstall()
                        ApkValidation.INVALID -> retryDownloadOrFinish(context)
                    }
                } finally {
                    completionInFlight = false
                }
            }
            else -> Unit
        }
    }

    /** CDN 反代加速前缀（直连失败时兜底）。 */
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

    /** onStart 调用：恢复上次未完成的下载与待授权安装。 */
    fun resumePendingInstall() {

        activity.lifecycleScope.launch { resumePendingDownload() }
        val uri = restorePendingInstallUri() ?: return
        if (canInstallPackages()) installApk(uri)
    }

    /** 恢复待安装 APK 的 uri（文件校验通过才返回）。 */
    private fun restorePendingInstallUri(): Uri? {
        pendingInstallUri?.let { return it }
        val path = downloadPrefs.getString(KEY_PENDING_INSTALL_PATH, null) ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            clearPendingInstall()
            return null
        }
        pendingDownloadFile = file
        val uri = fileToInstallUri(file)
        if (uri == null) clearPendingInstall()
        return uri
    }

    /** 经 FileProvider 生成安装用 content:// uri（路径不在授权范围时返回 null）。 */
    private fun fileToInstallUri(file: File): Uri? = try {
        FileProvider.getUriForFile(activity, "${activity.packageName}.file-provider", file)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Android 8+ 需用户授权「安装未知应用」。 */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    private fun persistPendingInstall() {
        val path = pendingDownloadFile?.absolutePath ?: return
        downloadPrefs.edit { putString(KEY_PENDING_INSTALL_PATH, path) }
    }

    private fun clearPendingInstall() {
        pendingInstallUri = null
        downloadPrefs.edit { remove(KEY_PENDING_INSTALL_PATH) }
    }

    /** 检查更新；manual 为 true 时会 Toast 提示「已最新 / 检查失败」。 */
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

    /** onResume 调用：补弹前台期间被推迟的更新提示。 */
    fun resumePendingUpdateDialog() {
        val release = pendingUpdate ?: return
        pendingUpdate = null
        if (!activity.isFinishing && !activity.isDestroyed) showUpdateDialog(release)
    }

    /** 显示更新弹窗；界面未处于前台则暂存，待 onResume 补弹。 */
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
        // 不响应点外部取消，避免残留触摸立刻关掉刚弹出的提示
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    /** 开始下载：记录版本 / 校验和 / 源顺序，写入应用私有下载目录。 */
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

    private fun apkFileName(version: String): String =
        "$APK_NAME_PREFIX$version$APK_NAME_SUFFIX"

    /** 经 DownloadManager 入队一次下载；目标必须为副文件私有目录（不能用 content:// 目标）。 */
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

    private fun hasActiveDownload(): Boolean {
        val file = pendingDownloadFile
        return file != null && lastDownloadId >= 0 &&
            queryDownloadStatus(lastDownloadId) != DownloadManager.STATUS_FAILED
    }

    /** 持久化当前下载状态，供进程被杀后恢复。 */
    private fun persistDownload() {
        downloadPrefs.edit {
            putLong(KEY_DOWNLOAD_ID, lastDownloadId)
                .putString(KEY_DOWNLOAD_VERSION, pendingDownloadVersion)
                .putString(KEY_DOWNLOAD_SHA256, pendingDownloadSha256.orEmpty())
                .putString(KEY_DOWNLOAD_RETRY_URLS, JSONArray(pendingDownloadUrls.toList()).toString())
        }
    }

    private fun clearPersistedDownload() {
        lastDownloadId = -1L
        pendingDownloadSha256 = null
        downloadPrefs.edit {
            remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION)
                .remove(KEY_DOWNLOAD_SHA256).remove(KEY_DOWNLOAD_RETRY_URLS)}
    }

    /** 换源重试；没有更多源时提示失败并清理状态。 */
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

    /** 从持久化状态恢复未完成的下载并处理其结果。 */
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
        handleDownloadOutcome(id)
    }

    /** 读回剩余待重试的下载源。 */
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

    private fun downloadedInstallUri(): Uri? =
        pendingDownloadFile?.takeIf { it.exists() }?.let(::fileToInstallUri)

    /**
     * 校验下载产物：
     * 1. 非空且能被系统解析为 APK；
     * 2. 包名一致；版本号（若已知）一致；
     * 3. SHA-256 匹配（fail-closed：缺失校验和也拒绝）。
     * 用于拦截「反代返回 200 + HTML 错误页」或残包 / 篡改包。
     */
    private fun validateDownloadedApk(): ApkValidation {
        val file = pendingDownloadFile ?: return ApkValidation.INVALID
        if (!file.exists() || file.length() == 0L) return ApkValidation.INVALID
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return ApkValidation.INVALID
        if (info.packageName != activity.packageName) return ApkValidation.INVALID
        val expectedVersion = pendingDownloadVersion
        val actualVersion = info.versionName
        if (expectedVersion.isNotBlank() &&
            (actualVersion.isNullOrBlank() || actualVersion != expectedVersion)
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

    /** 缺校验和时的终止路径：清理下载并提示手动安装。 */
    private fun abortUnverifiedInstall() {
        clearPersistedDownload()
        Toast.makeText(activity, R.string.update_unverified, Toast.LENGTH_LONG).show()
    }

    /** 计算文件 SHA-256（分块读取，避免大包占用内存）。 */
    private fun sha256OfFile(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
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

    /** 调起系统安装器；若无「安装未知应用」权限，先引导授权并记录待安装 uri。 */
    private fun installApk(apkUri: Uri) {
        if (!canInstallPackages()) {
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
            clearPendingInstall()
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.update_installer_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

/** 应用被替换（升级完成）后清理残留的更新安装包。 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateManager.cleanupUpdateApks(context)
        }
    }
}
