package com.quietsentry.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 值守产物写在应用私有目录，系统相册看不到，用户会误判“没录上”。
 * 停止值守时把本次会话的分片复制进相册，复制成功后再删掉私有原件：
 * 一份 30 秒分片约 64MB，两份长期并存会让存储占用翻倍，而私有目录还有 24 小时保护期无法自动回收。
 * 复制失败的保持原样，便于排查与后续重试。
 */
object GalleryExporter {

    private const val MIME = "video/mp4"
    private const val ALBUM = "QuietSentry"

    /** 返回成功写入相册的文件数；单条失败不影响其余分片，避免一个坏片导致整场导出失败。 */
    fun export(context: Context, files: List<File>): Int = files.count { file ->
        if (!file.isFile || file.length() == 0L) {
            false
        } else {
            runCatching { copy(context, file) }
                .onSuccess { file.delete() }
                .isSuccess
        }
    }

    private fun copy(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, MIME)
                // 归到独立相簿，避免和用户自己的视频混在一起
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM"
                )
            }
            val uri = context.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore 拒绝写入")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("MediaStore 输出流不可用")
        } else {
            // Android 9 没有分区存储，只能落公共 Movies 目录再交给媒体库扫描
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                ALBUM
            ).apply { mkdirs() }
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(MIME), null)
        }
    }
}
