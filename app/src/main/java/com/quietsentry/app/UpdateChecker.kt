package com.quietsentry.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * 版本与更新入口（PRD FR-17）。
 *
 * 为什么不做自有更新服务：产品明确不建账号与云服务，引入版本清单服务器等于新增一条
 * 需要长期维护的外部依赖；应用商店本身已承担版本分发与签名校验，应用只负责把用户送到正确入口。
 */
object UpdateChecker {

    data class Info(val versionName: String, val versionCode: Long)

    fun current(context: Context): Info {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return Info(info.versionName ?: "", code)
    }

    /**
     * 优先走应用商店 deep link，失败再退回网页。
     * 返回 false 表示设备既没有商店也没有浏览器，调用方需要给出明确提示而不是静默无响应。
     */
    fun openStore(context: Context): Boolean {
        val pkg = context.packageName
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(market)
            true
        }.getOrElse {
            runCatching {
                context.startActivity(web)
                true
            }.getOrDefault(false)
        }
    }
}
