package com.quietsentry.app

/**
 * 设备卡片的展示状态与提示优先级（PRD FR-03）。
 *
 * 顺序固定为：告警中 > 离线 > 低电 > 过热 > 存储告警 > 值守中 > 待机。
 * 直接复用 enum 的 ordinal 做排序，不另建权重表：顺序本身就是 PRD 规定，
 * 写在同一个类型里，新增状态时才不会漏改比较逻辑。
 */
object DeviceState {

    enum class Level { ALERT, OFFLINE, LOW_BATTERY, OVERHEAT, STORAGE, GUARDING, IDLE }

    data class View(
        val level: Level,
        val battery: Int,
        val charging: Boolean,
        val watching: Int,
        val maxViewers: Int,
        val lastEventAt: Long,
        val seenAt: Long,
        /** 值守中但心跳超时：进程可能被系统冻结，画面已停但 HTTP 还能应答（PRD FR-13） */
        val heartbeatStale: Boolean = false,
        val throttled: Boolean = false,
    )

    fun evaluate(status: DeviceHttp.Status?, lastEventAt: Long, seenAt: Long, now: Long): View {
        val level = when {
            status == null -> Level.OFFLINE
            // 告警是“叠加标识”：最近 5 分钟有事件就把卡片顶到最前，其余信息仍照常展示
            now - lastEventAt in 0 until ALERT_WINDOW_MS -> Level.ALERT
            // battery 为 -1 表示服务端没读到电量；把它当成低电会让所有设备都变琥珀色
            status.battery in 1..LOW_BATTERY_PERCENT && !status.charging -> Level.LOW_BATTERY
            status.thermal >= THERMAL_SEVERE -> Level.OVERHEAT
            status.storageFreeMb in 1 until LOW_STORAGE_MB -> Level.STORAGE
            status.mode == MODE_GUARD -> Level.GUARDING
            else -> Level.IDLE
        }
        return View(
            level = level,
            battery = status?.battery ?: -1,
            charging = status?.charging ?: false,
            watching = status?.watching ?: 0,
            maxViewers = status?.maxViewers ?: DEFAULT_MAX_VIEWERS,
            lastEventAt = maxOf(lastEventAt, status?.lastEventAt ?: 0L),
            seenAt = seenAt,
            heartbeatStale = heartbeatStale(status),
            throttled = status?.throttled == true,
        )
    }

    /**
     * 只有在“声称值守”时才检查心跳：待机设备本来就不巡检，
     * 用同一个阈值判断会把所有待机设备都误标成中断。
     */
    private fun heartbeatStale(status: DeviceHttp.Status?): Boolean {
        if (status == null || status.mode != MODE_GUARD) return false
        if (status.heartbeatAt <= 0L) return true
        // 两端时钟可能不同步，只用相机端自己的 serverTime 相减
        return status.serverTime - status.heartbeatAt > HEARTBEAT_TIMEOUT_MS
    }

    private const val MODE_GUARD = "guard"
    private const val DEFAULT_MAX_VIEWERS = 5
    private const val ALERT_WINDOW_MS = 5 * 60 * 1000L
    private const val LOW_BATTERY_PERCENT = 20
    private const val THERMAL_SEVERE = 3
    private const val LOW_STORAGE_MB = 500L

    /** PRD FR-13：心跳每 30 秒一次，超过 90 秒未更新即视为疑似中断 */
    private const val HEARTBEAT_TIMEOUT_MS = 90_000L
}
