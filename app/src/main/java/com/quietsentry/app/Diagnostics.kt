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
    enum class Target { NONE, APP_SETTINGS, NOTIFICATION, BATTERY, AUTO_START, WIFI }

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
        add(autoStart(context))
        add(service(context))
        add(network(context))
        add(storage(context))
        add(thermal(context))
        add(detection(context))
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

    /**
     * 自启动没有标准 API 可查：各 ROM 的白名单是私有数据库，第三方应用读不到。
     * 因此按厂商给出“需要手动确认”的提示，而不是伪造一个“已允许”的结论；
     * AOSP 类系统只要声明了 RECEIVE_BOOT_COMPLETED 就能收到广播，视为通过。
     */
    private fun autoStart(context: Context): Check {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.US)
        val restricted = LIMITED_AUTOSTART_BRANDS.any { brand.contains(it) }
        return Check(
            label = context.getString(R.string.diag_autostart),
            status = if (restricted) Status.WARN else Status.PASS,
            detail = context.getString(
                if (restricted) R.string.diag_autostart_manual else R.string.diag_autostart_ok
            ),
            target = if (restricted) Target.AUTO_START else Target.NONE,
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

    /**
     * 端侧检测档位（W26）：不做“支持/不支持”的猜测，只展示真实探测到的档位与模型状态。
     * 模型资产存在且档位允许时才说“已启用”，避免用户以为“没看到人形事件”是漏检。
     */
    private fun detection(context: Context): Check {
        val profile = DetectionProfile.of(context)
        // 只检查资产存在不足以说明“能用”：这里真跑一次前向，把张量与运行时问题暴露在诊断里
        val modelReady = runCatching {
            HumanDetector.create(context)?.use { it.probe() } ?: false
        }.getOrDefault(false)
        val enabled = profile.humanDetection && modelReady
        val detailRes = if (enabled) {
            R.string.diag_detection_detail_on
        } else {
            R.string.diag_detection_detail_off
        }
        return Check(
            label = context.getString(R.string.diag_detection),
            status = if (enabled) Status.PASS else Status.WARN,
            detail = context.getString(detailRes, context.getString(profile.tier.labelRes)),
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

    /** 定制系统普遍在“应用启动管理”里另设自启动开关，默认关闭 */
    private val LIMITED_AUTOSTART_BRANDS = listOf(
        "xiaomi", "redmi", "huawei", "honor", "oppo", "realme",
        "vivo", "iqoo", "meizu", "samsung", "asus", "lenovo",
    )
}
