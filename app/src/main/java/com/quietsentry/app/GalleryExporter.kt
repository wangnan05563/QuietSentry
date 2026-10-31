package com.quietsentry.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 值守产物写在应用私有目录，系统相册看不到，用户会误判“没录上”。
 * 停止值守时把本次会话的分片复制进相册，复制成功后再删掉私有原件：
 * 一份 30 秒分片约 64MB，两份长期并存会让存储占用翻倍，而私有目录还有 24 小时保护期无法自动回收。
 * 复制失败的保持原样，便于排查与后续重试。
 */
object GalleryExporter {

    /**
     * 一条导出结果。source 是私有原件路径（导出成功后会被删除），uri 是之后仍可再次打开的位置。
     * 回看页必须依赖 uri 而不是 File 路径，否则停止值守后所有历史事件都会变成“文件不可用”。
     */
    data class Exported(val source: File, val uri: String)

    /** 返回成功写入相册的条目；单条失败不影响其余分片，避免一个坏片导致整场导出失败。 */
    fun export(context: Context, files: List<File>): List<Exported> = files.mapNotNull { file ->
        if (!file.isFile || file.length() == 0L) {
            null
        } else {
            runCatching { copy(context, file) }
                .onSuccess { file.delete() }
                .getOrNull()
                ?.let { Exported(file, it.toString()) }
        }
    }

    /**
     * 实时画面截图。与视频导出共用同一个相簿，用户在一个位置就能找到所有 QuietSentry 产物；
     * 截图失败返回 false 而不是抛出，调用方只需要给一句提示。
     */
    fun saveImage(context: Context, bitmap: Bitmap): Boolean = runCatching {
        val name = "quiet_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, IMAGE_MIME)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                )
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { output ->
                return bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, output)
            }
            return false
        }

        // API 28 只能写公共目录，写完再扫描，否则相册里看不到
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        ).apply { mkdirs() }
        val target = File(dir, name)
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, it) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(IMAGE_MIME), null)
        true
    }.getOrDefault(false)

    private fun copy(context: Context, file: File): Uri {
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
            return uri
        }

        // Android 9 没有分区存储，只能落公共 Movies 目录再交给媒体库扫描。
        // 扫描是异步的，这里返回 file:// 定位即可：应用本身持有读权限，回看直接按文件路径打开。
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ALBUM
        ).apply { mkdirs() }
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(MIME), null)
        return Uri.fromFile(target)
    }

    private const val MIME = "video/mp4"
    private const val IMAGE_MIME = "image/jpeg"
    private const val IMAGE_QUALITY = 90
    private const val ALBUM = "QuietSentry"
}
