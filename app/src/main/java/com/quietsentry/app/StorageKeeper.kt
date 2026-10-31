package com.quietsentry.app

import android.content.Context
import android.os.StatFs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本地存储的保留与循环覆盖（PRD §8.3）。
 *
 * 两条规则同时成立：先按 7 天过期清理，再把总量压到「当前可用空间的 50%」以内。
 * 两种删除都跳过最近 24 小时的文件——正在录制的分片 lastModified 一直在更新，
 * 一旦被清理掉，录像会在用户毫无察觉的情况下中断。
 */
object StorageKeeper {

    private const val KEEP_DAYS = 7L
    private const val PROTECT_HOURS = 24L
    private const val QUOTA_RATIO = 0.5

    fun enforce(context: Context) {
        val roots = listOf(
            File(context.filesDir, "clips"),
            File(context.filesDir, "private_gallery"),
            // 缩略图在 events/thumbs 下，配额与过期清理只扫一层，必须直接指向叶子目录
            File(context.filesDir, "events/thumbs")
        )
        val now = System.currentTimeMillis()
        val expireBefore = now - TimeUnit.DAYS.toMillis(KEEP_DAYS)
        val protectAfter = now - TimeUnit.HOURS.toMillis(PROTECT_HOURS)

        // 先做过期清理：按时间判断的结果与配额无关，先删掉能让后面的用量计算更准
        for (dir in roots) {
            dir.listFiles()?.forEach { file ->
                if (!isProtected(file, protectAfter) && file.lastModified() < expireBefore) {
                    file.delete()
                }
            }
        }

        val quota = quotaBytes(context)
        var used = usedBytes(roots)
        if (used <= quota) return

        // 覆盖顺序：最旧优先；按修改时间排序而不是文件名，因为 orphan_ 与 pic_ 前缀不同
        val candidates = roots
            .flatMap { dir -> dir.listFiles()?.filter { !isProtected(it, protectAfter) } ?: emptyList() }
            .sortedBy { it.lastModified() }

        for (file in candidates) {
            if (used <= quota) break
            val length = file.length()
            if (file.delete()) used -= length
        }
    }

    /** 最近 24 小时的文件一律不动：正在录制的分片 mtime 持续刷新，删掉会中断录像。 */
    private fun isProtected(file: File, protectAfter: Long): Boolean =
        !file.isFile || file.lastModified() >= protectAfter

    private fun quotaBytes(context: Context): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        // 配额按「当前可用空间的一半」计算：设备本身越接近写满，可占用量同步收紧，
        // 避免固定一个绝对值后把最后一点空间也吃掉
        return (stat.availableBytes * QUOTA_RATIO).toLong()
    }

    private fun usedBytes(roots: List<File>): Long =
        roots.sumOf { dir -> dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L }
}
