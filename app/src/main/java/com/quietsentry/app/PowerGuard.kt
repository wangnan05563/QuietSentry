package com.quietsentry.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * 电源与热策略（PRD FR-13）。
 *
 * 阈值判定集中在这里而不是散落在服务与界面：同一个“电量 15%”在服务里要降载、
 * 在卡片上要变琥珀色、在开启值守前要二次确认，三处各写一遍必然出现数值漂移。
 *
 * 温度只用系统热等级做代理：普通应用拿不到精确温度（需要 HardwarePropertiesManager 签名权限），
 * MODERATE/SEVERE 对应 PRD 的 45°C/50°C 两档，这里不伪造一个读不到的度数。
 */
object PowerGuard {

    data class Snapshot(val battery: Int, val charging: Boolean, val thermal: Int) {
        /** battery 为 -1 表示系统没读到电量；此时任何电量判定都必须跳过，否则会被当成“极低” */
        val known: Boolean get() = battery >= 0
    }

    enum class Action { NONE, THROTTLE, STOP }

    enum class Reason { NONE, LOW_BATTERY, LOW_BATTERY_STOP, HEAT, HEAT_STOP, TIME_LIMIT }

    /** PRD FR-13：低于 20% 省电降级，低于 10% 停止值守 */
    private const val BATTERY_THROTTLE = 20
    private const val BATTERY_STOP = 10

    /** PowerManager.THERMAL_STATUS_MODERATE / SEVERE，数值固定，不依赖 API 29 才有的常量可用性 */
    private const val THERMAL_THROTTLE = 2
    private const val THERMAL_STOP = 3

    /** PRD O2：未充电限时 2 小时，到点自动停止并提示 */
    const val UNPLUGGED_LIMIT_MS = 2L * 60 * 60 * 1000

    private const val PREFS = "power_policy"
    private const val KEY_CHARGE_ONLY = "charge_only"

    fun snapshot(context: Context): Snapshot {
        val battery = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        // 充电状态只能靠 sticky 广播读：没有它就无法区分“电量低但正在充”和“电量低且在掉”
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        } else {
            0
        }
        return Snapshot(battery, charging, thermal)
    }

    fun evaluate(snapshot: Snapshot): Action = when {
        // 过热优先于电量：高温会先伤设备，而且降载对温度更有效
        snapshot.thermal >= THERMAL_STOP -> Action.STOP
        snapshot.thermal >= THERMAL_THROTTLE -> Action.THROTTLE
        // 充电中不按电量降载：接电源时用户预期是满性能，边充边降档只会让人以为设备坏了
        snapshot.battery in 1 until BATTERY_STOP && !snapshot.charging -> Action.STOP
        snapshot.battery in 1 until BATTERY_THROTTLE && !snapshot.charging -> Action.THROTTLE
        else -> Action.NONE
    }

    fun reason(snapshot: Snapshot, action: Action): Reason = when (action) {
        Action.STOP -> if (snapshot.thermal >= THERMAL_STOP) Reason.HEAT_STOP else Reason.LOW_BATTERY_STOP
        Action.THROTTLE -> if (snapshot.thermal >= THERMAL_THROTTLE) Reason.HEAT else Reason.LOW_BATTERY
        Action.NONE -> Reason.NONE
    }

    /** PRD Q8/O2：“仅充电时值守”默认开启 */
    fun chargeOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHARGE_ONLY, true)

    fun setChargeOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CHARGE_ONLY, enabled)
            .apply()
    }
}
