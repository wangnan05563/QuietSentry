package com.quietsentry.app

import android.content.Context

/**
 * 首次功能介绍的阅读标记。
 *
 * 与 ConsentStore 分开存储：合法性确认是一次性的法律动作，功能引导是可跳过、
 * 可在版本升级后重新出现的产品动作，两者的生命周期不应该绑在一起。
 */
object OnboardingStore {

    private const val PREFS = "onboarding"
    private const val KEY_SEEN = "seen"

    fun seen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEN, true).apply()
    }
}
