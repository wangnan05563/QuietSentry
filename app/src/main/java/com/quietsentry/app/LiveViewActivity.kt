package com.quietsentry.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * 观看端实时画面（PRD FR-08）。
 *
 * 拉流做成“读一帧、显示一帧”的循环而不是把视频交给 MediaPlayer：
 * 相机端提供的是 MJPEG，逐帧还给原生控件才能做到全屏、截图与画质切换，
 * 也不会为了一个新协议再引入播放器依赖。
 */
class LiveViewActivity : AppCompatActivity() {

    private lateinit var image: ImageView
    private lateinit var statusText: TextView
    private lateinit var counterText: TextView
    private lateinit var qualityPills: List<TextView>
    private lateinit var talkButton: Button
    private lateinit var sirenButton: Button
    private lateinit var torchButton: Button

    private lateinit var device: DeviceRegistry.Device
    private lateinit var streamer: LiveStreamer
    private var quality = QUALITY_AUTO

    /**
     * 画质切换过渡态（PRD 8 风险表“切换不清帧”）：切换会触发一轮 CONNECTING，
     * 不加这个标志的话兜底快照会立刻盖掉用户正在看的最后一帧。
     * 连接成功或确认离线时复位，保证真正的断线兜底不受影响。
     */
    private var switchingQuality = false

    /** 触发中的威慑动作（T04052）：非空时按钮变“停止”并倒计时，仅 UI 语义，无并发写者 */
    private var activeDeterrent: String? = null
    private var deterrentDeadlineMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val deterrentTick = object : Runnable {
        override fun run() {
            val remainingSec = (deterrentDeadlineMs - System.currentTimeMillis() + 999) / 1000
            if (remainingSec <= 0 || activeDeterrent == null) {
                endDeterrentUi()
                return
            }
            activeDeterrentButton()?.text = getString(R.string.live_deter_stop, remainingSec)
            // 250ms 粒度让秒数跳变发生在 0.25s 内，比整秒轮询更接近服务端的真实剩余时间
            uiHandler.postDelayed(this, 250)
        }
    }

    /** 只在按住期间存在；松手即置空，用空值作为“当前没在录”的唯一判据 */
    private var talk: TalkRecorder? = null

