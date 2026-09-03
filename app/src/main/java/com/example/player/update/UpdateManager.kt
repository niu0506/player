package com.example.player.update

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
import java.io.File

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

    /** 取回刚下载完成的更新包文件：直接用启动下载时记下的目标文件，避免「按最新 .apk」误取历史版本包 */
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
