/**
 * 应用内更新。
 *
 * 检查：请求 GitHub Releases API。
 * 下载：经系统 DownloadManager，从 GitHub Release 资产直连下载到公共「下载」目录。
 * 校验：下载产物必须通过包名 / 版本号 / SHA-256 校验（fail-closed：拿不到校验和即判定失败）。
 * 安装：本应用**不**调起系统安装器；校验通过后提示用户在系统「下载」目录中手动安装
 *      （或点系统下载完成通知自行安装）。
 */
package com.example.player

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
     * @property sha256 安装包 SHA-256（可能为 null，此时将判定下载失败）
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

    /** 缺少校验和，判定失败（fail-closed）。 */
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

/** 应用内更新编排：检查、下载与校验。下载校验通过后由用户手动安装，不代调系统安装器。 */
class UpdateManager(private val activity: AppCompatActivity) {

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val TAG = "UpdateManager"
        private const val DOWNLOAD_PREFS = "update_download"
        private const val KEY_DOWNLOAD_ID = "downloadId"
        private const val KEY_DOWNLOAD_VERSION = "downloadVersion"
        private const val KEY_DOWNLOAD_SHA256 = "downloadSha256"

        const val APK_NAME_PREFIX = "player-v"
        const val APK_NAME_SUFFIX = "-release.apk"

        /**
         * 应用替换（升级）成功后清理残留的更新包：公共「下载」目录（MediaStore）
         * 与应用私有下载目录（早期版本遗留）。
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

    /** 当前下载对应的版本号（用于校验 APK 元数据）。 */
    private var pendingDownloadVersion = ""

    /** 当前下载对应的期望 SHA-256。 */
    private var pendingDownloadSha256: String? = null

    /** 下载流程阶段：同时防止重复入队与重复处理完成回调。 */
    private var downloadState = DownloadState.IDLE

    private enum class DownloadState { IDLE, STARTING, COMPLETING }

    /** 因界面非前台而暂存的更新提示。 */
    private var pendingUpdate: UpdateChecker.Release? = null

    /** 当前已展示的更新提示，避免重复创建导致旧弹窗被顶掉。 */
    private var shownUpdateDialog: AlertDialog? = null

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

