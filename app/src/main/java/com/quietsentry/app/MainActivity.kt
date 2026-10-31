package com.quietsentry.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    }.toTypedArray()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
                statusText.text = CameraCapabilities.describe(this)
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

        findViewById<Button>(R.id.start_button).setOnClickListener {
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
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
            )
            statusText.text = getString(R.string.guard_started_tip)
        }
        findViewById<Button>(R.id.stop_button).setOnClickListener {
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

        if (requiredPermissions.all(::hasPermission)) {
            startCamera()
            statusText.text = CameraCapabilities.describe(this)
        } else {
            requestPermissions.launch(requiredPermissions)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)

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
