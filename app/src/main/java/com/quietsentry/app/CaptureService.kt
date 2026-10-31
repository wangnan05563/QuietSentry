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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

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
    private val thumbnailDir by lazy { File(filesDir, "events/thumbs").apply { mkdirs() } }
    private val eventStore by lazy { EventStore(this) }
    private val notifier by lazy { EventNotifier(this) }

    /** 网络状态供离线排队摘要使用；回调来自系统线程，因此用 @Volatile 暴露 */
    @Volatile
    private var networkAvailable = true
    private var offlineEvents = 0
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 已入库但还没挂上片段的“检测中”事件；检测器在分析/录音线程触发，用并发队列避免跨线程改列表 */
    private val pendingEvents = ConcurrentLinkedQueue<Long>()

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var soundDetector: SoundDetector? = null
    private var recording: Recording? = null
    private var running = false
    private var stopping = false
    private var segmentIndex = 0

    /**
     * 相机分析与 JPEG 编码放在独立单线程：CameraX 默认用传入的 executor 回调，
     * 640x480 的 YUV→Bitmap→JPEG 每帧约 10ms，放在主线程会直接造成通知与界面掉帧，
     * 也会让录制回调延迟。KEEP_ONLY_LATEST 保证积压时只保留最新帧，不会无限排队。
     */
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // 事件前覆盖（PRD FR-07）需要两样东西：本片起点用于算事件偏移，上一片用于补齐片头 5 秒
    private var segmentStartMs = 0L
    private var previousClip: File? = null

    // 本场值守的起点，用来区分“本次要导出到相册的分片”和目录里的历史文件
    private var sessionStartMs = 0L

    // CameraX 没有“分片时长上限”API，只能计时到点主动 stop()，由 Finalize 回调再开下一片。
    // 没有这个计时器时单片会一直写下去，实测 5 分钟就积累到 719MB，进程被杀时损失整段
    private val segmentTimer = Handler(Looper.getMainLooper())
    private val stopCurrentSegment = Runnable { recording?.stop() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 事件通道必须在第一次 notify 之前建好，否则 Android 8+ 会直接丢弃通知
        notifier.ensureChannels()
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
            // 进程被系统回收后 START_STICKY 重建时 intent 为空：只有上次确实在值守才恢复采集，
            // 用户主动停止会先把标记清掉，因此不会出现“停了又自己活过来”（T03771）
            null -> if (!running && wasGuardActive()) startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (running) return
        stopping = false
        running = true
        isRunning = true
        markGuardActive(true)
        sessionStartMs = System.currentTimeMillis()
        // 上一次进程被杀会留下未收敛的分片，先修复再开新片，避免索引里出现打不开的文件
        repairInterruptedClips()
        // 启动即执行一次保留策略：进程可能已经很久没运行过，期间新录的内容可能已超出过期或配额
        StorageKeeper.enforce(this)
        startNetworkMonitor()
        updateNotification(getString(R.string.capture_starting))

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val capture = VideoCapture.withOutput(Recorder.Builder().build())
            videoCapture = capture
            // 运动检测复用同一路相机流；640x480 同时作为主码流源，320x240 时无法提供能看清的直播画面
            val detector = MotionDetector(
                DETECTION_SENSITIVITY,
                MotionDetector.ALL_GRID,
                thumbnailDir
            ) { confidence, thumbnail ->
                onDetected(EventType.MOTION, R.string.detection_motion, confidence, thumbnail)
            }
            val frameAnalyzer = ImageAnalysis.Analyzer { image ->
                // 先发布到实时流再交给检测器；检测器负责 image.close()，
                // 这里不能提前关闭，否则 JPEG 编码与运动检测都会拿到失效缓冲
                LanFrameHub.publish(image)
                detector.analyze(image)
            }
            analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
                .also { it.setAnalyzer(analysisExecutor, frameAnalyzer) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    cameraOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture,
                    analysis
                )
                startSoundDetector()
                startNextSegment()
                // 相机绑定成功后才值得对外提供服务：否则观看端只能拿到一个“在线但永远无画面”的设备
                startLanServer()
            } catch (e: Exception) {
                // 相机被其他应用占用或设备无后置镜头：让用户看到原因，而不是一直显示“采集中”
                updateNotification(getString(R.string.camera_open_failed, e.message ?: ""))
                running = false
                isRunning = false
                // 相机打不开就不能算“值守中”，否则系统重建服务时会反复重试失败
                markGuardActive(false)
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
        // 起点要在这里而不是 Finalize 里取：Finalize 时刻比真实开录晚若干百毫秒，
        // 用它算偏移会让“事件前 5 秒”整体向后漂
        segmentStartMs = System.currentTimeMillis()
        val pending = File(output.path + ".pending")

        val options = FileOutputOptions.Builder(output).build()
        var pendingRecording = capture.output.prepareRecording(this, options)
        if (hasAudioPermission()) pendingRecording = pendingRecording.withAudioEnabled()
        pending.writeText(output.name)

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event !is VideoRecordEvent.Finalize) return@start
            // 分片可能因为相机异常提前结束，必须清掉残留计时，否则它会误停刚开起的下一片
            segmentTimer.removeCallbacks(stopCurrentSegment)
            pending.delete()
            if (event.hasError()) {
                updateNotification(getString(R.string.segment_error, event.error))
                // 写坏的分片不能挂给事件：保持“检测中”，回看页会如实提示文件不可用
                pendingEvents.clear()
            } else {
                attachPendingEvents(output, segmentStartMs, previousClip)
                // 保住刚收敛的片子，下一片的事件若发生在片头 5 秒内要靠它补前置画面
                previousClip = output
            }
            // 停止守值时最后一个分片要靠这次 Finalize 收敛，收敛后才能安全导出
            if (stopping) finishSession() else startNextSegment()
        }
        // 到点主动收片：片长可控，被杀或断电时最多损失一个分片
        segmentTimer.postDelayed(stopCurrentSegment, SEGMENT_DURATION_MS)
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

    private fun startSoundDetector() {
        soundDetector = SoundDetector(DETECTION_SENSITIVITY) {
            onDetected(EventType.SOUND, R.string.detection_sound, it, null)
        }.also { it.start(this) }
    }

    /**
     * 事件入库与通知：检测回调来自分析线程与录音线程，先写索引（SQLite 自身线程安全），
     * 再把界面侧动作切回主线程。声音事件没有对应画面，缩略图为 null。
     */
    private fun onDetected(type: EventType, typeRes: Int, confidence: Int, thumbnail: File?) {
        val outcome = eventStore.record(type, confidence, thumbnail)
        pendingEvents.add(outcome.id)
        // 合并进既有事件时不再提醒：同源 60 秒内的连续触发只推一次（PRD FR-09）
        if (!outcome.merged) {
            eventStore.get(outcome.id)?.let { notifyEvent(it) }
        }
        segmentTimer.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            updateNotification(getString(typeRes, time))
            Log.i(
                TAG,
                "detected type=${type.raw} time=$time confidence=$confidence " +
                    "eventId=${outcome.id} merged=${outcome.merged}"
            )
        }
    }

    /**
     * 发送前置判定全部在端侧完成：免打扰时段与接收级别只过滤“打扰方式”，
     * 事件本身已入库，不会因为被过滤而丢失（PRD FR-09）。
     */
    private fun notifyEvent(event: Event) {
        if (!networkAvailable) {
            // 离线只累计，恢复后补一条摘要；不在这里逐条重发，避免恢复瞬间刷屏
            offlineEvents++
        }
        if (AlertPolicy.isQuietNow(this)) return
        val level = AlertPolicy.levelOf(event.type, event.triggers)
        if (!AlertPolicy.accepts(this, level)) return
        notifier.showEvent(event, level)
    }

    /**
     * 网络监听只在值守期间有意义：离线计数与“恢复后摘要”都绑定这一场值守，
     * 服务销毁时注销，避免回调泄漏到进程级。
     */
    private fun startNetworkMonitor() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = manager
        networkAvailable = manager.activeNetwork != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOffline = !networkAvailable
                networkAvailable = true
                val queued = offlineEvents
                offlineEvents = 0
                if (wasOffline && queued > 0) notifier.showOfflineSummary(queued)
            }

            override fun onLost(network: Network) {
                networkAvailable = false
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private fun stopNetworkMonitor() {
        val manager = connectivity
        val callback = networkCallback
        networkCallback = null
        connectivity = null
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * 分片收敛后把本片期间产生的“检测中”事件挂到片段上；只有文件可读才流转为“已录制”。
     * 同时记录事件相对本片起点的偏移，回看页据此定位到“事件前 5 秒”而不是从分片开头播。
     */
    private fun attachPendingEvents(clip: File, clipStartMs: Long, prev: File?) {
        val ids = generateSequence { pendingEvents.poll() }.toList()
        if (ids.isEmpty()) return
        eventStore.markRecorded(ids, clip, clipStartMs, prev)
    }

    /**
     * 局域网服务由 LanServerHost 常驻，值守只是让它开始有画面可推。
     * 这里仅做一次幂等拉起，失败不影响采集：端口全被占用属于极端情况，只损失跨设备观看。
     */
    private fun startLanServer() {
        LanServerHost.ensure(this)
    }

    private fun stopCapture() {
        stopping = true
        running = false
        isRunning = false
        // 用户主动停止必须清除恢复标记：进程再被重建也不能把值守自己拉起来
        markGuardActive(false)
        // 不关闭局域网监听：设备要在“在线·待机”下继续可发现、可配对，
        // 直播则因没有新帧自然断开（LanFrameHub 长时间无帧）
        // 停止即结束检测：导出期间继续分析画面没有意义，只多耗电
        soundDetector?.stop()
        soundDetector = null
        // 先摘掉计时器，避免它落在一个已经不存在的录制上
        segmentTimer.removeCallbacks(stopCurrentSegment)
        val active = recording
        recording = null
        // 没有进行中的录制（进程重启后用户直接点停止）：不等待不存在的 Finalize，直接收尾
        if (active == null) finishSession() else active.stop()
    }

    /**
     * 把本场值守的分片导出到相册，导出完成后才真正结束服务。
     * 复制整段视频是 IO 密集型操作，必须离开主线程；也不能提前 stopSelf，否则进程收尾会掐断导出。
     */
    private fun finishSession() {
        val from = if (sessionStartMs > 0L) sessionStartMs else System.currentTimeMillis() - FALLBACK_SESSION_MS
        val clips = clipDir.listFiles { file ->
            file.isFile && !file.name.endsWith(".pending") && file.lastModified() >= from
        }.orEmpty().toList()
        Thread {
            val exported = GalleryExporter.export(this, clips)
            // 私有原件已在导出成功后删除，索引必须回写相册定位，否则回看页永远显示“文件不可用”
            exported.forEach { eventStore.markExported(it.source.absolutePath, it.uri) }
            // 每次收尾顺手清理过期的“最近删除”，不依赖用户下次打开回看页才有空间回收
            eventStore.purgeDeletedBefore(System.currentTimeMillis() - DELETED_RETAIN_MS)
            sendBroadcast(
                Intent(ACTION_EXPORTED).setPackage(packageName)
                    .putExtra(EXTRA_EXPORTED, exported.size)
            )
            stopSelf()
        }.start()
    }

    /**
     * 为什么要落盘而不是只靠 running 字段：START_STICKY 重建发生在全新服务实例里，
     * 内存状态早已清零，只有持久化标记才能区分“系统杀进程”和“用户点了停止”。
     */
    private fun markGuardActive(active: Boolean) {
        getSharedPreferences(PREFS_GUARD, MODE_PRIVATE).edit()
            .putBoolean(KEY_GUARD_ACTIVE, active)
            .apply()
    }

    private fun wasGuardActive(): Boolean =
        getSharedPreferences(PREFS_GUARD, MODE_PRIVATE).getBoolean(KEY_GUARD_ACTIVE, false)

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
        segmentTimer.removeCallbacks(stopCurrentSegment)
        stopNetworkMonitor()
        soundDetector?.stop()
        soundDetector = null
        analysis = null
        // 先解绑相机再关线程池：正在执行的 analyze 会继续使用 CameraX 的 ImageProxy，
        // 顺序反过来会让 shutdownNow 中断一个仍持有相机缓冲的线程
        provider?.unbindAll()
        provider = null
        videoCapture = null
        recording = null
        analysisExecutor.shutdownNow()
        running = false
        isRunning = false
        // 服务实例销毁后进程可能还活着，不关连接会随每次启停累积未释放的 SQLite 句柄
        eventStore.close()
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

        /** 检测灵敏度 1-5（PRD FR-07），默认居中；调参入口在设置页（W20）落地后接入 */
        private const val DETECTION_SENSITIVITY = 3

        private const val TAG = "QuietSentry"

        /** 分片时长上限；PRD §10 约定 30 秒，兼顾“被杀损失最小”与“文件数量可接受”。 */
        private const val SEGMENT_DURATION_MS = 30_000L

        const val ACTION_EXPORTED = "com.quietsentry.app.action.EXPORTED"
        const val EXTRA_EXPORTED = "exported_count"

        /** 进程重启后 sessionStartMs 已丢失，兜底只导最近 10 分钟，避免把历史分片重新灌进相册。 */
        private const val FALLBACK_SESSION_MS = 10 * 60 * 1000L

        /** “最近删除”保留期（PRD O6：7 天），超期后才允许物理清除索引与私有文件。 */
        private val DELETED_RETAIN_MS = 7L * 24 * 60 * 60 * 1000

        /** 值守恢复标记；只在本服务读写，避免与其他偏好项混在同一个文件里。 */
        private const val PREFS_GUARD = "guard_state"
        private const val KEY_GUARD_ACTIVE = "guard_active"

        /** 供界面判断值守是否在运行；相机是独占资源，值守与黑屏模式不能同时绑定。仅由本服务写入。 */
        @Volatile
        var isRunning = false
    }
}
