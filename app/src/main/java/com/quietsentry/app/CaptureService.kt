package com.quietsentry.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
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
import java.util.concurrent.atomic.AtomicInteger

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
    /**
     * 离线待补发的事件条数。写方是运动（分析线程）与声音（录音线程）两个检测线程，
     * 读改写方是系统网络回调线程，普通 Int 既非原子也无可见性保证——
     * 实测恢复网络时可能读到 0，整段离线事件就丢了摘要。
     */
    private val offlineEvents = AtomicInteger()
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 已入库但还没挂上片段的“检测中”事件；检测器在分析/录音线程触发，用并发队列避免跨线程改列表 */
    private val pendingEvents = ConcurrentLinkedQueue<Long>()

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null

    /** 远程手电（PRD FR-16）需要 CameraControl；只在绑定期间有效，销毁后必须清空 */
    private var boundCamera: Camera? = null

    /**
     * 相机可用性监听（PRD §10.5 能力降级）：相机是独占资源，被其他应用抢走时
     * CameraX 只会结束录制而不会报错，必须靠系统回调才能把“当前无画面”说清楚，
     * 并在对方释放后主动重绑，否则设备会长期停在“在线但没画面”。
     */
    private var cameraManager: CameraManager? = null
    private var cameraWatch: CameraManager.AvailabilityCallback? = null
    private var watchedCameraId: String? = null
    private var cameraBusy = false

    /** 绑定重试计数：释放时间不可知，有限次重试足以覆盖切前台/解锁等常见抢占场景 */
    private var bindRetries = 0
    private val retryBind = Runnable { if (running && !stopping) bindCamera() }

    /**
     * 重绑期间禁止 Finalize 回调自动开下一片：旧片是被主动停掉的，
     * 若还按“正常收片”流程续写，新片会落在即将解绑的旧 capture 上并随即失败。
     */
    private var rebinding = false

    /**
     * 每次绑定相机递增。旧 capture 的 Finalize 回调可能在新录制已经启动后才到达，
     * 若还按“正常收片”续写，同一个 Recorder 会被二次 start 抛 IllegalStateException（实测崩溃）。
     */
    private var bindGeneration = 0

    /** 最近一次绑定成功时刻：自身 unbindAll→bind 也会收到可用性回调，用它过滤自己造成的噪音 */
    private var lastBindAtMs = 0L

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

    /**
     * 电源守护（PRD FR-13）：每 30 秒检查一次电量、温度与“未充电时长”。
     * 与心跳同频既省唤醒，也让观看端看到的心跳间隔与降载判定是同一时刻的结论。
     */
    private val powerTimer = Handler(Looper.getMainLooper())
    private val powerTick = Runnable { checkPower() }

    /** 本次值守中“开始未充电”的时刻；充电后清零，用来算 PRD O2 的 2 小时限制 */
    private var unpluggedSince = 0L
    private var lastThrottled = false

    /** 远程动作提示的恢复计时：10 秒后通知要回到常规文案，否则会一直停在“正在对讲” */
    private val remoteLabelTimer = Handler(Looper.getMainLooper())
    private val restoreRemoteLabel = Runnable { updateNotification(getString(R.string.capture_running)) }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
            // 观看端请求暂停：与用户本机停止走同一条停采流程，只是要额外留下"被远程暂停"的状态与记录
            ACTION_PAUSE -> pauseByRemote()
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
        // 标记仍是“值守中”说明上次是被杀或设备重启：这就是被杀检测的证据，先记账再继续采集
        if (GuardState.isActive(this)) {
            val count = GuardState.crashCount(this) + 1
            GuardState.setCrashCount(this, count)
            if (count >= GuardState.CRASH_ADVICE_THRESHOLD) {
                notifier.showGuardNote(
                    getString(R.string.power_crash_title),
                    getString(R.string.power_crash_body, count)
                )
            }
        }
        markGuardActive(true)
        sessionStartMs = System.currentTimeMillis()
        unpluggedSince = 0L
        lastThrottled = false
        // 心跳与降载标记只活在内存里：新一轮值守必须从干净状态起步，否则重启后会沿用上一次的降载状态
        GuardRuntime.reset()
        GuardRuntime.beat()
        // 本机重新开始值守即视为解除远程暂停，同时把设置页保存的遮罩重新注入推流
        GuardState.setPaused(this, false)
        PrivacyGuard.applyStreamMask(this)
        schedulePowerTick()
        // 上一次进程被杀会留下未收敛的分片，先修复再开新片，避免索引里出现打不开的文件。
        // MediaMetadataRetriever 逐个探测会阻塞主线程，放到独立线程；修复只处理旧分片，
        // 与新分片写入没有竞态。
        Thread({ repairInterruptedClips() }, "clip-repair").apply {
            isDaemon = true
            start()
        }
        // 启动即执行一次保留策略：进程可能已经很久没运行过，期间新录的内容可能已超出过期或配额
        StorageKeeper.enforce(this)
        startNetworkMonitor()
        updateNotification(getString(R.string.capture_starting))

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 绑定相机并起采集：首次启动与“相机被抢占后释放”都走这里。
     * 独立成函数而不是留在启动回调里，是因为重绑要复用同一套镜头选择、画质档位与降级逻辑，
     * 复制一份必然会随着两种场景逐渐走偏。
     */
    private fun bindCamera() {
        val cameraProvider = provider ?: return
        // 先收敛旧会话：相机重新绑定后旧录制无法继续，留着会让下一片 prepareRecording 直接抛异常
        rebinding = true
        bindGeneration++
        val generation = bindGeneration
        segmentTimer.removeCallbacks(stopCurrentSegment)
        segmentTimer.removeCallbacks(retryBind)
        val active = recording
        recording = null
        active?.stop()
        val primary = CameraCapabilities.primary(this)
        if (primary == null) {
            // 没有可用镜头就凑不出值守：如实告知并退出，不能让通知一直显示“采集中”骗用户
            updateNotification(getString(R.string.camera_missing))
            notifier.showGuardNote(getString(R.string.camera_missing_title), getString(R.string.camera_missing))
            running = false
            isRunning = false
            markGuardActive(false)
            return
        }
        watchCamera(primary.id)
        val capture = VideoCapture.withOutput(CameraCapabilities.recorder(this).build())
        videoCapture = capture
        // 检测前的算力探测（W26）：低端机降分辨率并隔帧送检，避免检测本身成为耗电主因
        val detection = DetectionProfile.of(this)
        // 运动检测复用同一路相机流；分辨率为档位决定，低端机降到 VGA 不影响成片
        val detector = MotionDetector(
            DETECTION_SENSITIVITY,
            MotionDetector.ALL_GRID,
            thumbnailDir
        ) { confidence, thumbnail ->
            onDetected(EventType.MOTION, R.string.detection_motion, confidence, thumbnail)
        }
        // 帧计数只服务降载与算力档位的隔帧判定；分析器是单线程回调，无需加锁。
        // 每次重绑重建分析器，计数器随之归零，避免沿用上一场会话的节拍
        var frameCount = 0
        val frameAnalyzer = ImageAnalysis.Analyzer { image ->
            // 降载档（PRD FR-13）关闭实时推流并隔帧分析：省电的代价必须落在“推流与画质”上，
            // 不能变成停止检测——否则用户会以为值守还在，实际整段时间都录不到事件
            frameCount++
            val throttled = GuardRuntime.throttled
            if (!throttled) {
                LanFrameHub.publish(image)
                // RTSP/NVR 拉流复用同一帧：降载档一起停推，省电的代价落在推流而不是检测上
                H264Streamer.publish(image)
            }
            val skipForThrottle = throttled && frameCount % THROTTLE_FRAME_SKIP != 0
            val skipForCapability = frameCount % detection.detectEveryNFrames != 0
            if (skipForThrottle || skipForCapability) {
                // 跳过的帧必须自己关闭，漏掉一次相机缓冲就会卡死整条分析管线
                image.close()
            } else {
                // 先发布到实时流再交给检测器；检测器负责 image.close()，
                // 这里不能提前关闭，否则 JPEG 编码与运动检测都会拿到失效缓冲
                detector.analyze(image)
            }
        }
        analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(DetectionProfile.analysisSelector(detection))
            .build()
            .also { it.setAnalyzer(analysisExecutor, frameAnalyzer) }
        try {
            cameraProvider.unbindAll()
            boundCamera = cameraProvider.bindToLifecycle(
                cameraOwner,
                CameraCapabilities.selector(primary),
                capture,
                analysis
            )
            // 手电由 Deterrent 计时关闭，这里只提供开关；没有闪光灯的设备由 enableTorch 自行失败
            Deterrent.torchSwitch = { on ->
                val control = boundCamera?.cameraControl
                if (control == null) {
                    false
                } else {
                    control.enableTorch(on)
                    true
                }
            }
            bindRetries = 0
            rebinding = false
            // 绑定成功前的解绑/重试都会产生可用性回调，记下时刻让回调按“自身噪音”忽略
            lastBindAtMs = System.currentTimeMillis()
            // 只有前置镜头的设备要把降级结果说出来，否则用户会以为“后置坏了”
            updateNotification(
                getString(
                    if (primary.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        R.string.capture_running_front
                    } else {
                        R.string.capture_running
                    }
                )
            )
            if (soundDetector == null) startSoundDetector()
            startNextSegment(generation)
            // 相机绑定成功后才值得对外提供服务：否则观看端只能拿到一个“在线但永远无画面”的设备
            startLanServer()
        } catch (e: Exception) {
            // 相机被其他应用占用或设备无可用录制尺寸：先有限重试，再如实报错退出
            retryBind(e)
        }
    }

    /**
     * 相机被抢占时先重试而不是立刻停止：用户常见的解锁、系统相机、扫码等抢占
     * 几秒内就会释放，直接停止值守会把偶发抢占变成一次“设备离线”。
     */
    private fun retryBind(error: Exception?) {
        if (bindRetries < BIND_RETRY_LIMIT) {
            bindRetries++
            updateNotification(getString(R.string.camera_retrying, bindRetries, BIND_RETRY_LIMIT))
            segmentTimer.removeCallbacks(retryBind)
            segmentTimer.postDelayed(retryBind, BIND_RETRY_DELAY_MS)
            return
        }
        // 让用户看到原因，而不是一直显示“采集中”
        updateNotification(getString(R.string.camera_open_failed, error?.message ?: ""))
        running = false
        isRunning = false
        // 相机打不开就不能算“值守中”，否则系统重建服务时会反复重试失败
        markGuardActive(false)
    }

    private fun watchCamera(cameraId: String) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        watchedCameraId = cameraId
        if (cameraWatch != null) return
        cameraManager = manager
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(id: String) {
                if (id != watchedCameraId) return
                // 我们自己 unbindAll 和刚绑定成功的瞬态也会收到“不可用”，那不是被别人抢占；
                // 误判会把通知停成“被占用”，并且触发一次多余重绑
                if (rebinding || System.currentTimeMillis() - lastBindAtMs < CAMERA_SETTLE_MS) return
                if (cameraBusy) return
                cameraBusy = true
                updateNotification(getString(R.string.camera_busy))
            }

            override fun onCameraAvailable(id: String) {
                if (id != watchedCameraId) return
                // 与上面同源的自噪：刚完成绑定就回调“可用”，此时重绑只会多停一次录制
                if (System.currentTimeMillis() - lastBindAtMs < CAMERA_SETTLE_MS) return
                if (!cameraBusy) return
                cameraBusy = false
                // 抢用方释放后必须把通知改回常规文案，否则设备在正常采集却一直显示“被占用”
                updateNotification(getString(R.string.capture_running))
                // 对方释放后 CameraX 不会自己恢复已断开的会话，不重绑就会静默无画面
                if (running && !stopping && provider != null) bindCamera()
            }
        }
        cameraWatch = callback
        runCatching { manager.registerAvailabilityCallback(ContextCompat.getMainExecutor(this), callback) }
    }

    private fun unwatchCamera() {
        val manager = cameraManager
        val callback = cameraWatch
        cameraManager = null
        cameraWatch = null
        watchedCameraId = null
        cameraBusy = false
        if (manager != null && callback != null) {
            runCatching { manager.unregisterAvailabilityCallback(callback) }
        }
    }

    /**
     * 30 秒一段：分片越短，进程被杀时损失越少；分片之间先落 .pending 标记，
     * Finalize 成功才删除，这样启动时能准确识别“上次没录完”的文件。
     */
    private fun startNextSegment(generation: Int = bindGeneration) {
        if (!running || stopping) return
        // 换绑后到达的旧片收尾不能续写：同一个 Recorder 上二次 start 会抛“已有录制进行中”
        if (generation != bindGeneration) return
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

        // 磁盘写满时连 .pending 标记都写不进去；这条路径此前没有异常处理，
        // ENOSPC 会被直接抛到主线程把前台服务打崩（真机 10-31 的崩溃栈即在此处）。
        // 存储满时重试没有意义，如实提示并停止采集，避免用户以为还在录。
        val prepared = runCatching {
            val options = FileOutputOptions.Builder(output).build()
            var pendingRecording = capture.output.prepareRecording(this, options)
            if (hasAudioPermission()) pendingRecording = pendingRecording.withAudioEnabled()
            pending.writeText(output.name)
            pendingRecording
        }.getOrElse { e ->
            Log.w(TAG, "segment unavailable: ${e.message}")
            pending.delete()
            updateNotification(getString(R.string.segment_storage_full))
            notifier.showGuardNote(
                getString(R.string.segment_storage_full),
                getString(R.string.segment_storage_full_hint)
            )
            stopCapture()
            return
        }

        recording = runCatching {
            prepared.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event !is VideoRecordEvent.Finalize) return@start
                // 分片可能因为相机异常提前结束，必须清掉残留计时，否则它会误停刚开起的下一片
                segmentTimer.removeCallbacks(stopCurrentSegment)
                pending.delete()
                // 换绑后迟到的旧片 Finalize 只做文件收尾，既不能挂事件也不能再开下一片
                if (generation != bindGeneration) return@start
                recording = null
                if (event.hasError()) {
                    updateNotification(getString(R.string.segment_error, event.error))
                    // 写坏的分片不能挂给事件：保持“检测中”，回看页会如实提示文件不可用
                    pendingEvents.clear()
                } else {
                    attachPendingEvents(output, segmentStartMs, previousClip)
                    // 保住刚收敛的片子，下一片的事件若发生在片头 5 秒内要靠它补前置画面
                    previousClip = output
                }
                // 停止守值时最后一个分片要靠这次 Finalize 收敛，收敛后才能安全导出；
                // 重绑流程里的分片既不续写也不触发收尾，交给新的 bindCamera 重新起片
                if (stopping) finishSession() else if (!rebinding) startNextSegment(generation)
            }
        }.getOrElse { e ->
            // 相机刚被抢走时 start 会被拒；放任异常会让前台服务拖死整个进程，本场值守直接丢失
            Log.w(TAG, "startNextSegment rejected: ${e.message}")
            pending.delete()
            segmentTimer.postDelayed({ startNextSegment(generation) }, SEGMENT_RETRY_DELAY_MS)
            null
        }
        // 已安排重试时不能把收片计时挂在不存在的录制上
        if (recording == null) return
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
            // 离线只累计，恢复后补一条摘要；不在这里逐条重发，避免恢复瞬间刷屏。
            // 必须在这里 return：缺少返回会继续走到 showEvent，断网时照样逐条弹出事件通知，
            // 恢复后再补一条摘要，用户会看到同一条事件被提醒两次
            offlineEvents.incrementAndGet()
            return
        }
        // 检测时段外仍记录事件，只是不打扰（PRD FR-07：时段外可继续录制但不推送）
        if (!AlertPolicy.isDetectionWindow(this)) return
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
                val queued = offlineEvents.getAndSet(0)
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

    /**
     * 远程暂停（PRD FR-14）：先停采集再落"已暂停"标记，顺序反过来会被 stopCapture 的清理动作覆盖。
     * 暂停后不自动恢复：恢复必须由摄像头端本机确认，否则远程端可以无限期地反复开关采集。
     */
    private fun pauseByRemote() {
        if (!running) return
        AuditLog.record(this, AuditLog.REMOTE_PAUSE, "观看端请求暂停采集")
        stopCapture()
        GuardState.setPaused(this, true)
        updateNotification(getString(R.string.pause_remote_notice))
        notifier.showGuardNote(getString(R.string.pause_remote_title), getString(R.string.pause_remote_notice))
    }

    private fun stopCapture() {
        stopping = true
        running = false
        isRunning = false
        // 用户主动停止必须清除恢复标记：进程再被重建也不能把值守自己拉起来
        markGuardActive(false)
        // 停掉电源巡检再清运行态：留着定时器会让已经收尾的服务继续被唤醒
        powerTimer.removeCallbacks(powerTick)
        segmentTimer.removeCallbacks(retryBind)
        rebinding = false
        unwatchCamera()
        GuardRuntime.reset()
        // 不关闭局域网监听：设备要在“在线·待机”下继续可发现、可配对，
        // 直播则因没有新帧自然断开（LanFrameHub 长时间无帧）
        // 停止即释放硬件编码器：不释放会一直占着编解码资源，下次值守重新建实例还可能失败
        H264Streamer.stop()
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
     * 电源巡检（PRD FR-13）：同一时刻刷新心跳，观看端据此判断“进程还在但采集卡住”。
     * 判定与心跳必须同源，否则会出现“心跳新、状态旧”的自相矛盾读数。
     */
    private fun schedulePowerTick() {
        powerTimer.removeCallbacks(powerTick)
        if (!running) return
        powerTimer.postDelayed(powerTick, POWER_CHECK_INTERVAL_MS)
    }

    private fun checkPower() {
        if (!running || stopping) return
        GuardRuntime.beat()
        val snapshot = PowerGuard.snapshot(this)
        val now = System.currentTimeMillis()
        // 未充电计时从本场值守起点算：在此之前没人记账，从会话起点起步才符合“最多跑 2 小时”的承诺
        if (snapshot.charging) {
            unpluggedSince = 0L
        } else if (unpluggedSince == 0L) {
            unpluggedSince = if (sessionStartMs > 0L) sessionStartMs else now
        }
        val overTime = PowerGuard.chargeOnly(this) &&
            unpluggedSince > 0L && now - unpluggedSince >= PowerGuard.UNPLUGGED_LIMIT_MS
        val action = PowerGuard.evaluate(snapshot)
        val reason = when {
            action == PowerGuard.Action.STOP -> PowerGuard.reason(snapshot, action)
            // 限时是用户设定的策略，优先级低于设备安全：热或电量已经要停时不再改写停止原因
            overTime -> PowerGuard.Reason.TIME_LIMIT
            else -> PowerGuard.Reason.NONE
        }
        if (reason != PowerGuard.Reason.NONE) {
            stopForPower(reason, snapshot)
            return
        }
        applyThrottle(action == PowerGuard.Action.THROTTLE, snapshot)
        schedulePowerTick()
    }

    /**
     * 降载只在状态切换时提示：每 30 秒刷一次通知会让必须常驻的通知变成骚扰源。
     */
    private fun applyThrottle(on: Boolean, snapshot: PowerGuard.Snapshot) {
        GuardRuntime.throttled = on
        if (on == lastThrottled) return
        lastThrottled = on
        if (on) {
            val text = powerText(PowerGuard.reason(snapshot, PowerGuard.Action.THROTTLE), snapshot)
            updateNotification(text)
            notifier.showGuardNote(getString(R.string.power_note_title), text)
        } else {
            updateNotification(getString(R.string.capture_running))
        }
    }

    private fun powerText(reason: PowerGuard.Reason, snapshot: PowerGuard.Snapshot): String = when (reason) {
        PowerGuard.Reason.HEAT -> getString(R.string.power_throttle_heat)
        PowerGuard.Reason.LOW_BATTERY -> getString(R.string.power_throttle_low, snapshot.battery)
        else -> getString(R.string.capture_running)
    }

    /**
     * 自动停止必须留下可追溯的原因：用户往往人不在场，只有通知能解释“为什么停了”。
     * 先在通知里写明原因再停服务，顺序反过来通知会被停止流程覆盖成“正在保存”。
     */
    private fun stopForPower(reason: PowerGuard.Reason, snapshot: PowerGuard.Snapshot) {
        val text = when (reason) {
            PowerGuard.Reason.HEAT_STOP -> getString(R.string.power_stop_heat)
            PowerGuard.Reason.TIME_LIMIT -> getString(R.string.power_stop_time)
            else -> getString(R.string.power_stop_low, snapshot.battery)
        }
        updateNotification(text)
        notifier.showGuardNote(getString(R.string.power_stop_title), text)
        stopCapture()
        // stopCapture 会清空运行态，停止原因要放在它之后再写，否则观看端读不到原因
        GuardRuntime.stoppedReason = reason
    }

    /**
     * 为什么要落盘而不是只靠 running 字段：START_STICKY 重建发生在全新服务实例里，
     * 内存状态早已清零，只有持久化标记才能区分“系统杀进程”和“用户点了停止”。
     */
    private fun markGuardActive(active: Boolean) = GuardState.setActive(this, active)

    private fun wasGuardActive(): Boolean = GuardState.isActive(this)

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

    /**
     * 对讲/警笛/手电都由观看端触发，而设备往往放在别的房间：
     * 摄像头端唯一的常驻可见面就是这条前台通知，不刷新它，用户事后才知道设备被动过。
     */
    private fun flashRemoteActivity(text: String) {
        remoteLabelTimer.removeCallbacks(restoreRemoteLabel)
        updateNotification(text)
        remoteLabelTimer.postDelayed(restoreRemoteLabel, REMOTE_LABEL_MS)
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
        segmentTimer.removeCallbacks(retryBind)
        powerTimer.removeCallbacks(powerTick)
        remoteLabelTimer.removeCallbacks(restoreRemoteLabel)
        if (instance === this) instance = null
        unwatchCamera()
        stopNetworkMonitor()
        // 威慑状态绑定在采集上：服务一停，手电必须关掉、警笛音调与对讲播放器都要释放，
        // 否则下一次启动会拿到一个还占着音频设备或闪光灯的残留实例
        Deterrent.release()
        TalkPlayer.release()
        boundCamera = null
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
        const val ACTION_PAUSE = "com.quietsentry.app.action.PAUSE_BY_REMOTE"
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

        /** 电源巡检间隔（PRD FR-13 要求每 30 秒一次心跳） */
        private const val POWER_CHECK_INTERVAL_MS = 30_000L

        /** 降载档隔一帧分析：帧率减半仍能捕捉运动起始，又实实在在省下一半推理电量 */
        private const val THROTTLE_FRAME_SKIP = 2

        /** 远程动作提示在通知栏停留的时长：与警笛/手电的 10 秒自动停止对齐 */
        private const val REMOTE_LABEL_MS = 10_000L

        /** 相机被抢占后的重试次数与间隔：3 次 × 3 秒覆盖解锁、扫码等短暂占用 */
        private const val BIND_RETRY_LIMIT = 3
        private const val BIND_RETRY_DELAY_MS = 3_000L

        /** 自身 unbindAll→bind 引发的可用性回调在此窗口内视为噪音，不提示“被占用”也不重绑 */
        private const val CAMERA_SETTLE_MS = 2_000L

        /** 录制被 CameraX 拒绝后的重试间隔：多为一瞬间的相机切换竞态 */
        private const val SEGMENT_RETRY_DELAY_MS = 3_000L

        /** 供界面判断值守是否在运行；相机是独占资源，值守与黑屏模式不能同时绑定。仅由本服务写入。 */
        @Volatile
        var isRunning = false

        /** 只用于把“他人触发的动作”推到前台通知；不暴露实例方法，避免界面绕过 isRunning 直接操作采集 */
        @Volatile
        private var instance: CaptureService? = null

        fun showRemoteActivity(text: String) {
            instance?.flashRemoteActivity(text)
        }
    }
}