    /** 下载结束处理：失败提示；成功则校验（OK 提示手动安装 / 缺校验和或无效均判定失败）。 */
    private suspend fun handleDownloadOutcome(id: Long, context: Context = activity) {
        when (withContext(Dispatchers.IO) { queryDownloadStatus(id) }) {
            DownloadManager.STATUS_FAILED -> failDownload(context)
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (downloadState == DownloadState.COMPLETING) return
                downloadState = DownloadState.COMPLETING
                try {
                    when (withContext(Dispatchers.IO) { validateDownloadedApk(id) }) {
                        ApkValidation.OK -> {
                            clearPersistedDownload()
                            activity.toastLong(R.string.update_download_ready)
                        }
                        ApkValidation.MISSING_CHECKSUM -> abortUnverifiedDownload()
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

    /** onStart 调用：恢复上次未完成的下载并处理其结果。 */
    fun resumePendingDownload() {
        activity.lifecycleScope.launch {
            val id = downloadPrefs.getLong(KEY_DOWNLOAD_ID, -1L)
            if (id < 0) return@launch
            lastDownloadId = id
            pendingDownloadVersion = downloadPrefs.getString(KEY_DOWNLOAD_VERSION, "") ?: ""
            pendingDownloadSha256 = parseSha256Digest(downloadPrefs.getString(KEY_DOWNLOAD_SHA256, null))
            if (pendingDownloadVersion.isBlank()) {
                clearPersistedDownload()
                return@launch
            }
            handleDownloadOutcome(id)
        }
    }

    /** 检查更新；manual 为 true 时会 Toast 提示「已最新 / 检查失败」。 */
    fun checkForUpdate(manual: Boolean) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val release = updateChecker.checkLatest()
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                val current = try {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        activity.packageManager.getPackageInfo(
                            activity.packageName,
                            PackageManager.PackageInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        activity.packageManager.getPackageInfo(activity.packageName, 0)
                    }
                    info.versionName?.takeIf { it.isNotBlank() }
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

    /** 界面可安全弹窗：未销毁且已处于前台。 */
    private fun canShowDialog(): Boolean =
        !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** 显示更新弹窗；界面未处于前台则暂存，待 onResume 补弹。弹窗失败不应导致应用崩溃。 */
    private fun showUpdateDialog(release: UpdateChecker.Release) {
        if (!canShowDialog()) {
            pendingUpdate = release
            return
        }
        // 检查是异步返回的，弹窗可能在当前输入事件 / 界面遍历尚未结束时就被创建，
        // 从而刚显示即被系统移除（表现为「一闪即逝」）。延后到下一轮主线程消息再弹，
        // 让当前批次的事件与布局先处理完；重建后重新校验界面状态。
        activity.window.decorView.post { presentUpdateDialog(release) }
    }

    /** 在主线程下一轮消息中创建并弹出更新提示；重复调用不会叠加弹窗。 */
    private fun presentUpdateDialog(release: UpdateChecker.Release) {
        if (!canShowDialog()) {
            pendingUpdate = release
            return
        }
        // 已有弹窗在展示：不重复创建（否则旧弹窗会被后建的顶掉，视觉上像「闪一下」）
        if (shownUpdateDialog?.isShowing == true) return

        val notes = release.notes.trim().ifEmpty { activity.getString(R.string.update_notes_fallback) }
        try {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_dialog_title, release.version))
                .setMessage(notes)
                .setPositiveButton(R.string.update_now) { _, _ -> downloadApk(release) }
                .setNegativeButton(R.string.cancel, null)
                .create()
            // 不响应点外部取消，避免残留触摸立刻关掉刚弹出的提示
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnDismissListener {
                if (shownUpdateDialog === dialog) shownUpdateDialog = null
            }
            shownUpdateDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "显示更新提示失败", e)
            activity.toast(R.string.update_check_failed)
        }
    }

    /** 开始下载：记录版本 / 校验和，写入公共「下载」目录。 */
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
                enqueueDownload(release.apkUrl)
                activity.toast(R.string.update_download_started)
            } catch (e: Exception) {
                Log.w(TAG, "发起更新下载失败", e)
                failDownload()
            } finally {
                // 完成回调可能已把状态推进到 COMPLETING，此时不要回退
                if (downloadState == DownloadState.STARTING) downloadState = DownloadState.IDLE
            }
        }
    }

    private fun apkFileName(version: String): String =
        "$APK_NAME_PREFIX$version$APK_NAME_SUFFIX"

    /**
     * 入队前删除公共「下载」目录中的同名旧包，避免 DownloadManager 因目标文件已存在
     * （ERROR_FILE_ALREADY_EXISTS）判定下载失败。删除失败（非本应用所有 / 无权限）时静默忽略，
     * 与 [cleanupUpdateApks] 采用同一套 MediaStore 删除方式。
     */
    private fun deleteExistingDownloadFile(fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                activity.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(fileName)
                )
            } catch (_: Exception) {
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                ).delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 经 DownloadManager 入队一次下载到公共「下载」目录。
     * 使用 `setDestinationInExternalPublicDir`（内部为 `file://`），
     * 不可传 `content://` 目标（会抛 IllegalArgumentException）——这正是 v1.4.8 的闪退根因。
     */
    private fun enqueueDownload(url: String) {
        val fileName = apkFileName(pendingDownloadVersion)
        deleteExistingDownloadFile(fileName)
        val request = DownloadManager.Request(url.toUri())
            .setTitle(activity.getString(R.string.update_download_title, pendingDownloadVersion))
            .setDescription(activity.getString(R.string.update_download_description))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        lastDownloadId = downloadManager.enqueue(request)
        persistDownload()
    }

    private fun hasActiveDownload(): Boolean =
        lastDownloadId >= 0 &&
            queryDownloadStatus(lastDownloadId) != DownloadManager.STATUS_FAILED

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

    /**
     * 校验下载产物：
     * 1. 能被读到且可被系统解析为 APK；
     * 2. 包名一致；版本号（若已知）一致；
     * 3. SHA-256 匹配（fail-closed：缺失校验和也判定失败）。
     * 用于拦截「反代返回 200 + HTML 错误页」或残包 / 篡改包。
     *
     * 产物位于公共「下载」目录（非本应用可直读的路径），故经 DownloadManager 提供的
     * content uri 读取，并复制到缓存临时文件以读取 APK 元数据。
     */
    private fun validateDownloadedApk(id: Long): ApkValidation {
        val uri = downloadManager.getUriForDownloadedFile(id) ?: return ApkValidation.INVALID
        val temp = File(activity.cacheDir, "update-verify.apk")
        return try {
            val copied = activity.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied || temp.length() == 0L) return ApkValidation.INVALID
            @Suppress("DEPRECATION")
            val info = activity.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                ?: return ApkValidation.INVALID
            if (info.packageName != activity.packageName) return ApkValidation.INVALID
            val expectedVersion = pendingDownloadVersion
            val actualVersion = info.versionName
            if (expectedVersion.isNotBlank() &&
                (actualVersion.isNullOrBlank() || actualVersion != expectedVersion)
            ) {
                return ApkValidation.INVALID
            }
            val actualHash = sha256OfFile(temp) ?: return ApkValidation.INVALID
            when (verifyApkChecksum(pendingDownloadSha256, actualHash)) {
                ApkValidation.OK -> ApkValidation.OK
                ApkValidation.MISSING_CHECKSUM -> {
                    Log.w(TAG, "更新包未提供 SHA-256，无法验证完整性，判定下载失败")
                    ApkValidation.MISSING_CHECKSUM
                }
                ApkValidation.INVALID -> {
                    Log.w(TAG, "更新包校验和不匹配：期望 ${pendingDownloadSha256}，实际 $actualHash")
                    ApkValidation.INVALID
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取下载产物失败", e)
            ApkValidation.INVALID
        } finally {
            temp.delete()
        }
    }

    /** 缺校验和时的终止路径：清理下载状态并提示手动下载。 */
    private fun abortUnverifiedDownload() {
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
}

/** 应用被替换（升级完成）后清理残留的更新安装包。 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateManager.cleanupUpdateApks(context)
        }
    }
}
