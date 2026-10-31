package com.quietsentry.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 值守模式的前台服务：相机与录制都在服务内绑定，屏幕熄灭、Activity 退到后台后仍持续采集。
 * 前台通知既是平台硬约束，也是用户确认“正在采集”的唯一可见凭据。
 */
class CaptureService : LifecycleService() {

    // Service 的 lifecycle 不会自动进入 RESUMED，CameraX 需要 RESUMED 才真正打开相机，
    // 因此用独立 Owner，把相机生命周期与界面可见性彻底解耦。
    private val cameraOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
    }

    private val clipDir by lazy { File(filesDir, "clips").apply { mkdirs() } }

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var running = false
    private var stopping = false
    private var segmentIndex = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (running) return
        stopping = false
        running = true
        // 上一次进程被杀会留下未收敛的分片，先修复再开新片，避免索引里出现打不开的文件
        repairInterruptedClips()
        // 启动即执行一次保留策略：进程可能已经很久没运行过，期间新录的内容可能已超出过期或配额
        StorageKeeper.enforce(this)
        updateNotification(getString(R.string.capture_starting))

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val capture = VideoCapture.withOutput(Recorder.Builder().build())
            videoCapture = capture
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(cameraOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                startNextSegment()
            } catch (e: Exception) {
                // 相机被其他应用占用或设备无后置镜头：让用户看到原因，而不是一直显示“采集中”
                updateNotification(getString(R.string.camera_open_failed, e.message ?: ""))
                running = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 30 秒一段：分片越短，进程被杀时损失越少；分片之间先落 .pending 标记，
     * Finalize 成功才删除，这样启动时能准确识别“上次没录完”的文件。
     */
    private fun startNextSegment() {
        if (!running || stopping) return
        // 每 10 片检查一次保留策略：每片都扫目录不划算，间隔太久又可能在一轮长时间录制里把存储写满
        if (segmentIndex > 0 && segmentIndex % CHECK_EVERY_SEGMENTS == 0) {
            StorageKeeper.enforce(this)
        }
        val capture = videoCapture ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(clipDir, "clip_${stamp}_${segmentIndex++}.mp4")
        val pending = File(output.path + ".pending")

        val options = FileOutputOptions.Builder(output).build()
        var pendingRecording = capture.output.prepareRecording(this, options)
        if (hasAudioPermission()) pendingRecording = pendingRecording.withAudioEnabled()
        pending.writeText(output.name)

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event !is VideoRecordEvent.Finalize) return@start
            pending.delete()
            if (event.hasError()) {
                updateNotification(getString(R.string.segment_error, event.error))
            }
            startNextSegment()
        }
    }

    private fun repairInterruptedClips() {
        val strayPending = clipDir.listFiles { file -> file.name.endsWith(".pending") } ?: return
        for (marker in strayPending) {
            val name = marker.readText().trim()
            val clip = File(clipDir, name)
            // 缺少 moov 的分片无法解码，移出 clips 目录而不是直接删除，给用户保留取证与排查的可能
            if (!isReadable(clip)) {
                clip.renameTo(File(clipDir, "orphan_$name"))
            }
            marker.delete()
        }
    }

    private fun isReadable(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) != null
        } catch (e: Exception) {
            false
        } finally {
            retriever.release()
        }
    }

    private fun stopCapture() {
        stopping = true
        running = false
        recording?.stop()
        recording = null
        stopSelf()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundWithType() {
        val notification = buildNotification(getString(R.string.capture_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 14 起，申报 microphone 类型要求录音权限已授予，否则 startForeground 直接抛异常；
            // 用户拒绝录音时退化为纯相机类型，录制本身仍可用（只是没有声音）
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasAudioPermission()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop_guard), stopPending)
            .build()
    }

    override fun onDestroy() {
        provider?.unbindAll()
        provider = null
        videoCapture = null
        recording = null
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.quietsentry.app.action.START"
        const val ACTION_STOP = "com.quietsentry.app.action.STOP"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val CHECK_EVERY_SEGMENTS = 10
    }
}
