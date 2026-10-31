package com.quietsentry.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地审计日志（PRD FR-14："安全设置变更记录在审计日志"）。
 *
 * 为什么落盘成 JSONL 而不是只打 Logcat：用户需要能自己回看"谁在什么时候看过、暂停过、
 * 被撤销过授权"，Logcat 会被系统回收，也没有任何界面入口。
 * 只记录行为与角色，不记录令牌、PIN、公钥等敏感值——审计日志本身不能成为新的泄露面。
 */
object AuditLog {

    /** 事件类型用常量而不是枚举：落盘后即使哪天调整枚举顺序，历史行仍可读 */
    const val PAIR = "pair"
    const val REVOKE = "revoke"
    const val LEAVE = "leave"
    const val REMOTE_PAUSE = "remote_pause"
    const val PIN_SET = "pin_set"
    const val PIN_CLEAR = "pin_clear"
    const val PIN_FAIL = "pin_fail"
    const val MASK_CHANGE = "mask_change"
    const val EXPORT = "export"
    const val INTEGRATION = "integration"

    data class Entry(val at: Long, val type: String, val detail: String)

    private const val FILE_NAME = "audit.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val KEEP_LINES = 300
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /** 追加与重写都在同一把锁内完成：审计写入来自服务线程、HTTP 线程与界面，交叉写会切碎 JSON 行 */
    private val lock = Object()

    fun record(context: Context, type: String, detail: String) {
        val safeDetail = detail.replace('\n', ' ').take(200)
        val line = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("type", type)
            .put("detail", safeDetail)
            .toString()
        synchronized(lock) {
            runCatching {
                val file = file(context)
                // 超过上限先裁剪再写：直接追加会让文件无限增长，且裁剪时能顺手丢掉过期行
                if (file.length() > MAX_BYTES) prune(file)
                file.appendText(line + "\n")
            }
        }
    }

    /** 最近的记录倒序返回；坏行直接跳过，一条写坏的记录不该让整个审计页打不开 */
    fun recent(context: Context, limit: Int = 50): List<Entry> {
        val file = file(context)
        if (!file.isFile) return emptyList()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return runCatching {
            file.readLines()
                .asSequence()
                .mapNotNull { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                    Entry(json.optLong("at"), json.optString("type"), json.optString("detail"))
                }
                .filter { it.at >= cutoff }
                .toList()
                .takeLast(limit)
                .reversed()
        }.getOrDefault(emptyList())
    }

    fun exportText(context: Context): String = buildString {
        appendLine("QuietSentry 审计日志")
        appendLine("导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        appendLine()
        recent(context, Int.MAX_VALUE).forEach { entry ->
            appendLine("${stamp(entry.at)} [${typeLabel(entry.type)}] ${entry.detail}")
        }
    }

    private fun prune(file: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = file.readLines()
            .filter { line ->
                val at = runCatching { JSONObject(line).optLong("at") }.getOrDefault(0L)
                at >= cutoff
            }
            .takeLast(KEEP_LINES)
        file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    private fun file(context: Context): File =
        File(context.filesDir, "audit").apply { mkdirs() }.let { File(it, FILE_NAME) }

    private fun stamp(at: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(at))

    /** 界面展示用；未知类型原样返回，避免新加类型时审计页出现空白行 */
    fun typeLabel(type: String): String = when (type) {
        PAIR -> "配对"
        REVOKE -> "撤销授权"
        LEAVE -> "主动离开"
        REMOTE_PAUSE -> "远程暂停"
        PIN_SET -> "设置应用锁"
        PIN_CLEAR -> "关闭应用锁"
        PIN_FAIL -> "应用锁校验失败"
        MASK_CHANGE -> "隐私遮罩变更"
        EXPORT -> "导出"
        INTEGRATION -> "第三方集成"
        else -> type
    }
}
