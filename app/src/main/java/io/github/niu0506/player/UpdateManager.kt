/**
 * 应用内更新：经 GitHub Releases 检查版本，DownloadManager 下载到公共「下载」目录，
 * 校验包名/版本/SHA-256（fail-closed），校验通过后直接调起系统安装器。
 */
package io.github.niu0506.player

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import androidx.core.content.edit

/** 版本检查器：从 GitHub Releases API 解析最新 Release 信息。 */
object UpdateChecker {

    /** 最新版本信息；sha256 缺失时下载校验会失败。 */
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

    private const val GITHUB_API_LATEST =
        "https://api.github.com/repos/niu0506/player/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** 逐段比较版本号，判断 remote 是否比 current 新（忽略 - / + 后缀，非数字段按 0 处理）。 */
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

/** 执行一段「失败即忽略」的清理动作（删除旧包 / 注销广播等收尾操作）。 */
private inline fun ignoreFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
    }
}

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

/** 已安装版本达到下载版本时视为安装成功（避免安装包被系统删除后误报失败）。 */
internal fun isDownloadedVersionInstalled(downloaded: String, installed: String?): Boolean =
    installed != null && !UpdateChecker.isNewer(downloaded, installed)

/** 应用内更新编排：检查、下载与校验。校验通过后直接调起系统安装器。 */
class UpdateManager(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val DOWNLOAD_PREFS = "update_download"
        private const val KEY_DOWNLOAD_ID = "downloadId"
        private const val KEY_DOWNLOAD_VERSION = "downloadVersion"
        private const val KEY_DOWNLOAD_SHA256 = "downloadSha256"

        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val APK_NAME_PREFIX = "player-v"
        private const val APK_NAME_SUFFIX = "-release.apk"

        /** 私有目录中的已校验安装包副本（供 FileProvider 交给系统安装器）。 */
        private const val VERIFIED_APK_DIR = "updates"

        /** 与 AndroidManifest 中 FileProvider 的 authorities 保持一致。 */
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

        /** 升级后清理公共「下载」目录与私有目录中的旧安装包。 */
        fun cleanupUpdateApks(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ignoreFailure {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("$APK_NAME_PREFIX%$APK_NAME_SUFFIX")
                    )
                }
            }
            // 1.5.10 之前安装包写在应用私有 Download 目录；1.6.0 起 applicationId
            // 已变更，当前包名不会再产生该目录文件，因此不再保留该清理分支。
            File(context.filesDir, VERIFIED_APK_DIR).listFiles()?.forEach { it.delete() }
        }
    }

    private val downloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private val downloadPrefs by lazy {
        activity.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
    }

    /** 一次待处理下载；prefs 是唯一持久化来源，避免内存字段与 prefs 漂移。 */
    private data class PendingDownload(
        val id: Long,
        val version: String,
        val sha256: String?,
    )

    /** 校验通过、可直接交给系统安装器的 APK 副本。 */
    private var verifiedApkFile: File? = null

    /** 下载流程忙标志：同时防止重复入队与重复处理完成回调。 */
    private var downloadBusy = false

    /** 「安装未知应用」授权提示，避免重复弹窗。 */
    private var installPermissionDialog: AlertDialog? = null

    /** 当前已展示的更新提示，避免重复创建导致旧弹窗被顶掉。 */
    private var shownUpdateDialog: AlertDialog? = null

    /** 系统下载完成广播：只处理本次发起的下载 id。 */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val pending = readPendingDownload() ?: return
            if (id != pending.id) return
            activity.lifecycleScope.launch {
                handleDownloadOutcome(id)
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

    /** 下载结束处理：失败提示；成功则校验并直接调起安装器（缺校验和或无效均判定失败）。 */
    private suspend fun handleDownloadOutcome(id: Long) {
        val pending = readPendingDownload()?.takeIf { it.id == id } ?: return
        when (withContext(Dispatchers.IO) { queryDownloadStatus(id) }) {
            DownloadManager.STATUS_FAILED -> failDownload()
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (downloadBusy) return
                downloadBusy = true
                try {
                    when (withContext(Dispatchers.IO) { validateDownloadedApk(pending) }) {
                        ApkValidation.OK -> installVerifiedApk()
                        ApkValidation.MISSING_CHECKSUM ->
                            failDownload(R.string.update_unverified, long = true)
                        ApkValidation.INVALID -> failDownload()
                    }
                } finally {
                    downloadBusy = false
                }
            }
            else -> Unit
        }
    }

    fun registerReceivers() {
        // DOWNLOAD_COMPLETE 由下载提供方（另一个应用）发出，必须用 RECEIVER_EXPORTED，
        // 否则收不到回调。
        ContextCompat.registerReceiver(
            activity, downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun unregisterReceivers() {
        ignoreFailure { activity.unregisterReceiver(downloadCompleteReceiver) }
    }

    /** onStart 调用：恢复上次未完成的下载并处理其结果。 */
    fun resumePendingDownload() {
        activity.lifecycleScope.launch {
            val pending = readPendingDownload() ?: return@launch
            if (pending.version.isBlank()) {
                clearPersistedDownload()
                return@launch
            }
            // 目标版本已安装：安装包可能已被系统删除，静默清理即可
            if (isDownloadedVersionInstalled(pending.version, installedVersionName())) {
                clearPersistedDownload()
                return@launch
            }
            handleDownloadOutcome(pending.id)
        }
    }

    /** 检查更新；manual 为 true 时会 Toast 提示「已最新 / 检查失败」。 */
    fun checkForUpdate(manual: Boolean) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val release = UpdateChecker.checkLatest()
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                val current = installedVersionName()
                when {
                    current == null || release == null -> if (manual) {
                        activity.toast(R.string.update_check_failed)
                    }
                    !UpdateChecker.isNewer(release.version, current) -> if (manual) {
                        activity.toast(R.string.update_up_to_date, current)
                    }
                    else -> showUpdateWhenResumed(release)
                }
            }
        }
    }

    /** 读取当前安装版本的 versionName；读取失败返回 null。 */
    private fun installedVersionName(): String? = try {
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

    /** 等界面回到 RESUMED 再创建更新提示；Activity 销毁时协程会取消。 */
    private fun showUpdateWhenResumed(release: UpdateChecker.Release) {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed { presentUpdateDialog(release) }
        }
    }

    /** 创建并弹出更新提示；已有弹窗展示时不重复创建。 */
    private fun presentUpdateDialog(release: UpdateChecker.Release) {
        if (activity.isFinishing || activity.isDestroyed) return
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
        if (downloadBusy) {
            activity.toast(R.string.update_download_in_progress)
            return
        }
        downloadBusy = true
        activity.lifecycleScope.launch {
            try {
                if (withContext(Dispatchers.IO) { hasActiveDownload() }) {
                    activity.toast(R.string.update_download_in_progress)
                    return@launch
                }
                enqueueDownload(release.apkUrl, release.version, release.sha256)
                activity.toast(R.string.update_download_started)
            } catch (e: Exception) {
                Log.w(TAG, "发起更新下载失败", e)
                failDownload()
            } finally {
                downloadBusy = false
            }
        }
    }

    private fun apkFileName(version: String): String =
        "$APK_NAME_PREFIX${version.replace(Regex("[^A-Za-z0-9._-]"), "_")}$APK_NAME_SUFFIX"

    /** 删除同名旧包，避免 DownloadManager 因文件已存在而失败；删除失败静默忽略。 */
    private fun deleteExistingDownloadFile(fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ignoreFailure {
                activity.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(fileName)
                )
            }
        } else {
            ignoreFailure {
                @Suppress("DEPRECATION")
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                ).delete()
            }
        }
    }

    /**
     * 入队下载到公共「下载」目录。
     * 必须用 setDestinationInExternalPublicDir（file://）；传 content:// 会抛异常。
     */
    private fun enqueueDownload(url: String, version: String, sha256: String?) {
        val fileName = apkFileName(version)
        deleteExistingDownloadFile(fileName)
        // 丢弃上一次校验留下的副本，避免指向旧版本
        verifiedApkFile = null
        File(activity.filesDir, VERIFIED_APK_DIR).listFiles()?.forEach { it.delete() }
        val request = DownloadManager.Request(url.toUri())
            .setTitle(activity.getString(R.string.update_download_title, version))
            .setDescription(activity.getString(R.string.update_download_description))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        val id = downloadManager.enqueue(request)
        persistDownload(PendingDownload(id, version, sha256))
    }

    private fun hasActiveDownload(): Boolean {
        val pending = readPendingDownload() ?: return false
        return queryDownloadStatus(pending.id) != DownloadManager.STATUS_FAILED
    }

    /** 读取持久化的下载状态；无记录时返回 null。 */
    private fun readPendingDownload(): PendingDownload? {
        val id = downloadPrefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return null
        return PendingDownload(
            id = id,
            version = downloadPrefs.getString(KEY_DOWNLOAD_VERSION, "").orEmpty(),
            sha256 = parseSha256Digest(downloadPrefs.getString(KEY_DOWNLOAD_SHA256, null)),
        )
    }

    /** 持久化当前下载状态，供进程被杀后恢复。 */
    private fun persistDownload(pending: PendingDownload) {
        downloadPrefs.edit {
            putLong(KEY_DOWNLOAD_ID, pending.id)
                .putString(KEY_DOWNLOAD_VERSION, pending.version)
                .putString(KEY_DOWNLOAD_SHA256, pending.sha256.orEmpty())
        }
    }

    private fun clearPersistedDownload() {
        downloadPrefs.edit {
            remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION)
                .remove(KEY_DOWNLOAD_SHA256)
        }
    }

    /** 下载失败：提示并清理状态。 */
    private fun failDownload(
        @StringRes messageRes: Int = R.string.update_download_failed,
        long: Boolean = false,
    ) {
        if (long) activity.toastLong(messageRes) else activity.toast(messageRes)
        clearPersistedDownload()
    }

    /**
     * 校验下载产物：可解析、包名/版本一致、SHA-256 匹配；缺失校验和也判定失败。
     * 下载流直接写入最终目标并同步计算哈希，避免临时文件与二次拷贝。
     */
    private fun validateDownloadedApk(pending: PendingDownload): ApkValidation {
        verifiedApkFile = null
        val uri = downloadManager.getUriForDownloadedFile(pending.id)
            ?: return ApkValidation.INVALID
        val target = verifiedApkTarget(pending.version)
        return try {
            target.parentFile?.mkdirs()
            target.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            val input = activity.contentResolver.openInputStream(uri)
                ?: return ApkValidation.INVALID
            DigestInputStream(input, digest).use { digestInput ->
                target.outputStream().use { output -> digestInput.copyTo(output) }
            }
            if (target.length() == 0L) return ApkValidation.INVALID
            @Suppress("DEPRECATION")
            val info = activity.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                ?: return ApkValidation.INVALID
            if (info.packageName != activity.packageName) return ApkValidation.INVALID
            val actualVersion = info.versionName
            if (pending.version.isNotBlank() &&
                (actualVersion.isNullOrBlank() || actualVersion != pending.version)
            ) {
                return ApkValidation.INVALID
            }
            val actualHash = toHex(digest.digest())
            val checksum = verifyApkChecksum(pending.sha256, actualHash)
            if (checksum != ApkValidation.OK) {
                if (checksum == ApkValidation.MISSING_CHECKSUM) {
                    Log.w(TAG, "更新包未提供 SHA-256，无法验证完整性，判定下载失败")
                } else {
                    Log.w(TAG, "更新包校验和不匹配：期望 ${pending.sha256}，实际 $actualHash")
                }
                return checksum
            }
            verifiedApkFile = target
            ApkValidation.OK
        } catch (e: Exception) {
            Log.w(TAG, "读取下载产物失败", e)
            ApkValidation.INVALID
        } finally {
            // 只有校验通过时 verifiedApkFile 才指向 target；失败路径删掉残留文件
            if (verifiedApkFile !== target) target.delete()
        }
    }

    /** 已校验安装包在私有目录中的落点。 */
    private fun verifiedApkTarget(version: String): File =
        File(File(activity.filesDir, VERIFIED_APK_DIR), apkFileName(version))

    /**
     * 调起系统安装器安装已校验的 APK。
     * Android 8+ 需要「安装未知应用」授权，未授权时先说明并跳转设置，返回后由
     * [resumePendingDownload] 再次校验并继续安装。
     */
    private fun installVerifiedApk() {
        if (activity.isFinishing || activity.isDestroyed) return
        val file = verifiedApkFile?.takeIf { it.exists() && it.length() > 0L }
        if (file == null) {
            failDownload()
            return
        }
        // 界面已不可见时先不拉起安装器，等下次回到前台（resumePendingDownload）再继续
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            requestInstallPermission()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(
                activity,
                activity.packageName + FILE_PROVIDER_SUFFIX,
                file
            )
        } catch (e: Exception) {
            Log.w(TAG, "生成安装包 URI 失败", e)
            activity.toastLong(R.string.update_install_failed)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            installPermissionDialog?.dismiss()
            activity.startActivity(intent)
            // 已把安装流程交给系统：清掉下载状态，避免每次回前台重复拉起安装器
            clearPersistedDownload()
        } catch (e: Exception) {
            Log.w(TAG, "打开系统安装器失败", e)
            activity.toastLong(R.string.update_install_failed)
        }
    }

    /** 等界面回到 RESUMED 再提示并引导用户授予「安装未知应用」权限（Android 8+）。 */
    private fun requestInstallPermission() {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed { showInstallPermissionDialog() }
        }
    }

    private fun showInstallPermissionDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (installPermissionDialog?.isShowing == true) return
        try {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.update_install_permission_title)
                .setMessage(R.string.update_install_permission_message)
                .setPositiveButton(R.string.update_install_permission_action) { _, _ ->
                    openInstallPermissionSettings()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            dialog.setOnDismissListener {
                if (installPermissionDialog === dialog) installPermissionDialog = null
            }
            installPermissionDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "显示安装权限提示失败", e)
            activity.toastLong(R.string.update_install_permission_message)
        }
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${activity.packageName}".toUri()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "打开安装未知应用设置失败", e)
            activity.toastLong(R.string.update_install_failed)
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
