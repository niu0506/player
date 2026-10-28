/** 应用内更新：检查、下载、校验并调起系统安装器。 */
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

/** 从 GitHub Releases 检查最新版本。 */
object UpdateChecker {

    data class Release(
        val version: String,
        val apkUrl: String,
        val notes: String,
        val sha256: String? = null,
    )

    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    fun checkLatest(): Release? {
        val body = fetchLatestReleaseJson() ?: return null
        return parseRelease(body)
    }

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

    private fun parseRelease(body: String): Release? {
        return try {
            val obj = JSONObject(body)
            val version = normalizeVersion(obj.text("tag_name")).ifBlank { return null }
            val asset = findApkAsset(obj.optJSONArray("assets"))
            val apkUrl = asset?.text("browser_download_url").orEmpty().trim().ifBlank { return null }
            Release(
                version = version,
                apkUrl = apkUrl,
                notes = filterReleaseNotes(obj.text("body")),
                sha256 = parseSha256Digest(asset?.text("digest")),
            )
        } catch (_: Exception) {
            null
        }
    }

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

/** 解析 SHA-256（支持裸 hex 或 `sha256:` 前缀）。 */
internal fun parseSha256Digest(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val hex = trimmed.substringAfterLast(':').trim().lowercase()
    return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

/**
 * 过滤 CI 写入 release body 的校验和行（旧版本发布记录里仍有）。
 * 兼容 `SHA-256: <hex>`、`- SHA256：<hex>` 与单独的 64 位 hex 行。
 */
internal fun filterReleaseNotes(raw: String): String {
    val checksumLine = Regex("^[`*_\\-\\s]*sha-?256[`*_\\s]*[:：].*$", RegexOption.IGNORE_CASE)
    val bareHashLine = Regex("^[`*_\\-\\s]*[0-9a-fA-F]{64}[`*_\\s]*$")
    return raw.lineSequence()
        .filterNot { line ->
            val trimmed = line.trim()
            checksumLine.matches(trimmed) || bareHashLine.matches(trimmed)
        }
        .joinToString("\n")
        .trim()
}

internal fun toHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return sb.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"

private inline fun ignoreFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
    }
}

internal enum class ApkValidation {
    OK,

    /** 缺少校验和（按失败处理）。 */
    MISSING_CHECKSUM,

    INVALID,
}

internal fun verifyApkChecksum(expected: String?, actual: String): ApkValidation {
    if (expected.isNullOrEmpty()) return ApkValidation.MISSING_CHECKSUM
    return if (actual.equals(expected, ignoreCase = true)) {
        ApkValidation.OK
    } else {
        ApkValidation.INVALID
    }
}

