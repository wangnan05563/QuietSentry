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
        type == EventType.MOTION && triggers >= URGENT_TRIGGERS -> AlertLevel.URGENT
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
}
