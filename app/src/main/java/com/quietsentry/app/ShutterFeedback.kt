package com.quietsentry.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 拍照震动反馈（T04023，PRD 4.2）：黑屏页没有取景预览也没有快门动画，
 * 一次短震动是「拍到了」的唯一确认；夜间值守下默认开、可关（有人怕震动暴露）。
 */
object ShutterFeedback {

    private const val PREFS = "shutter_feedback"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, value).apply()
    }

    fun vibrate(context: Context) {
        if (!enabled(context)) return
        // API 31 起 Vibrator 归入 VibratorManager 管理，直接取旧服务拿到的是兼容壳，
        // 行为仍可用，但按平台推荐路径取默认震动器更稳
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        // 40ms 单次脉冲：够确认又不像闹铃，长时间值守不扰人
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