    // 权限回调可能发生在 onStop 之后：授予后不要自行开录，交给用户重新按住
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(getString(R.string.live_talk_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = DeviceRegistry(this).all()
            .firstOrNull { it.id == intent.getStringExtra(EXTRA_DEVICE_ID) }
        if (target == null) {
            Toast.makeText(this, R.string.live_no_device, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        device = target

        setContentView(R.layout.activity_live_view)
        image = findViewById(R.id.live_image)
        statusText = findViewById(R.id.live_status)
        counterText = findViewById(R.id.live_counter)
        // 三档 pill 用同一份点击逻辑：映射表把 View 直接绑到目标码流参数，避免逐个写监听器
        qualityPills = listOf(
            findViewById(R.id.live_quality_auto),
            findViewById(R.id.live_quality_smooth),
            findViewById(R.id.live_quality_hd),
        )
        qualityPills.forEachIndexed { index, pill ->
            pill.setOnClickListener { selectQuality(QUALITY_LEVELS[index]) }
        }
        renderQualityPills()
        findViewById<TextView>(R.id.live_title).text = device.name

        findViewById<Button>(R.id.live_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.live_snapshot).setOnClickListener { snapshot() }
        findViewById<Button>(R.id.live_pause).setOnClickListener { confirmPause() }
        // 触发态下同一按钮变成“停止”：点击语义由 activeDeterrent 决定，不在监听器里重复判断
        sirenButton = findViewById(R.id.live_siren)
        torchButton = findViewById(R.id.live_torch)
        sirenButton.setOnClickListener {
            if (activeDeterrent == DETERRENT_SIREN) requestDeterrentStop(DETERRENT_SIREN)
            else confirmDeterrent(siren = true)
        }
        torchButton.setOnClickListener {
            if (activeDeterrent == DETERRENT_TORCH) requestDeterrentStop(DETERRENT_TORCH)
            else confirmDeterrent(siren = false)
        }

        // 对讲必须“按住说话”：默认不常开麦克风（PRD FR-16），因此用触摸事件而不是点击
        talkButton = findViewById(R.id.live_talk)
        talkButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTalk()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTalk()
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        // 看实时画面时不应因为锁屏而中断观察，也不该让系统栏占掉画面
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
        }
        statusText.text = getString(R.string.live_state_connecting)

        // 横屏时刘海在屏幕左/右短边：系统栏虽然已隐藏，但挖孔区仍会盖住顶部“关闭”与底部按钮。
        // 只避让 displayCutout 一类，不用整屏 systemBars 内边距，否则画面会被重新压回安全区、失去全屏意义。
        val topBar = findViewById<View>(R.id.live_top_bar)
        val bottomBar = findViewById<View>(R.id.live_bottom_bar)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(left = cutout.left, right = cutout.right)
            bottomBar.updatePadding(left = cutout.left, right = cutout.right)
            insets
        }

        streamer = LiveStreamer(
            device.host, device.port, device.token,
            object : LiveStreamer.Listener {
                override fun onState(state: LiveStreamer.State) = onUi { renderState(state) }

                override fun onFrame(frame: Bitmap) = onUi { image.setImageBitmap(frame) }

                // 重连次数跟在“信号中断”后面显示：只报状态不报进度，用户会以为卡死了
                override fun onRetryWait(attempt: Int) = onUi {
                    counterText.text = getString(R.string.live_retry_count, attempt)
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        // 设备不存在时 onCreate 已经 finish，但 onStart 仍会被调用一次，这里必须避免碰未初始化的字段
        if (::streamer.isInitialized) streamer.start(quality)
    }

    override fun onStop() {
        // 退到后台就停流：为“可能还要看”而一直解码耗电不值得，回前台会立即重新连上
        if (::streamer.isInitialized) streamer.stop()
        // 对讲同样不能留到后台：麦克风一旦脱离用户手指，就等于在不知情的情况下持续录音
        stopTalk()
        // 倒计时只服务本页 UI：离开页面后回调必须停掉，否则会向已销毁的按钮写文案
        if (activeDeterrent != null) endDeterrentUi()
        super.onStop()
    }

    override fun onDestroy() {
        stopTalk()
        if (::streamer.isInitialized) streamer.release()
        super.onDestroy()
    }

    /** 选中态集中渲染：onCreate 的初始档位与切换后的高亮走同一条路径 */
    private fun renderQualityPills() {
        qualityPills.forEachIndexed { index, pill ->
            pill.isSelected = QUALITY_LEVELS[index] == quality
        }
    }

    /** 点当前档位不重连：重连会带来一两秒画面停顿，重复点击不应付出这个代价 */
    private fun selectQuality(target: String) {
        if (target == quality) return
        quality = target
        renderQualityPills()
        switchingQuality = true
        // 换画质只能换一条流：服务端按连接决定码流，不断开就没法重新协商。
        // ImageView 上最后一帧不会被清掉，重连成功前继续显示旧码流的画面作兜底
        streamer.stop()
        streamer.start(quality)
    }

    private fun renderState(state: LiveStreamer.State) {
        when (state) {
            LiveStreamer.State.CONNECTING -> {
                statusText.text = ""
                if (switchingQuality) {
                    // 切画质过渡：保留最后一帧只提示切换中，不拉兜底快照
                    counterText.setText(R.string.live_quality_switching)
                } else {
                    counterText.setText(R.string.live_state_connecting)
                    showFallbackSnapshot()
                }
            }
            LiveStreamer.State.CONNECTED -> {
                switchingQuality = false
                statusText.text = ""
                counterText.setText(R.string.live_state_connected)
            }
            LiveStreamer.State.RETRY -> {
                statusText.setText(R.string.live_status_retry)
                // 重连次数由 onRetryWait 异步补上，这里清空避免残留上一轮次数
                if (switchingQuality) {
                    counterText.setText(R.string.live_quality_switching)
                } else {
                    counterText.text = ""
                    showFallbackSnapshot()
                }
            }
            LiveStreamer.State.UNAUTHORIZED -> {
                // LiveStreamer 收到 401 后已停止重连循环，这里给出重新配对出路而不是干等
                counterText.text = ""
                promptRepair()
            }
            LiveStreamer.State.BUSY -> {
                statusText.text = getString(R.string.live_status_busy, MAX_VIEWERS)
                counterText.text = ""
            }
            LiveStreamer.State.OFFLINE -> {
                // 切换失败也归入离线：复位过渡态，否则设备恢复后的断线兜底会被一直抑制
                switchingQuality = false
                statusText.setText(R.string.live_status_offline)
                counterText.text = ""
                showFallbackSnapshot()
            }
        }
    }

    /** 兜底快照节流：重连循环每 3 秒就会触发一次状态回调，不节流会一直打快照请求 */
    private var lastFallbackAtMs = 0L

    /**
     * 首帧前的防白屏兜底（PRD 3.2）：把最近快照铺进画面并标注最后在线时间。
     * 只覆盖与直播帧同一个 ImageView，首帧到达后 onFrame 自然把它替换掉，无需显式清理。
     */
    private fun showFallbackSnapshot() {
        val now = System.currentTimeMillis()
        if (now - lastFallbackAtMs < FALLBACK_THROTTLE_MS) return
        lastFallbackAtMs = now
        val host = device.host
        val port = device.port
        val token = device.token
        Thread {
            val result = DeviceHttp.snapshot(host, port, token) ?: return@Thread
            val bitmap = BitmapFactory.decodeByteArray(result.jpeg, 0, result.jpeg.size) ?: return@Thread
            onUi {
                image.setImageBitmap(bitmap)
                if (result.atMs > 0) {
                    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(result.atMs))
                    statusText.text = getString(R.string.live_last_seen, time)
                }
            }
        }.start()
    }

    /**
     * 远程暂停（PRD FR-14）：暂停不可由本机恢复，因此必须二次确认。
     * 请求在 IO 线程发出，成功后只是把服务端状态改掉，画面会在数秒内自然断开。
     */
    private fun confirmPause() {
        AlertDialog.Builder(this)
            .setTitle(R.string.live_pause_title)
            .setMessage(R.string.live_pause_message)
            .setPositiveButton(R.string.live_pause_ok) { _, _ -> requestPause() }
            .setNegativeButton(R.string.live_pause_cancel, null)
            .show()
    }

    private fun requestPause() {
        val host = device.host
        val port = device.port
        val token = device.token
        Thread {
            val ok = DeviceHttp.pause(host, port, token)
            onUi {
                Toast.makeText(
                    this,
                    if (ok) R.string.live_pause_done else R.string.live_pause_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun onUi(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    /**
     * 按住说话：每次按下都新建一个采集器，松开即销毁。
     * 复用同一个实例会让“上次失败后残留的线程”混进下一次对讲，且权限被撤销时难以重新判断。
     */
    private fun startTalk() {
        if (talk != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 首次按下才申请权限：不在进入页面时弹窗，避免打断用户看画面
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val host = device.host
        val port = device.port
        val token = device.token
        val recorder = TalkRecorder(
            upload = { pcm -> DeviceHttp.talk(host, port, token, pcm) },
            onStopped = { failed ->
                // 服务端不接收（离线、已停止值守）时上传必然失败，这里必须告诉用户话没送出去
                if (failed) onUi { toast(getString(R.string.live_talk_failed)) }
            },
        )
        if (!recorder.start(this)) {
            toast(getString(R.string.live_talk_unavailable))
            return
        }
        talk = recorder
        talkButton.setText(R.string.live_talk_active)
    }

    private fun stopTalk() {
        val recorder = talk ?: return
        talk = null
        recorder.stop()
        if (::talkButton.isInitialized) talkButton.setText(R.string.live_talk)
    }

    /** 警笛与手电都会在摄像头端发出声音/光，误触代价高，因此统一走二次确认（PRD FR-16） */
    private fun confirmDeterrent(siren: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (siren) R.string.live_siren_title else R.string.live_torch_title)
            .setMessage(if (siren) R.string.live_siren_message else R.string.live_torch_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> requestDeterrent(siren) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestDeterrent(siren: Boolean) {
        val host = device.host
        val port = device.port
        val token = device.token
        Thread {
            val result =
                if (siren) DeviceHttp.siren(host, port, token) else DeviceHttp.torch(host, port, token)
            onUi {
                showDeterrentResult(siren, result)
            }
        }.start()
    }

    /**
     * 失败原因必须逐条翻译：冷却、夜间禁用、访客无权限、未在值守都是“没有执行”，
     * 一句笼统的“失败”会让用户以为设备出了问题，反而重复触发。
     */
    private fun showDeterrentResult(siren: Boolean, result: DeviceHttp.ControlResult) {
        if (result.ok) {
            // 以本地时钟起倒计时：与服务端 DURATION_MS 同源（10 秒），秒级误差可接受
            beginDeterrentCountdown(if (siren) DETERRENT_SIREN else DETERRENT_TORCH)
            return
        }
        val text = when {
            result.error == "cooldown" -> getString(R.string.live_deter_cooldown, result.retryAfterSec)
            result.error == "night_disabled" -> getString(R.string.live_deter_night)
            result.error == "forbidden" || result.error == "unauthorized" ->
                getString(R.string.live_deter_forbidden)
            result.error == "not_running" -> getString(R.string.live_deter_not_running)
            else -> getString(R.string.live_deter_failed)
        }
        toast(text)
    }

    private fun activeDeterrentButton(): Button? = when (activeDeterrent) {
        DETERRENT_SIREN -> if (::sirenButton.isInitialized) sirenButton else null
        DETERRENT_TORCH -> if (::torchButton.isInitialized) torchButton else null
        else -> null
    }

    private fun beginDeterrentCountdown(which: String) {
        activeDeterrent = which
        deterrentDeadlineMs = System.currentTimeMillis() + Deterrent.DURATION_MS
        uiHandler.removeCallbacks(deterrentTick)
        deterrentTick.run()
    }

    private fun endDeterrentUi() {
        uiHandler.removeCallbacks(deterrentTick)
        val which = activeDeterrent ?: return
        activeDeterrent = null
        // 复位被倒计时改写的按钮文案；按钮引用在 onCreate 前可能未初始化，按类型兜底
        if (::sirenButton.isInitialized && which == DETERRENT_SIREN) {
            sirenButton.setText(R.string.live_siren)
        }
        if (::torchButton.isInitialized && which == DETERRENT_TORCH) {
            torchButton.setText(R.string.live_torch)
        }
    }

    /**
     * 触发态点击“停止”：UI 先行复位，请求在 IO 线程发出后不弹提示——
     * 停止失败的唯一常见原因是设备已离线，而动作本身 10 秒内必然自然结束，兜底已存在。
     */
    private fun requestDeterrentStop(which: String) {
        endDeterrentUi()
        val host = device.host
        val port = device.port
        val token = device.token
        Thread {
            if (which == DETERRENT_SIREN) DeviceHttp.sirenStop(host, port, token)
            else DeviceHttp.torchStop(host, port, token)
        }.start()
    }

    /** PRD 4.3：令牌失效不再留在画面页反复重试，弹一次说明并给出扫码出路 */
    private fun promptRepair() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.live_status_unauthorized)
            .setMessage(R.string.live_repair_message)
            .setPositiveButton(R.string.live_repair_action) { _, _ ->
                startActivity(Intent(this, ScanPairActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.live_repair_close) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun snapshot() {
        val frame = streamer.currentFrame()
        if (frame == null) {
            Toast.makeText(this, R.string.live_snapshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = GalleryExporter.saveImage(this, frame)
        Toast.makeText(
            this,
            if (ok) R.string.live_snapshot_saved else R.string.live_snapshot_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        // 三档与 UI 顺序一一对应：“流畅”就是子码流（服务端 quality=sub 走 main=false），
        // “高清”是主码流，“自动”从主码流起、写不动由服务端自行降级
        private val QUALITY_LEVELS = arrayOf(QUALITY_AUTO, QUALITY_SMOOTH, QUALITY_MAIN)
        private const val QUALITY_AUTO = "auto"
        private const val QUALITY_SMOOTH = "sub"
        private const val QUALITY_MAIN = "main"
        // 触发态按钮标识：与服务端 /api/{siren,torch}/stop 的目标一一对应
        private const val DETERRENT_SIREN = "siren"
        private const val DETERRENT_TORCH = "torch"
        // 服务端 MAX_VIEWERS 固定为 5，这里只用于把 429 翻译成人话
        private const val MAX_VIEWERS = 5
        // 兜底快照节流窗口：比重连间隔长一个量级，离线期间不至于每轮重连都拉一次图
        private const val FALLBACK_THROTTLE_MS = 10_000L
    }
}
