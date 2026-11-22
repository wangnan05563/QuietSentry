package com.quietsentry.app

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MVP 入口：预览用于放置与调角度，值守采集由 CaptureService 负责，
 * 这样退到后台或熄屏不会因为 Activity 生命周期而中断采集。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var bannerPermission: TextView
    private lateinit var recentEvent: TextView

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        // Android 13 起前台服务通知需要运行时授权，否则通知不可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // API 28 导出相册走公共目录 + 媒体扫描，没有该权限会直接写失败
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    // 导出由服务在后台线程完成，结果只能靠广播回来；否则“停止”点了之后界面毫无变化
    private val exportedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val saved = intent.getIntExtra(CaptureService.EXTRA_EXPORTED, 0)
            primaryButton.isEnabled = true
            statusText.text = if (saved > 0) {
                getString(R.string.saved_to_gallery, saved)
            } else {
                getString(R.string.save_failed)
            }
            // 导出完成说明服务已释放相机；用户仍停在本界面，此时必须立刻恢复预览，
            // 否则会一直停在“画面由后台采集”，直到切走再回来（T03770）。
            // 远程暂停是例外：设备仍在待机，预览恢复会重新占用相机，必须保持抑制。
            if (!CaptureService.isRunning && !GuardState.isPaused(this@MainActivity) && previewSuppressed) {
                previewSuppressed = false
                previewView.visibility = View.VISIBLE
                if (requiredPermissions.all(::hasPermission)) {
                    startCamera()
                }
            }
            // 停止值守后主按钮要立刻回到“开始值守”，不能等下次 onResume
            refreshPrimaryButton()
            refreshRecentEvent()
        }
    }

    private lateinit var primaryButton: Button

    // 值守期间相机由服务独占，界面预览被主动隐藏；用标记记住，停止值守回到界面后才恢复（T03770）
    private var previewSuppressed = false

    private val eventStore by lazy { EventStore(this) }

    // 状态卡轮询只在界面可见时运行：后台不空转，回到前台 onResume 重新拉起
    private val statusClock = Handler(Looper.getMainLooper())
    private val statusTicker = Runnable { tickStatusCard() }

    /** 状态卡轮询：SAM 转换后 `this` 指向 Activity，自我重投需要具名方法承接 */
    private fun tickStatusCard() {
        refreshStatusCard()
        // 值守由服务异步拉起，isRunning 在 beginGuard 返回后才置位；只在 onResume 刷按钮
        // 会让运行中仍显示“开始值守”，而点击实际执行的是停止——破坏性动作被标成启动。
        refreshPrimaryButton()
        refreshRecentEvent()
        // 存储余量与相机占用都会随时间变化，横幅必须进同一轮询
        refreshBanner()
        statusClock.postDelayed(statusTicker, REFRESH_INTERVAL_MS)
    }

    // 停止值守的导出反馈（正在保存/已保存 N 段）不允许被轮询覆盖，导出流程结束前置位
    private var holdStatusText = false

    // 预览绑定失败多半是相机被其他应用占用；置位后横幅给出“点此重试”，重绑成功即清除
    private var cameraBusy = false

    /** 已经向系统请求过权限：区分“还没问”与“问过被拒”，后者才需要常驻的恢复横幅 */
    private var permissionAsked = false

    companion object {
        private const val REFRESH_INTERVAL_MS = 10_000L
        private const val GB = 1024.0 * 1024 * 1024
        // 低于此余量横幅预警：写满会导致分片失败直接停值守，预警必须留出清理时间
        private const val STORAGE_LOW_BYTES = 500L * 1024 * 1024
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // 弹窗回来无论授没授，横幅都要按最新异常状态刷新
            refreshBanner()
            if (result[Manifest.permission.CAMERA] == true) {
                showCameraOrGuard()
            } else {
                // 横幅由 refreshBanner 统一维护（它会在 onResume 再刷一次），
                // 这里只补状态文字，避免两处各写一份显隐逻辑互相覆盖
                statusText.text = getString(R.string.camera_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 未确认合法用途前不展示任何采集入口：先能用再补确认，会被商店判定为未显著披露
        if (!ConsentStore.isAccepted(this)) {
            startActivity(Intent(this, ConsentActivity::class.java))
            finish()
            return
        }
        // 兜底入口：引导页被系统或用户跳过时（例如从通知直达），首次仍需补看一次
        if (!OnboardingStore.seen(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview_view)
        statusText = findViewById(R.id.status_text)
        primaryButton = findViewById(R.id.primary_button)
        bannerPermission = findViewById(R.id.banner_permission)
        // 横幅文案与动作由 refreshBanner 按当前最高优先异常统一设置
        // 通知点击直达回放：MainActivity 只是中转，用户真正要看的是那一段片段
        consumeEventIntent(intent)
        ContextCompat.registerReceiver(
            this,
            exportedReceiver,
            IntentFilter(CaptureService.ACTION_EXPORTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 主按钮按值守状态二合一：空闲=开始值守，运行=停止值守（入口收敛见 T04042）
        primaryButton.setOnClickListener {
            if (CaptureService.isRunning) confirmStop() else confirmThenStart { beginGuard() }
        }
        findViewById<Button>(R.id.more_button).setOnClickListener { showMoreMenu() }
        recentEvent = findViewById(R.id.recent_event)
        recentEvent.setOnClickListener {
            // 点击时重查一次拿最新事件：卡片渲染与点击之间可能刚好有新事件入库
            eventStore.recent(1).firstOrNull()?.let { event ->
                startActivity(
                    Intent(this, EventPlayerActivity::class.java)
                        .putExtra(EventPlayerActivity.EXTRA_EVENT_ID, event.id)
                )
            }
        }

        // 待机状态也要能在局域网里被发现：观看端的“在线·待机”完全依赖这个常驻监听，
        // 若等到开始值守才启动，用户第一次配对就找不到设备
        LanServerHost.ensure(applicationContext)

        // 先按当前异常状态定横幅显隐，再决定是否弹授权框；回调里还会再刷一次
        refreshBanner()
        if (requiredPermissions.all(::hasPermission)) {
            showCameraOrGuard()
        } else {
            permissionAsked = true
            requestPermissions.launch(requiredPermissions)
        }
    }

    /** “更多”入口：把菜单构建与分支处理挪出 onCreate，保持启动流程只做装配 */
    private fun showMoreMenu() {
        val entries = listOf(
            R.string.record_mode_entry to R.string.more_record_desc,
            R.string.black_mode_entry to R.string.more_black_desc,
            R.string.timeline_entry to R.string.more_timeline_desc,
            R.string.pairing_entry to R.string.more_pairing_desc,
            R.string.device_list_entry to R.string.more_device_desc,
            R.string.remote_entry to R.string.more_remote_desc,
            R.string.settings_entry to R.string.more_settings_desc,
        )
        // PRD 要求底部弹层：居中 AlertDialog 把高频功能塞进屏幕中央，读起来像错误弹窗。
        // 没有引入 Material 依赖，这里用 Dialog + 底部 Gravity + 圆角背景自己搭。
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_more_sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.6f)
        }
        val container = dialog.findViewById<LinearLayout>(R.id.more_sheet_items)
        entries.forEach { (titleRes, descRes) ->
            container.addView(buildMoreRow(titleRes, descRes) {
                dialog.dismiss()
                onMoreMenuItem(titleRes)
            })
        }
        dialog.findViewById<TextView>(R.id.more_sheet_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 弹层单行：标题 15sp + 说明 12sp，最小高度 64dp，整行可点，点击区远大于 44dp */
    private fun buildMoreRow(
        titleRes: Int,
        descRes: Int,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(12), dp(10))
            minimumHeight = dp(64)
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.qs_row_ripple)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = getString(titleRes)
            setTextColor(getColor(R.color.qs_text))
            textSize = 15f
        })
        row.addView(TextView(this).apply {
            text = getString(descRes)
            setTextColor(getColor(R.color.qs_text_muted))
            textSize = 12f
        })
        return row
    }

    private fun onMoreMenuItem(entry: Int) {
        when (entry) {
            R.string.record_mode_entry -> startRecordOnlyEntry()
            R.string.black_mode_entry -> startBlackScreenEntry()
            R.string.timeline_entry -> startActivity(Intent(this, EventTimelineActivity::class.java))
            R.string.pairing_entry -> startActivity(Intent(this, PairingActivity::class.java))
            R.string.device_list_entry -> startActivity(Intent(this, DeviceListActivity::class.java))
            R.string.remote_entry -> startActivity(Intent(this, RemoteAccessActivity::class.java))
            R.string.settings_entry -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /** 连续记录与值守互斥：服务会忽略重复 START，必须给明确反馈 */
    private fun startRecordOnlyEntry() {
        if (CaptureService.isRunning) {
            statusText.text = getString(R.string.guard_already_running)
            return
        }
        confirmThenStart { startRecordOnly() }
    }

    /** 值守与黑屏都通过 unbindAll 独占相机，同时进入会让值守录像静默中断 */
    private fun startBlackScreenEntry() {
        if (CaptureService.isRunning) {
            statusText.text = getString(R.string.guard_running_conflict)
            return
        }
        startActivity(Intent(this, BlackScreenActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时权限可能已变化，横幅必须跟着刷新
        refreshBanner()
        // 值守状态可能已变（如从通知停止、服务被杀），主按钮与事件卡都要跟着刷新
        refreshPrimaryButton()
        refreshRecentEvent()
        // 重新进入界面意味着上一轮导出反馈已被用户看过，状态卡恢复接管
        holdStatusText = false
        refreshStatusCard()
        statusClock.removeCallbacks(statusTicker)
        statusClock.postDelayed(statusTicker, REFRESH_INTERVAL_MS)
        // 值守中打开 App：界面再次 bind 会把服务已经绑定的采集一并解绑，必须让相机归后台（T03770）
        if (CaptureService.isRunning) {
            suppressPreview()
        } else if (previewSuppressed && !GuardState.isPaused(this)) {
            previewSuppressed = false
            previewView.visibility = View.VISIBLE
            if (requiredPermissions.all(::hasPermission)) {
                startCamera()
                statusText.text = CameraCapabilities.describe(this)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        statusClock.removeCallbacks(statusTicker)
    }

    /**
     * 值守与否决定相机归谁：服务运行中只展示状态，不打开预览；
     * 否则正常绑定预览。两个入口（权限回调、onCreate）都走这里，避免漏掉其中一条。
     */
    private fun showCameraOrGuard() {
        // 远程暂停虽然让服务停采，但设备仍在“在线待机”；此时打开 App 不能再抢相机，
        // 否则息屏待机的省电与隐私收益会被界面预览抵消。
        if (CaptureService.isRunning || GuardState.isPaused(this)) {
            suppressPreview()
            return
        }
        previewSuppressed = false
        previewView.visibility = View.VISIBLE
        startCamera()
        statusText.text = CameraCapabilities.describe(this)
    }

    private fun suppressPreview() {
        previewView.visibility = View.GONE
        previewSuppressed = true
        statusText.text = if (GuardState.isPaused(this)) {
            getString(R.string.state_paused)
        } else {
            getString(R.string.guard_running_preview)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 通知里携带事件 id 时直接进播放器。MainActivity 不 finish：
     * 播放器返回后仍能回到值守主界面，用户不必从桌面重新找入口。
     */
    private fun consumeEventIntent(intent: Intent?) {
        val eventId = intent?.getLongExtra(EventNotifier.EXTRA_EVENT_ID, -1L) ?: -1L
        if (eventId <= 0L) return
        startActivity(
            Intent(this, EventPlayerActivity::class.java)
                .putExtra(EventPlayerActivity.EXTRA_EVENT_ID, eventId)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeEventIntent(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(exportedReceiver) }
        super.onDestroy()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * 三类异常共用一条横幅（PRD 3.3 单横幅原则），按严重度取最高优先：
     * 通知权限（告警丢失）> 相机占用（预览不可用）> 存储不足（值守将中断）。
     * 每类只给一个“唯一动作”，直接换文案与点击行为，不做多按钮。
     */
    private fun refreshBanner() {
        when {
            // 相机权限被拒且已经问过：系统不会再弹框，只能把用户送到设置页手动开
            !hasPermission(Manifest.permission.CAMERA) && permissionAsked ->
                showBanner(R.string.banner_camera_permission) { openAppSettings() }
            !hasNotificationPermission() -> showBanner(R.string.banner_notification_permission) {
                // T05207 实测：部分 Android 14 镜像的 AppNotificationSettingsActivity
                // 不接受 package: data，直接带 data 启动会抛 ActivityNotFoundException 让首页崩溃。
                // 改用官方 EXTRA_APP_PACKAGE（无 data），并兜底到应用详情页，保证「点此去开启」永不崩。
                val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(notificationSettings) }
                    .onFailure { runCatching { startActivity(appDetails) } }
            }
            cameraBusy -> showBanner(R.string.banner_camera_busy) {
                // 占用方释放是外部事件，用户主动重试是唯一出路；仍失败会在 bindPreview 再置位
                startCamera()
            }
            isStorageLow() -> showBanner(R.string.banner_storage_low) {
                // 清理手段（最近删除/保留天数）都在时间线，横幅只负责把人带到能清理的地方
                startActivity(Intent(this, EventTimelineActivity::class.java))
            }
            else -> bannerPermission.visibility = View.GONE
        }
    }

    private fun showBanner(textRes: Int, action: () -> Unit) {
        bannerPermission.setText(textRes)
        bannerPermission.setOnClickListener { action() }
        bannerPermission.visibility = View.VISIBLE
    }

    /** 权限被“不再询问”拦死后的唯一出路：跳应用详情页改授权。 */
    private fun openAppSettings() {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(appDetails) }
    }

    private fun isStorageLow(): Boolean =
        StatFs(filesDir.absolutePath).availableBytes < STORAGE_LOW_BYTES

    /** 主按钮文案随值守状态切换；只在状态可能变化的时机调用（onResume / 导出完成）。 */
    private fun refreshPrimaryButton() {
        primaryButton.text =
            getString(if (CaptureService.isRunning) R.string.stop_guard else R.string.start_guard)
    }

    /**
     * 状态卡：运行中显示模式、时长与存储余量，空闲显示待机与存储余量。
     * 停止值守的导出反馈是破坏性操作的关键回执，用 holdStatusText 挡住轮询覆盖。
     */
    private fun refreshStatusCard() {
        if (holdStatusText) return
        val freeGb = StatFs(filesDir.absolutePath).availableBytes / GB
        val free = String.format(Locale.US, "%.1fGB", freeGb)
        val running = CaptureService.isRunning
        // 远程暂停后服务已停、isRunning 为 false，但“待机”会把“被观看端暂停”这件事藏掉：
        // 用户回到相机端只会看到一个可以重新开始的待机页，不知道是谁、为什么停的。
        val paused = GuardState.isPaused(this)
        val message = if (running) {
            val total = (System.currentTimeMillis() - GuardRuntime.startedAtMs).coerceAtLeast(0L) / 1000
            val elapsed = String.format(Locale.US, "%02d:%02d:%02d", total / 3600, total % 3600 / 60, total % 60)
            getString(
                if (GuardRuntime.recordOnly) R.string.status_card_recording else R.string.status_card_guarding,
                elapsed,
                free,
                GuardRuntime.segmentCount
            )
        } else if (paused) {
            getString(R.string.status_card_paused, free)
        } else {
            getString(R.string.status_card_idle, free)
        }
        // 状态行原本只有文字，“正在值守”和“已停止”在余光里几乎一样；
        // 补一个状态色点，取值沿用设备列表的 qs_ok / qs_idle，避免两套语义
        val line = SpannableString("● $message")
        line.setSpan(
            ForegroundColorSpan(
                ContextCompat.getColor(
                    this,
                    when {
                        running -> R.color.qs_ok
                        paused -> R.color.qs_warn
                        else -> R.color.qs_idle
                    }
                )
            ),
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        statusText.text = line
    }

    /**
     * 最近事件卡：有事件时给时间/类型/留存状态，没有事件时给一行空态说明。
     * 直接整块隐藏会让新用户以为“这一栏永远不会有东西”，
     * 而空态文案同时也告诉用户值守期间什么情况才会产生事件。
     * 是否“已留存”不只看 clip 路径——片段导出到相册后私有原件会被删除，
     * 此时仍有 clipUri 可播，同样算已留存。
     */
    private fun refreshRecentEvent() {
        val event = eventStore.recent(1).firstOrNull()
        if (event == null) {
            // 空态不响应点击：跳到空时间轴等于让用户白跑一趟
            recentEvent.isClickable = false
            recentEvent.text = getString(R.string.recent_event_empty)
            recentEvent.visibility = View.VISIBLE
            return
        }
        recentEvent.isClickable = true
        recentEvent.visibility = View.VISIBLE
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(event.occurredAt))
        val retained = event.clipUri != null || event.clip?.isFile == true
        recentEvent.text = getString(
            R.string.recent_event_line,
            time,
            getString(event.type.labelRes),
            getString(if (retained) R.string.recent_event_retained else R.string.recent_event_recording)
        )
    }

    /**
     * 值守与连续记录共用的启动前置流程：权限与“仅充电”确认都过了才真正启动。
     * 未接电源必须先确认“限时 2 小时”，否则到点自动停止时人不在场也找不到原因。
     */
    private fun confirmThenStart(start: () -> Unit) {
        if (!ensureStartable()) return
        val snapshot = PowerGuard.snapshot(this)
        if (PowerGuard.chargeOnly(this) && !snapshot.charging) {
            AlertDialog.Builder(this)
                .setTitle(R.string.power_unplugged_title)
                .setMessage(R.string.power_unplugged_message)
                .setPositiveButton(R.string.power_unplugged_confirm) { _, _ -> start() }
                .setNegativeButton(R.string.power_unplugged_cancel, null)
                .show()
            return
        }
        start()
    }

    private fun confirmStop() {
        // 停止是破坏性操作：私有原件导出后即被删除，误触无法回退，必须先确认
        AlertDialog.Builder(this)
            .setTitle(R.string.stop_guard_confirm_title)
            .setMessage(R.string.stop_guard_confirm_message)
            .setPositiveButton(R.string.stop_guard_confirm_yes) { _, _ ->
                // 导出要等最后一个分片 Finalize 后才开始，先给出“正在保存”提示并禁用以防重复点击
                primaryButton.isEnabled = false
                holdStatusText = true
                statusText.text = getString(R.string.saving_to_gallery)
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 连续记录模式：与值守共用同一条采集与分片管线，只是不跑检测与声音告警。
     * 为什么仍走前台服务：录制本身就是 camera 类型前台服务的职责，绕开它会被系统在息屏后回收。
     */
    private fun startRecordOnly() {
        withBatteryExemption {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RECORD_ONLY, true)
            )
            suppressPreview()
            // 同 beginGuard：isRunning 尚未置位，状态卡交给 ticker 接管
        }
    }

    /**
     * 先讲清楚“为什么要给电池豁免”，再打开系统设置。
     * 直接跳系统弹窗（历史上就是这样）用户只看到一句英文标题，不知道给了会怎样、不给又会怎样。
     */
    private fun withBatteryExemption(onProceed: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            onProceed()
            return
        }
        val power = getSystemService(PowerManager::class.java)
        if (power == null || power.isIgnoringBatteryOptimizations(packageName)) {
            onProceed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_explain_title)
            .setMessage(R.string.battery_explain_message)
            .setPositiveButton(R.string.battery_explain_allow) { _, _ ->
                requestBatteryExemption()
                onProceed()
            }
            .setNegativeButton(R.string.battery_explain_later) { _, _ -> onProceed() }
            .show()
    }

    /** 值守与连续记录共用的启动前置检查：权限缺一不可，否则前台服务会直接抛异常。 */
    private fun ensureStartable(): Boolean {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            permissionAsked = true
            requestPermissions.launch(requiredPermissions)
            statusText.text = getString(R.string.permission_required)
            return false
        }
        if (!hasNotificationPermission()) {
            permissionAsked = true
            requestPermissions.launch(requiredPermissions)
            statusText.text = getString(R.string.notification_required)
            return false
        }
        return true
    }

    private fun beginGuard() {
        withBatteryExemption {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
            )
            // 服务启动会 unbindAll 抢走相机，界面预览先收起来，避免留下一个永远黑屏的取景框。
            // 此刻服务的 isRunning 还没置位，立即刷状态卡会误显示“待机”，交给 ticker 10 秒内接管
            suppressPreview()
        }
    }

    /**
     * ROM 对未豁免应用有独立的“锁屏冻结”策略，camera 类型前台服务并不在豁免范围内。
     * 用系统标准对话框申请电池豁免；用户拒绝也照常启动，避免把可用性堵在这一步。
     */
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            bindPreview(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            // 绑定成功说明占用已释放，横幅立即收起，不等下一轮 ticker
            if (cameraBusy) {
                cameraBusy = false
                refreshBanner()
            }
        } catch (e: Exception) {
            // 旧设备可能没有后置主摄，或镜头被其他应用占用，失败要让用户看到原因
            cameraBusy = true
            refreshBanner()
            statusText.text = getString(R.string.camera_open_failed, e.message ?: "")
        }
    }
}
