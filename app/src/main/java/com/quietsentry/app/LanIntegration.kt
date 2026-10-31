package com.quietsentry.app

import android.content.Context
import java.security.MessageDigest

/**
 * 第三方集成（PRD FR-15：直播 / RTSP / ONVIF / 局域网 Web 控制台）的开关与凭据。
 *
 * 为什么默认关闭：自有观看端靠扫码配对拿令牌，第三方工具（浏览器、VLC、NVR）不走配对流程，
 * 只能靠“用户名 + 一次性访问令牌”。开启即把画面暴露给局域网内任何知道地址的设备，
 * 默认关闭才符合“用户没有明确同意就不扩大暴露面”。
 *
 * 为什么锁定状态放在这里而不是 DeviceServer 里：认证失败计数必须跨连接、跨请求生效。
 * 若挂在单个 Socket 的请求处理里，攻击者每次新建连接就能把计数清零，5 次锁定形同虚设。
 */
object LanIntegration {

    enum class Result { OK, INVALID, LOCKED }

    /** 固定用户名：第三方客户端（VLC/Frigate）应能长期保存一份凭据，随机用户名没有额外安全收益 */
    const val USERNAME = "admin"

    /** PRD FR-15 异常：认证失败 5 次后锁定 10 分钟 */
    const val MAX_FAILURES = 5
    const val LOCK_MS = 10 * 60 * 1000L

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        // 关闭即清空失败计数：下次开启是一段新的暴露期，不该继承上一轮的锁定
        if (!enabled) clearFailures(context)
    }

    /**
     * 一次性访问令牌：首次读取时生成并落盘。
     * 落盘是为了让用户重启 App 后不用重新配置 NVR/HA；重置入口放在设置页，随时可换新。
     */
    @Synchronized
    fun token(context: Context): String {
        val stored = prefs(context).getString(KEY_TOKEN, null)
        if (stored != null && stored.isNotEmpty()) return stored
        val created = CryptoBox.randomToken()
        prefs(context).edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    @Synchronized
    fun resetToken(context: Context): String {
        val created = CryptoBox.randomToken()
        prefs(context).edit().putString(KEY_TOKEN, created).apply()
        // 换令牌同时解锁：所有者刚主动重置，没有理由继续把管理员挡在门外
        clearFailures(context)
        return created
    }

    /** 剩余锁定时长（毫秒）；0 表示未锁定 */
    fun lockRemainingMs(context: Context, now: Long = System.currentTimeMillis()): Long {
        val until = prefs(context).getLong(KEY_LOCKED_UNTIL, 0L)
        return (until - now).coerceAtLeast(0L)
    }

    fun failureCount(context: Context): Int = prefs(context).getInt(KEY_FAILURES, 0)

    /**
     * 校验 Basic 凭据。比较用 [MessageDigest.isEqual] 而不是字符串 equals：
     * 后者在首个不同字符处提前返回，局域网内可被按响应时间逐字节猜出令牌。
     */
    @Synchronized
    fun verify(context: Context, username: String, password: String): Result {
        if (lockRemainingMs(context) > 0L) return Result.LOCKED
        val expected = token(context)
        val userOk = MessageDigest.isEqual(username.toByteArray(), USERNAME.toByteArray())
        val passOk = MessageDigest.isEqual(password.toByteArray(), expected.toByteArray())
        if (userOk && passOk) {
            clearFailures(context)
            return Result.OK
        }
        recordFailure(context)
        return if (lockRemainingMs(context) > 0L) Result.LOCKED else Result.INVALID
    }

    fun clearFailures(context: Context) {
        prefs(context).edit().remove(KEY_FAILURES).remove(KEY_LOCKED_UNTIL).apply()
    }

    private fun recordFailure(context: Context) {
        val failures = failureCount(context) + 1
        val editor = prefs(context).edit().putInt(KEY_FAILURES, failures)
        // 只累计不重置更稳妥：攻击者被锁后继续尝试也不会延长或缩短窗口，行为可预期
        if (failures >= MAX_FAILURES) {
            editor.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCK_MS)
        }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "lan_integration"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_FAILURES = "auth_failures"
    private const val KEY_LOCKED_UNTIL = "locked_until"
}
