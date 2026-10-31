package com.quietsentry.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * MVP 入口：预览用于放置与调角度，值守采集由 CaptureService 负责，
 * 这样退到后台或熄屏不会因为 Activity 生命周期而中断采集。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView

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
            stopButton.isEnabled = true
            statusText.text = if (saved > 0) {
                getString(R.string.saved_to_gallery, saved)
            } else {
                getString(R.string.save_failed)
            }
        }
    }

    private lateinit var stopButton: Button

    // 值守期间相机由服务独占，界面预览被主动隐藏；用标记记住，停止值守回到界面后才恢复（T03770）
    private var previewSuppressed = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                showCameraOrGuard()
            } else {
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
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview_view)
        statusText = findViewById(R.id.status_text)
        stopButton = findViewById(R.id.stop_button)
        // 通知点击直达回放：MainActivity 只是中转，用户真正要看的是那一段片段
        consumeEventIntent(intent)
        ContextCompat.registerReceiver(
            this,
            exportedReceiver,
            IntentFilter(CaptureService.ACTION_EXPORTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        findViewById<Button>(R.id.start_button).setOnClickListener {
            // 服务已在采集时再发 START 会被内部直接忽略，界面必须自己给出反馈，否则用户以为没点上
            if (CaptureService.isRunning) {
                statusText.text = getString(R.string.guard_already_running)
                return@setOnClickListener
            }
            // camera 类型前台服务要求相机权限已授予，否则系统在 startForeground 时直接抛异常
            if (!hasPermission(Manifest.permission.CAMERA)) {
                requestPermissions.launch(requiredPermissions)
                statusText.text = getString(R.string.permission_required)
                return@setOnClickListener
            }
            // PRD §10.5：常驻通知是用户确认“正在采集”的唯一可见凭据，没有它就不允许启动
            if (!hasNotificationPermission()) {
                requestPermissions.launch(requiredPermissions)
                statusText.text = getString(R.string.notification_required)
                return@setOnClickListener
            }
            // 未豁免时弹系统对话框：实测锁屏 7 分钟后 Samsung 会冻结 UID，前台服务挡不住
            requestBatteryExemption()
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
            )
            // 服务启动会 unbindAll 抢走相机，界面预览先收起来，避免留下一个永远黑屏的取景框
            suppressPreview()
        }
        stopButton.setOnClickListener {
            // 导出要等最后一个分片 Finalize 后才开始，先给出“正在保存”提示并禁用以防重复点击
            stopButton.isEnabled = false
            statusText.text = getString(R.string.saving_to_gallery)
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        findViewById<Button>(R.id.black_mode_button).setOnClickListener {
            // 值守与黑屏都通过 unbindAll 独占相机，同时进入会让值守录像静默中断
            if (CaptureService.isRunning) {
                statusText.text = getString(R.string.guard_running_conflict)
                return@setOnClickListener
            }
            startActivity(Intent(this, BlackScreenActivity::class.java))
        }
        findViewById<Button>(R.id.timeline_button).setOnClickListener {
            startActivity(Intent(this, EventTimelineActivity::class.java))
        }
        findViewById<Button>(R.id.pairing_button).setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        findViewById<Button>(R.id.device_list_button).setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }
        findViewById<Button>(R.id.remote_access_button).setOnClickListener {
            startActivity(Intent(this, RemoteAccessActivity::class.java))
        }

        // 待机状态也要能在局域网里被发现：观看端的“在线·待机”完全依赖这个常驻监听，
        // 若等到开始值守才启动，用户第一次配对就找不到设备
        LanServerHost.ensure(applicationContext)

        if (requiredPermissions.all(::hasPermission)) {
            showCameraOrGuard()
        } else {
            requestPermissions.launch(requiredPermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        // 值守中打开 App：界面再次 bind 会把服务已经绑定的采集一并解绑，必须让相机归后台（T03770）
        if (CaptureService.isRunning) {
            suppressPreview()
        } else if (previewSuppressed) {
            previewSuppressed = false
            previewView.visibility = View.VISIBLE
            if (requiredPermissions.all(::hasPermission)) {
                startCamera()
                statusText.text = CameraCapabilities.describe(this)
            }
        }
    }

    /**
     * 值守与否决定相机归谁：服务运行中只展示状态，不打开预览；
     * 否则正常绑定预览。两个入口（权限回调、onCreate）都走这里，避免漏掉其中一条。
     */
    private fun showCameraOrGuard() {
        if (CaptureService.isRunning) {
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
        statusText.text = getString(R.string.guard_running_preview)
    }

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
        } catch (e: Exception) {
            // 旧设备可能没有后置主摄，或镜头被其他应用占用，失败要让用户看到原因
            statusText.text = getString(R.string.camera_open_failed, e.message ?: "")
        }
    }
}
