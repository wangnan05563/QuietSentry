package com.quietsentry.app

/**
 * 设备卡片的展示状态与提示优先级（PRD FR-03 / PRD §7「状态与标识」）。
 *
 * 优先级由两部分组成，二者不是同一个维度：
 *  - 运行状态（level）：离线 > 低电 > 过热 > 存储告警 > 值守中 > 待机。
 *    直接复用 enum 的 ordinal 做排序，不另建权重表：顺序本身就是 PRD 规定。
 *  - 告警标识（alerting）：最近 5 分钟有事件。PRD §7 明确「告警是叠加在运行状态上的
 *    标识，不构成独立运行状态」，因此不能与运行状态互斥——一台离线或低电的设备，
 *    同样可能是「刚刚有告警」的那台，用户最需要立刻看到。
 *
 * T04741 裁决：历史实现把 ALERT 塞进 level 且让 status==null 短路告警判断，
 * 导致离线设备即使 5 分钟内有事件也丢失告警标识，与「告警中 > 离线」及「叠加标识」
 * 声明不符。这里保留在线设备 level=ALERT 的既有展示（避免回归），另用 View.alerting
 * 独立携带告警标识；列表排序按 alerting 优先，UI 在运行状态之上叠加告警角标。
 */
object DeviceState {

    /** 不含 ALERT 会让在线设备的既有文案回退，故枚举保留，但它只是「在线且告警」的便捷态 */
    enum class Level { ALERT, OFFLINE, LOW_BATTERY, OVERHEAT, STORAGE, GUARDING, IDLE }

    data class View(
        val level: Level,
        val battery: Int,
        val charging: Boolean,
        val watching: Int,
        val maxViewers: Int,
        val lastEventAt: Long,
        val seenAt: Long,
        /** 最近 5 分钟有事件：独立于运行状态的叠加标识（PRD §7），离线设备同样可置位 */
        val alerting: Boolean = false,
        /** 值守中但心跳超时：进程可能被系统冻结，画面已停但 HTTP 还能应答（PRD FR-13） */
        val heartbeatStale: Boolean = false,
        val throttled: Boolean = false,
    )

    fun evaluate(status: DeviceHttp.Status?, lastEventAt: Long, seenAt: Long, now: Long): View {
        // 先独立算告警标识：离线时 status 为 null，但本地持久化的 lastEventAt 依然可信，
        // 不能因为拉不到状态就把「刚刚发生过什么」一起丢掉
        val alerting = now - lastEventAt in 0 until ALERT_WINDOW_MS
        val level = when {
            status == null -> Level.OFFLINE
            // 在线设备仍以告警态顶到最前，保持既有卡片文案与回归断言不变
            alerting -> Level.ALERT
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
            alerting = alerting,
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
