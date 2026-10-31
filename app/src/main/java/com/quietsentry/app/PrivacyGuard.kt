package com.quietsentry.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 本地隐私保护（PRD FR-14）：应用锁 PIN、隐私遮罩与远程暂停的开关都收在这一处，
 * 避免同一份设置在设置页、黑屏页与服务里各写一遍 prefs 键。
 *
 * 为什么 PIN 只存加盐摘要：应用私有目录仍可能被 root/备份导出，
 * 存明文或可逆密文等于把"谁能看"直接交给拿到文件的人。
 */
object PrivacyGuard {

    /** 遮罩作用在推给观看端的实时画面上；本地录像不做逐帧重绘，这一点必须对用户说清楚 */
    enum class MaskMode(val value: Int, val labelRes: Int) {
        OFF(0, R.string.privacy_mask_off),
        TOP(1, R.string.privacy_mask_top),
        BOTTOM(2, R.string.privacy_mask_bottom),
        FULL(3, R.string.privacy_mask_full);

        companion object {
            fun of(value: Int): MaskMode = entries.firstOrNull { it.value == value } ?: OFF
        }
    }

    private const val PREFS = "privacy_guard"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_FAILS = "pin_fails"
    private const val KEY_LOCK_UNTIL = "pin_lock_until"
    private const val KEY_MASK = "mask_mode"

    /** 连续失败 5 次锁 60 秒：足以挡住拿到手机的人随手试，又不会把忘记 PIN 的用户永久关在门外 */
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 60_000L

    /** 解锁后 10 分钟内进入各私密页面不再重复输入；超时后重新校验，避免"一次解锁永久放行" */
    private const val UNLOCK_TTL_MS = 10 * 60 * 1000L

    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 6

    @Volatile
    private var unlockedAt = 0L

    fun hasPin(context: Context): Boolean =
        prefs(context).getString(KEY_HASH, null) != null

    fun setPin(context: Context, pin: String): Boolean {
        if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH || pin.any { !it.isDigit() }) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_SALT, Base64.getEncoder().encodeToString(salt))
            .putString(KEY_HASH, hash(salt, pin))
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
        unlockedAt = System.currentTimeMillis()
        AuditLog.record(context, AuditLog.PIN_SET, "应用锁已开启")
        return true
    }

    fun clearPin(context: Context) {
        prefs(context).edit()
            .remove(KEY_SALT)
            .remove(KEY_HASH)
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
        unlockedAt = 0L
        AuditLog.record(context, AuditLog.PIN_CLEAR, "应用锁已关闭")
    }

    fun lockedUntil(context: Context): Long = prefs(context).getLong(KEY_LOCK_UNTIL, 0L)

    fun verify(context: Context, pin: String): Boolean {
        if (!hasPin(context)) return true
        val now = System.currentTimeMillis()
        val lockUntil = lockedUntil(context)
        if (lockUntil > now) return false
        val salt = Base64.getDecoder().decode(prefs(context).getString(KEY_SALT, "") ?: "")
        val expected = prefs(context).getString(KEY_HASH, "").orEmpty()
        return if (hash(salt, pin) == expected) {
            prefs(context).edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
            unlockedAt = now
            true
        } else {
            val fails = prefs(context).getInt(KEY_FAILS, 0) + 1
            val editor = prefs(context).edit().putInt(KEY_FAILS, fails)
            if (fails >= MAX_FAILS) {
                editor.putLong(KEY_LOCK_UNTIL, now + LOCK_MS).putInt(KEY_FAILS, 0)
            }
            editor.apply()
            AuditLog.record(context, AuditLog.PIN_FAIL, "失败第 $fails 次")
            false
        }
    }

    /** 本次会话是否已通过校验；没有设置 PIN 时恒为 true */
    fun allow(context: Context): Boolean =
        !hasPin(context) || System.currentTimeMillis() - unlockedAt in 0 until UNLOCK_TTL_MS

    /** 各私密页入口统一走这里：验证失败就不加载内容，而不是先展示再遮住 */
    fun prompt(activity: AppCompatActivity, onResult: (Boolean) -> Unit) {
        val lockUntil = lockedUntil(activity)
        if (lockUntil > System.currentTimeMillis()) {
            val seconds = ((lockUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
            Toast.makeText(activity, activity.getString(R.string.pin_locked, seconds), Toast.LENGTH_SHORT).show()
            onResult(false)
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = activity.getString(R.string.pin_hint)
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pin_title)
            .setView(input)
            .setPositiveButton(R.string.pin_ok) { _, _ ->
                val ok = verify(activity, input.text.toString())
                if (!ok) {
                    Toast.makeText(activity, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                }
                onResult(ok)
            }
            .setNegativeButton(R.string.pin_cancel) { _, _ -> onResult(false) }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    fun maskMode(context: Context): MaskMode =
        MaskMode.of(prefs(context).getInt(KEY_MASK, MaskMode.OFF.value))

    fun setMaskMode(context: Context, mode: MaskMode) {
        prefs(context).edit().putInt(KEY_MASK, mode.value).apply()
        // 遮罩对正在推流的相机端要立即生效：等下一次值守启动才更新，用户会以为设置没保存
        LanFrameHub.setMask(regions(mode))
        AuditLog.record(context, AuditLog.MASK_CHANGE, "遮罩=${context.getString(mode.labelRes)}")
    }

    /** 回到相机端时把已保存的遮罩重新注入：进程重启后 LanFrameHub 是空的 */
    fun applyStreamMask(context: Context) {
        LanFrameHub.setMask(regions(maskMode(context)))
    }

    fun regions(mode: MaskMode): List<FloatArray>? = when (mode) {
        MaskMode.OFF -> null
        MaskMode.TOP -> listOf(floatArrayOf(0f, 0f, 1f, 0.35f))
        MaskMode.BOTTOM -> listOf(floatArrayOf(0f, 0.65f, 1f, 1f))
        MaskMode.FULL -> listOf(floatArrayOf(0f, 0f, 1f, 1f))
    }

    fun isPinFormatValid(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

    private fun hash(salt: ByteArray, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray())
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
