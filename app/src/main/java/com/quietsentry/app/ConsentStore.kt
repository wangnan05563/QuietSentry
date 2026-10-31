package com.quietsentry.app

import android.content.Context
import android.os.Build

/**
 * 合法用途确认的本地记录（FR-01）。
 *
 * 只写本机 SharedPreferences：确认行为本身就是隐私动作，若上传到服务器，
 * 反而违背产品“无账号、数据不出设备”的承诺。版本号升到 2 时全量用户会重新看到确认页。
 */
object ConsentStore {

    private const val PREF_NAME = "consent"
    private const val KEY_VERSION = "version"
    private const val KEY_ACCEPTED_AT = "accepted_at"
    private const val KEY_DEVICE_MODEL = "device_model"

    const val CURRENT_VERSION = 1

    fun isAccepted(context: Context): Boolean =
        prefs(context).getInt(KEY_VERSION, 0) >= CURRENT_VERSION

    fun accept(context: Context) {
        prefs(context).edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .putString(KEY_DEVICE_MODEL, Build.MODEL)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
