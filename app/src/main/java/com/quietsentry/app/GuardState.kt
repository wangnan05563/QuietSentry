package com.quietsentry.app

import android.content.Context

/**
 * 值守持久化标记（PRD FR-13）。
 *
 * 为什么要落盘而不是只看内存：START_STICKY 重建发生在全新的服务实例里，
 * 开机广播更是另一个进程，只有持久化标记才能区分“系统杀进程/重启”和“用户主动停止”。
 * 服务与开机广播都读这里，避免两份 prefs 键名各自演化。
 */
object GuardState {

    private const val PREFS = "guard_state"
    private const val KEY_ACTIVE = "guard_active"
    private const val KEY_CRASH_COUNT = "abnormal_start_count"
    private const val KEY_PAUSED = "paused_by_remote"

    /** 连续 3 次异常启动（PRD FR-13 异常）就建议用户改用常电供电并降画质 */
    const val CRASH_ADVICE_THRESHOLD = 3

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    /**
     * 远程暂停（PRD FR-14）：必须落盘，因为暂停会同时停掉采集服务，
     * 内存标记随服务销毁即丢，观看端就再也看不到"已暂停"这个状态。
     */
    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
        // 正常停止会清零异常计数：计数表达的是“连续”被杀，而不是历史累计
        if (!active) setCrashCount(context, 0)
    }

    fun crashCount(context: Context): Int = prefs(context).getInt(KEY_CRASH_COUNT, 0)

    fun setCrashCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_CRASH_COUNT, count).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 进程内的值守运行态（心跳与降载标记）。
 *
 * 供 DeviceServer 读取：观看端要能区分“进程还在但采集已降载/卡住”和“设备直接离线”。
 * 只放内存是有意的——进程一旦被杀，心跳自然归零，这正是“被杀检测”需要的信号，
 * 落盘反而会让一份陈旧心跳骗过观看端。
 */
object GuardRuntime {

    /** 每 30 秒由 CaptureService 刷新；0 表示本次进程还没有值守心跳 */
    @Volatile
    var heartbeatAt = 0L
        private set

    @Volatile
    var throttled = false

    @Volatile
    var stoppedReason = PowerGuard.Reason.NONE

    fun beat(now: Long = System.currentTimeMillis()) {
        heartbeatAt = now
    }

    fun reset() {
        heartbeatAt = 0L
        throttled = false
        stoppedReason = PowerGuard.Reason.NONE
    }
}

