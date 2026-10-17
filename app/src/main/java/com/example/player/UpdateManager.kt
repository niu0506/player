/**
 * 应用内更新。
 *
 * 检查：请求 GitHub Releases API。
 * 下载：经系统 DownloadManager，从 GitHub Release 资产直连下载。
 * 校验：安装前必须通过包名 / 版本号 / SHA-256 校验（fail-closed：拿不到校验和即拒绝安装）。
 * 安装：校验通过后由用户确认，再调起系统安装器（不自动安装）。
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

/** 版本检查器：从 GitHub Releases API 解析最新 Release 信息。 */
class UpdateChecker {

    /**
     * 最新版本信息。
     * @property version 版本号（已去掉 v 前缀）
     * @property apkUrl 安装包下载地址
     * @property notes 更新说明
     * @property sha256 安装包 SHA-256（可能为 null，此时将拒绝安装）
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

    /** 检查最新版本：请求 GitHub Releases API。 */
    fun checkLatest(): Release? {
        val body = fetchLatestReleaseJson() ?: return null
        return parseRelease(body)
    }

    /** 请求 GitHub Releases API；任何网络或 HTTP 异常都返回 null。 */
    private fun fetchLatestReleaseJson(): String? {
        var conn: HttpURLConnection? = null
        return try {
            val current = URL(GITHUB_API_LATEST).openConnection() as HttpURLConnection
            conn = current
            current.connectTimeout = CONNECT_TIMEOUT_MS
            current.readTimeout = READ_TIMEOUT_MS
            current.setRequestProperty("Accept", "application/vnd.github+json")
            if (current.responseCode != 200) null
            else current.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 解析 GitHub Release JSON。 */
    private fun parseRelease(body: String): Release? {
        return try {
            val obj = JSONObject(body)
            val version = normalizeVersion(obj.text("tag_name")).ifBlank { return null }
            val asset = findApkAsset(obj.optJSONArray("assets"))
            val apkUrl = asset?.text("browser_download_url").orEmpty().trim().ifBlank { return null }
            Release(version, apkUrl, obj.text("body"), parseSha256Digest(asset?.text("digest")))
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

/** 安装包校验结论。 */
internal enum class ApkValidation {
    OK,

    /** 缺少校验和，拒绝安装（fail-closed）。 */
    MISSING_CHECKSUM,

    /** 文件残缺 / 元数据不符 / 校验不匹配。 */
    INVALID,
}

/** 比较期望与实际校验和（忽略大小写）。 */
internal fun verifyApkChecksum(expected: String?, actual: String): ApkValidation {
    if (expected.isNullOrEmpty()) return ApkValidation.MISSING_CHECKSUM
    return if (actual.equals(expected, ignoreCase = true)) {
        ApkValidation.OK
    } else {
        ApkValidation.INVALID
    }
}

/** 应用内更新编排：检查、下载、校验与安装。 */
class UpdateManager(private val activity: AppCompatActivity) {

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val TAG = "UpdateManager"
        private const val DOWNLOAD_PREFS = "update_download"
        private const val KEY_DOWNLOAD_ID = "downloadId"
        private const val KEY_DOWNLOAD_VERSION = "downloadVersion"
        private const val KEY_DOWNLOAD_SHA256 = "downloadSha256"
        private const val KEY_PENDING_INSTALL_PATH = "pendingInstallPath"

        const val APK_NAME_PREFIX = "player-v"
        const val APK_NAME_SUFFIX = "-release.apk"

        /**
         * 应用替换成功后清理旧更新包：应用私有下载目录，
         * 以及 v1.4.x 时期遗留在 MediaStore Downloads 中的包（历史兼容，后续版本可移除）。
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

    private val downloadPrefs by lazy {
        activity.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
    }

    /** 当前下载目标文件。 */
    private var pendingDownloadFile: File? = null

    /** 当前下载对应的版本号（用于校验 APK 元数据）。 */
    private var pendingDownloadVersion = ""

    /** 当前下载对应的期望 SHA-256。 */
    private var pendingDownloadSha256: String? = null

    /** 下载流程阶段：同时防止重复入队与重复处理完成回调。 */
    private var downloadState = DownloadState.IDLE

    private enum class DownloadState { IDLE, STARTING, COMPLETING }

    /** 因界面非前台而暂存的更新提示。 */
    private var pendingUpdate: UpdateChecker.Release? = null

    /** 因界面非前台而暂存的待确认安装 uri。 */
    private var pendingInstallPrompt: Uri? = null

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

    /** 下载结束处理：失败提示；成功则校验（OK 待确认安装 / 缺校验和拒绝 / 无效提示）。 */
    private suspend fun handleDownloadOutcome(id: Long, context: Context = activity) {
        when (withContext(Dispatchers.IO) { queryDownloadStatus(id) }) {
            DownloadManager.STATUS_FAILED -> failDownload(context)
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (downloadState == DownloadState.COMPLETING) return
                downloadState = DownloadState.COMPLETING
                try {
                    when (withContext(Dispatchers.IO) { validateDownloadedApk() }) {
                        ApkValidation.OK -> {
                            val uri = downloadedInstallUri()
                            clearPersistedDownload()
                            if (uri != null) {
                                persistPendingInstall()
                                promptInstall(uri)
                            }
                        }
                        ApkValidation.MISSING_CHECKSUM -> abortUnverifiedInstall()
                        ApkValidation.INVALID -> failDownload(context)
                    }
                } finally {
                    downloadState = DownloadState.IDLE
                }
            }
            else -> Unit
        }
    }

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

    /** onStart 调用：恢复上次未完成的下载（待确认安装统一由 onResume 补弹）。 */
    fun resumePendingInstall() {
        activity.lifecycleScope.launch { resumePendingDownload() }
    }

    /** 恢复待确认安装的 APK uri（文件与路径均有效才返回）。 */
    private fun restorePendingInstallUri(): Uri? {
        val path = downloadPrefs.getString(KEY_PENDING_INSTALL_PATH, null) ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            clearPendingInstall()
            return null
        }
        pendingDownloadFile = file
        return downloadedInstallUri().also { if (it == null) clearPendingInstall() }
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
        downloadPrefs.edit { remove(KEY_PENDING_INSTALL_PATH) }
    }

    /** 检查更新；manual 为 true 时会 Toast 提示「已最新 / 检查失败」。 */
    fun checkForUpdate(manual: Boolean) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val release = updateChecker.checkLatest()
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
                        activity.toast(R.string.update_check_failed)
                    }
                    !UpdateChecker.isNewer(release.version, current) -> if (manual) {
                        activity.toast(R.string.update_up_to_date, current)
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
        showUpdateDialog(release)
    }

    /** onResume 调用：补弹待确认安装（内存暂存优先，其次从持久化路径恢复）。 */
    fun resumePendingInstallPrompt() {
        val uri = pendingInstallPrompt ?: restorePendingInstallUri()
        pendingInstallPrompt = null
        if (uri != null) promptInstall(uri)
    }

    /** 界面可安全弹窗：未销毁且已处于前台。 */
    private fun canShowDialog(): Boolean =
        !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** 下载校验通过后交由用户确认，确认后才调起系统安装器（不再自动安装）。 */
    private fun promptInstall(apkUri: Uri) {
        if (!canShowDialog()) {
            pendingInstallPrompt = apkUri
            return
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_title)
            .setMessage(R.string.update_install_prompt)
            .setPositiveButton(R.string.update_install_now) { _, _ -> installApk(apkUri) }
            .setNegativeButton(R.string.cancel) { _, _ -> clearPendingInstall() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { clearPendingInstall() }
        dialog.show()
    }

    /** 显示更新弹窗；界面未处于前台则暂存，待 onResume 补弹。 */
    private fun showUpdateDialog(release: UpdateChecker.Release) {
        if (!canShowDialog()) {
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

    /** 开始下载：记录版本 / 校验和，写入应用私有下载目录。 */
    private fun downloadApk(release: UpdateChecker.Release) {
        if (downloadState != DownloadState.IDLE) {
            activity.toast(R.string.update_download_in_progress)
            return
        }
        downloadState = DownloadState.STARTING
        activity.lifecycleScope.launch {
            try {
                if (withContext(Dispatchers.IO) { hasActiveDownload() }) {
                    activity.toast(R.string.update_download_in_progress)
                    return@launch
                }
                pendingDownloadVersion = release.version
                pendingDownloadSha256 = release.sha256
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (dir == null) {
                    activity.toast(R.string.update_storage_unavailable)
                    return@launch
                }
                pendingDownloadFile = File(dir, apkFileName(release.version))
                enqueueDownload(release.apkUrl)
                activity.toast(R.string.update_download_started)
            } finally {
                // 完成回调可能已把状态推进到 COMPLETING，此时不要回退
                if (downloadState == DownloadState.STARTING) downloadState = DownloadState.IDLE
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
        }
    }

    private fun clearPersistedDownload() {
        lastDownloadId = -1L
        pendingDownloadSha256 = null
        downloadPrefs.edit {
            remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION)
                .remove(KEY_DOWNLOAD_SHA256)
        }
    }

    /** 下载失败：提示并清理状态。 */
    private fun failDownload(context: Context = activity) {
        context.toast(R.string.update_download_failed)
        clearPersistedDownload()
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

        handleDownloadOutcome(id)
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
            ApkValidation.OK -> ApkValidation.OK
            ApkValidation.MISSING_CHECKSUM -> {
                Log.w(TAG, "更新包未提供 SHA-256，无法验证完整性，拒绝安装")
                ApkValidation.MISSING_CHECKSUM
            }
            ApkValidation.INVALID -> {
                Log.w(TAG, "更新包校验和不匹配：期望 ${pendingDownloadSha256}，实际 $actualHash")
                ApkValidation.INVALID
            }
        }
    }

    /** 缺校验和时的终止路径：清理下载并提示手动安装。 */
    private fun abortUnverifiedInstall() {
        clearPersistedDownload()
        activity.toastLong(R.string.update_unverified)
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

    /** 调起系统安装器；若无「安装未知应用」权限，先引导授权，返回后再次确认安装。 */
    private fun installApk(apkUri: Uri) {
        if (!canInstallPackages()) {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${activity.packageName}".toUri()
                    )
                )
            } catch (_: Exception) {
            }
            activity.toastLong(R.string.update_install_permission)
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
            activity.toast(R.string.update_installer_failed)
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