internal fun isDownloadedVersionInstalled(downloaded: String, installed: String?): Boolean =
    installed != null && !UpdateChecker.isNewer(downloaded, installed)

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

        private val APK_NAME_UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

        private const val VERIFIED_APK_DIR = "updates"

        /** 与 AndroidManifest 中 FileProvider 的 authorities 保持一致。 */
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

        /** 应用专属外部目录：DownloadManager 可直接写入，校验与安装都用这一份。 */
        private fun updateDir(context: Context): File? = context.getExternalFilesDir(VERIFIED_APK_DIR)

        fun cleanupUpdateApks(context: Context) {
            // 兼容旧版本：可能曾在公共「下载」目录留下安装包
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ignoreFailure {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("$APK_NAME_PREFIX%$APK_NAME_SUFFIX")
                    )
                }
            } else {
                ignoreFailure {
                    @Suppress("DEPRECATION")
                    val downloads =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloads.listFiles()
                        ?.filter {
                            it.name.startsWith(APK_NAME_PREFIX) && it.name.endsWith(APK_NAME_SUFFIX)
                        }
                        ?.forEach { it.delete() }
                }
            }
            clearUpdateApkDirs(context)
        }

        /** 清理下载目录；旧版本校验后的副本在内部 filesDir，一并删除。 */
        private fun clearUpdateApkDirs(context: Context) {
            File(context.filesDir, VERIFIED_APK_DIR).listFiles()?.forEach { it.delete() }
            updateDir(context)?.listFiles()?.forEach { it.delete() }
        }
    }

    private val downloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private val downloadPrefs by lazy {
        activity.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
    }

    /** 待处理下载；prefs 是唯一持久化来源。 */
    private data class PendingDownload(
        val id: Long,
        val version: String,
        val sha256: String?,
    )

    private var verifiedApkFile: File? = null

    private var downloadBusy = false

    private var installPermissionDialog: AlertDialog? = null

    private var shownUpdateDialog: AlertDialog? = null

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

    /** 查询下载状态；失败按 STATUS_FAILED 处理。 */
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
        // DOWNLOAD_COMPLETE 由其他应用发出，必须用 RECEIVER_EXPORTED
        ContextCompat.registerReceiver(
            activity, downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun unregisterReceivers() {
        ignoreFailure { activity.unregisterReceiver(downloadCompleteReceiver) }
    }

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

    /** 检查更新；manual 时提示结果。 */
    fun checkForUpdate(manual: Boolean) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val release = UpdateChecker.checkLatest()
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                val current = installedVersionName()
                when {
                    current == null || release == null -> if (manual) {
                        activity.snack(R.string.update_check_failed)
                    }
                    !UpdateChecker.isNewer(release.version, current) -> if (manual) {
                        activity.snack(R.string.update_up_to_date, current)
                    }
                    else -> showUpdateWhenResumed(release)
                }
            }
        }
    }

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

    private fun showUpdateWhenResumed(release: UpdateChecker.Release) =
        whenResumed { presentUpdateDialog(release) }

    /** 界面回到 RESUMED 后再执行，避免在后台弹窗。 */
    private fun whenResumed(block: () -> Unit) {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed { block() }
        }
    }

    private fun presentUpdateDialog(release: UpdateChecker.Release) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (shownUpdateDialog?.isShowing == true) return

        val notes = release.notes.trim().ifEmpty { activity.getString(R.string.update_notes_fallback) }
        try {
            val dialog = MaterialAlertDialogBuilder(activity)
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
            activity.snack(R.string.update_check_failed)
        }
    }

    private fun downloadApk(release: UpdateChecker.Release) {
        if (downloadBusy) {
            activity.snack(R.string.update_download_in_progress)
            return
        }
        downloadBusy = true
        activity.lifecycleScope.launch {
            try {
                if (withContext(Dispatchers.IO) { hasActiveDownload() }) {
                    activity.snack(R.string.update_download_in_progress)
                    return@launch
                }
                enqueueDownload(release.apkUrl, release.version, release.sha256)
                activity.snack(R.string.update_download_started)
            } catch (e: Exception) {
                Log.w(TAG, "发起更新下载失败", e)
                failDownload()
            } finally {
                downloadBusy = false
            }
        }
    }

    private fun apkFileName(version: String): String =
        "$APK_NAME_PREFIX${version.replace(APK_NAME_UNSAFE_CHARS, "_")}$APK_NAME_SUFFIX"

    /**
     * 入队下载到应用专属外部目录，下载产物即最终校验位置，不再复制第二份。
     * DownloadManager 只接受 file://，这里用 setDestinationInExternalFilesDir 满足该限制。
     */
    private fun enqueueDownload(url: String, version: String, sha256: String?) {
        val fileName = apkFileName(version)
        // 丢弃上次校验的副本，避免指向旧版本
        verifiedApkFile = null
        clearUpdateApkDirs(activity)
        val request = DownloadManager.Request(url.toUri())
            .setTitle(activity.getString(R.string.update_download_title, version))
            .setDescription(activity.getString(R.string.update_download_description))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, VERIFIED_APK_DIR, fileName)
        val id = downloadManager.enqueue(request)
        persistDownload(PendingDownload(id, version, sha256))
    }

    private fun hasActiveDownload(): Boolean {
        val pending = readPendingDownload() ?: return false
        return queryDownloadStatus(pending.id) != DownloadManager.STATUS_FAILED
    }

    private fun readPendingDownload(): PendingDownload? {
        val id = downloadPrefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return null
        return PendingDownload(
            id = id,
            version = downloadPrefs.getString(KEY_DOWNLOAD_VERSION, "").orEmpty(),
            sha256 = parseSha256Digest(downloadPrefs.getString(KEY_DOWNLOAD_SHA256, null)),
        )
    }

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

    private fun failDownload(
        @StringRes messageRes: Int = R.string.update_download_failed,
        long: Boolean = false,
    ) {
        if (long) activity.snackLong(messageRes) else activity.snack(messageRes)
        clearPersistedDownload()
    }

    /** 校验下载产物（包名 / 版本 / SHA-256）；不再复制，直接校验最终位置的文件。 */
    private fun validateDownloadedApk(pending: PendingDownload): ApkValidation {
        verifiedApkFile = null
        val target = verifiedApkTarget(pending.version) ?: return ApkValidation.INVALID
        return try {
            if (!target.exists() || target.length() == 0L) return ApkValidation.INVALID
            val digest = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
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
            // 校验通过才保留 target；失败路径删掉残留文件
            if (verifiedApkFile !== target) target.delete()
        }
    }

    private fun verifiedApkTarget(version: String): File? =
        updateDir(activity)?.let { File(it, apkFileName(version)) }

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
            activity.snackLong(R.string.update_install_failed)
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
            activity.snackLong(R.string.update_install_failed)
        }
    }

    private fun requestInstallPermission() =
        whenResumed { showInstallPermissionDialog() }

    private fun showInstallPermissionDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (installPermissionDialog?.isShowing == true) return
        try {
            val dialog = MaterialAlertDialogBuilder(activity)
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
            activity.snackLong(R.string.update_install_permission_message)
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
            activity.snackLong(R.string.update_install_failed)
        }
    }

}

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateManager.cleanupUpdateApks(context)
        }
    }
}
