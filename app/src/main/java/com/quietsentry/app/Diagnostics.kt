package com.quietsentry.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 健康面板与一键诊断（PRD FR-17）。
 *
 * 检查只读系统状态，不改任何设置：诊断的价值是“告诉用户卡在哪”并给去设置的入口，
 * 自动帮用户改权限会被商店判定为越权，也会让用户不知道应用动了什么。
 */
object Diagnostics {

    enum class Status { PASS, WARN, BLOCK }

    /** 每项检查对应的跳转目标；NONE 表示无需用户操作 */
    enum class Target { NONE, APP_SETTINGS, NOTIFICATION, BATTERY, WIFI }

    data class Check(
        val label: String,
        val status: Status,
        val detail: String,
        val target: Target,
    )

    fun run(context: Context): List<Check> = buildList {
        add(camera(context))
        add(notification(context))
        add(battery(context))
        add(service(context))
        add(network(context))
        add(storage(context))
        add(thermal(context))
        add(pairing(context))
    }

    /** 脱敏报告：只写权限与容量等状态值，不包含令牌、公钥、事件内容与路径 */
    fun exportReport(context: Context, checks: List<Check>): String = buildString {
        appendLine("QuietSentry 诊断报告")
        appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        appendLine("应用版本: " + appVersion(context))
        appendLine()
        checks.forEach { check ->
            appendLine("[${statusLabel(check.status)}] ${check.label}: ${check.detail}")
        }
    }

    private fun camera(context: Context): Check {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return Check(
            label = context.getString(R.string.diag_camera),
            status = if (granted) Status.PASS else Status.BLOCK,
            detail = context.getString(if (granted) R.string.diag_granted else R.string.diag_denied),
            target = if (granted) Target.NONE else Target.APP_SETTINGS,
        )
    }

    private fun notification(context: Context): Check {
        // Android 13 之前通知默认开启，没有可检查的运行时权限
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return Check(
            label = context.getString(R.string.diag_notification),
            status = if (granted) Status.PASS else Status.BLOCK,
            detail = context.getString(if (granted) R.string.diag_granted else R.string.diag_denied),
            target = if (granted) Target.NONE else Target.NOTIFICATION,
        )
    }

    private fun battery(context: Context): Check {
        val power = context.getSystemService(PowerManager::class.java)
        val exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        return Check(
            label = context.getString(R.string.diag_battery),
            status = if (exempt) Status.PASS else Status.WARN,
            detail = context.getString(if (exempt) R.string.diag_exempt else R.string.diag_not_exempt),
            target = if (exempt) Target.NONE else Target.BATTERY,
        )
    }

    private fun service(context: Context): Check = Check(
        label = context.getString(R.string.diag_service),
        status = Status.PASS,
        detail = context.getString(
            if (CaptureService.isRunning) R.string.diag_service_running
            else R.string.diag_service_idle
        ),
        target = Target.NONE,
    )

    private fun network(context: Context): Check {
        val address = PairingQr.localIpv4(context)
        return Check(
            label = context.getString(R.string.diag_network),
            status = if (address != null) Status.PASS else Status.WARN,
            detail = address ?: context.getString(R.string.diag_no_lan),
            target = if (address != null) Target.NONE else Target.WIFI,
        )
    }

    private fun storage(context: Context): Check {
        val freeMb = runCatching {
            StatFs(context.filesDir.absolutePath).let { it.availableBytes / (1024 * 1024) }
        }.getOrDefault(-1L)
        val low = freeMb in 0 until LOW_STORAGE_MB
        return Check(
            label = context.getString(R.string.diag_storage),
            status = if (low) Status.WARN else Status.PASS,
            detail = context.getString(R.string.diag_storage_free, freeMb),
            target = Target.NONE,
        )
    }

    private fun thermal(context: Context): Check {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 28 读不到散热状态，不作为问题项
            return Check(
                label = context.getString(R.string.diag_thermal),
                status = Status.PASS,
                detail = context.getString(R.string.diag_unsupported),
                target = Target.NONE,
            )
        }
        val power = context.getSystemService(PowerManager::class.java)
        val status = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        val severe = status >= PowerManager.THERMAL_STATUS_SEVERE
        return Check(
            label = context.getString(R.string.diag_thermal),
            status = if (severe) Status.WARN else Status.PASS,
            detail = context.getString(R.string.diag_thermal_level, status),
            target = Target.NONE,
        )
    }

    private fun pairing(context: Context): Check {
        val grants = runCatching { PairingStore(context).activeGrants().size }.getOrDefault(0)
        return Check(
            label = context.getString(R.string.diag_pairing),
            status = if (grants > 0) Status.PASS else Status.WARN,
            detail = context.getString(R.string.diag_grants, grants),
            target = Target.NONE,
        )
    }

    private fun statusLabel(status: Status): String = when (status) {
        Status.PASS -> "通过"
        Status.WARN -> "可优化"
        Status.BLOCK -> "阻塞"
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private const val LOW_STORAGE_MB = 500L
}
