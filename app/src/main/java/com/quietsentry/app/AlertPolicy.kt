package com.quietsentry.app

import android.content.Context
import java.util.Calendar

/** 告警级别（PRD FR-09）：紧急（人形/连续运动）、普通（单次运动/声音）、提示（设备状态）。 */
enum class AlertLevel(val raw: String) {
    URGENT("urgent"),
    NORMAL("normal"),
    INFO("info"),
}

/**
 * 告警策略（PRD FR-09）：级别判定、免打扰时段、接收级别过滤。
 *
 * 为什么放在端侧判断而不是推送服务：免打扰与接收级别是“要不要打扰用户”的决策，
 * 必须在通知发出前生效；放到服务端会让免打扰形同虚设（推送已经到达设备）。
 */
object AlertPolicy {

    private const val PREFS = "alert_prefs"
    private const val KEY_QUIET_ENABLED = "quiet_enabled"
    private const val KEY_QUIET_START = "quiet_start_min"
    private const val KEY_QUIET_END = "quiet_end_min"
    private const val KEY_MIN_LEVEL = "min_level"

    /** 默认免打扰时段 22:00–07:00；默认关闭，避免用户误以为漏推事件，开启入口在设置页（见派生单） */
    private const val DEFAULT_QUIET_START = 22 * 60
    private const val DEFAULT_QUIET_END = 7 * 60

    /** 同源 60 秒内第 3 次触发视为“连续运动”，升级为紧急 */
    private const val URGENT_TRIGGERS = 3

    fun levelOf(type: EventType, triggers: Int): AlertLevel = when {
        // 人形与运动都属画面类事件；声音不升级紧急，避免把持续噪声当成入侵
        type != EventType.SOUND && triggers >= URGENT_TRIGGERS -> AlertLevel.URGENT
        else -> AlertLevel.NORMAL
    }

    /**
     * 当前是否处于免打扰时段。时段支持跨午夜（22:00–07:00），
     * 因此不能写成 start <= now <= end，否则跨零点配置永远不生效。
     */
    fun isQuietNow(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_QUIET_ENABLED, false)) return false
        val start = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
        val end = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END)
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (start <= end) {
            minutes in start until end
        } else {
            minutes >= start || minutes < end
        }
    }

    /**
     * 接收级别过滤：默认普通（设备状态类提示不单独打扰），紧急永远接收。
     * 成员自选级别依赖配对（M4），当前只作用于本机。
     */
    fun accepts(context: Context, level: AlertLevel): Boolean {
        if (level == AlertLevel.URGENT) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val minLevel = prefs.getString(KEY_MIN_LEVEL, AlertLevel.NORMAL.raw)
        return when (minLevel) {
            AlertLevel.URGENT.raw -> false
            AlertLevel.INFO.raw -> true
            else -> level == AlertLevel.NORMAL
        }
    }

    /**
     * 设置页要读的免打扰配置。集中在这里读写，避免界面直接拼 prefs 键名——
     * 键名一旦被第二个地方复制，改名时就会有一处静默失效。
     */
    data class Quiet(val enabled: Boolean, val startMin: Int, val endMin: Int) {
        /** 时段跨越午夜（如 22:00–07:00）需要单独提示，否则用户会以为配置反了 */
        val crossesMidnight: Boolean get() = startMin > endMin
        fun startLabel(): String = format(startMin)
        fun endLabel(): String = format(endMin)

        private fun format(minuteOfDay: Int): String =
            String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
    }

    fun quiet(context: Context): Quiet {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Quiet(
            enabled = prefs.getBoolean(KEY_QUIET_ENABLED, false),
            startMin = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START),
            endMin = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END),
        )
    }

    /** 写入即生效：EventNotifier 每次发通知前都会重新读 prefs，不需要重启值守服务 */
    fun setQuiet(context: Context, quiet: Quiet) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_QUIET_ENABLED, quiet.enabled)
            .putInt(KEY_QUIET_START, quiet.startMin)
            .putInt(KEY_QUIET_END, quiet.endMin)
            .apply()
    }

    // ---------- 检测时段计划（PRD FR-07） ----------

    private const val KEY_PLAN_ENABLED = "plan_enabled"
    private const val KEY_PLAN_START = "plan_start_min"
    private const val KEY_PLAN_END = "plan_end_min"
    private const val DEFAULT_PLAN_START = 8 * 60
    private const val DEFAULT_PLAN_END = 22 * 60

    data class DetectPlan(val enabled: Boolean, val startMin: Int, val endMin: Int) {
        /** 时段跨越午夜（如 22:00–07:00）需要单独提示，否则用户会以为配置反了 */
        val crossesMidnight: Boolean get() = startMin > endMin
        fun startLabel(): String = format(startMin)
        fun endLabel(): String = format(endMin)

        private fun format(minuteOfDay: Int): String =
            String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
    }

    fun detectPlan(context: Context): DetectPlan {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DetectPlan(
            enabled = prefs.getBoolean(KEY_PLAN_ENABLED, false),
            startMin = prefs.getInt(KEY_PLAN_START, DEFAULT_PLAN_START),
            endMin = prefs.getInt(KEY_PLAN_END, DEFAULT_PLAN_END),
        )
    }

    fun setDetectPlan(context: Context, plan: DetectPlan) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PLAN_ENABLED, plan.enabled)
            .putInt(KEY_PLAN_START, plan.startMin)
            .putInt(KEY_PLAN_END, plan.endMin)
            .apply()
    }

    /**
     * 未启用计划时视为全天允许。时段外仍检测并记录事件，只是不推送，
     * 因此这里只用于“要不要打扰”，不用于“要不要采集”。
     */
    fun isDetectionWindow(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val plan = detectPlan(context)
        if (!plan.enabled) return true
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (plan.startMin <= plan.endMin) {
            minutes in plan.startMin until plan.endMin
        } else {
            minutes >= plan.startMin || minutes < plan.endMin
        }
    }

    fun minLevel(context: Context): AlertLevel {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MIN_LEVEL, AlertLevel.NORMAL.raw)
        return AlertLevel.values().firstOrNull { it.raw == raw } ?: AlertLevel.NORMAL
    }

    fun setMinLevel(context: Context, level: AlertLevel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MIN_LEVEL, level.raw)
            .apply()
    }
}
