package com.example.player.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.player.model.MediaItemData

/**
 * 本地媒体库扫描器：封装 MediaStore 的查询细节与权限判定。
 *
 * 只负责「读」：查询系统媒体库中的视频/音频、判断是否具备某类媒体的
 * 全量读取权限。播放列表的合并/去重/清理等状态编排由上层（MainActivity）负责。
 */
class MediaStoreScanner(private val context: Context) {

    /** 查询系统媒体库中的全部视频（按名称升序） */
    fun queryVideos(): List<MediaItemData> = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    /** 查询系统媒体库中的全部音频（按名称升序） */
    fun queryAudios(): List<MediaItemData> = query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    /**
     * 查询 MediaStore 获取媒体文件列表。
     * @param contentUri 视频或音频的集合 Uri
     * @return 查询到的媒体列表（可能为空）
     */
    private fun query(contentUri: Uri): List<MediaItemData> {
        val items = mutableListOf<MediaItemData>()
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION
        )
        try {
            context.contentResolver.query(
                contentUri, projection, null, null,
                "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx)
                    val duration = cursor.getLong(durIdx)
                    items.add(
                        MediaItemData(Uri.withAppendedPath(contentUri, id.toString()), name, duration)
                    )
                }
            }
        } catch (_: Exception) {
        }
        return items
    }

    /**
     * 是否具备指定媒体类型的「全量」读取权限（API 34+ 的「仅选中」授权不算全量）。
     * 用于删除对账前的保护：避免在部分授权下把「未授权但仍存在」的文件误判为已删除。
     */
    fun hasFullMediaAccess(permission: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
