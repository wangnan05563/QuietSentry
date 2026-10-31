package com.quietsentry.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 重启恢复提示（PRD FR-13：重启后不自动启动相机，推送“设备已重启”并提供一键恢复）。
 *
 * Android 14 起禁止从 BOOT_COMPLETED 创建 camera 类型前台服务，
 * 所以这里只做告知：用户点通知回到应用，再由本机确认是否恢复值守。
 * 若上次是用户主动停止（标记已清），重启不需要打扰任何人。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!GuardState.isActive(context)) return
        EventNotifier(context).notifyRestart()
    }
}
