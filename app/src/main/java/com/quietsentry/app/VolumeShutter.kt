package com.quietsentry.app

import android.content.Context

/**
 * 黑屏页音量键快门（T04034，PRD 7）：黑屏看不到画面，握持盲拍时音量键比点屏幕稳。
 * 默认关闭——开启会吞掉音量键、改变系统按键习惯，这类代价必须由用户主动知情选择。
 */
object VolumeShutter {

    private const val PREFS = "volume_shutter"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, value).apply()
    }
}
