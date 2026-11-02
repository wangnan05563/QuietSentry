package com.quietsentry.app

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
    private lateinit var qualityButton: Button
    private lateinit var talkButton: Button

    private lateinit var device: DeviceRegistry.Device
    private lateinit var streamer: LiveStreamer
    private var quality = QUALITY_AUTO

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
        qualityButton = findViewById(R.id.live_quality)
        findViewById<TextView>(R.id.live_title).text = device.name

        findViewById<Button>(R.id.live_back).setOnClickListener { finish() }
        qualityButton.setOnClickListener { toggleQuality() }
        findViewById<Button>(R.id.live_snapshot).setOnClickListener { snapshot() }
        findViewById<Button>(R.id.live_pause).setOnClickListener { confirmPause() }
        findViewById<Button>(R.id.live_siren).setOnClickListener { confirmDeterrent(siren = true) }
        findViewById<Button>(R.id.live_torch).setOnClickListener { confirmDeterrent(siren = false) }

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
        super.onStop()
    }

    override fun onDestroy() {
        stopTalk()
        if (::streamer.isInitialized) streamer.release()
        super.onDestroy()
    }

    private fun toggleQuality() {
        quality = if (quality == QUALITY_AUTO) QUALITY_MAIN else QUALITY_AUTO
        qualityButton.setText(
            if (quality == QUALITY_AUTO) R.string.live_quality_auto else R.string.live_quality_main
        )
        // 换画质只能换一条流：服务端按连接决定码流，不断开就没法重新协商
        streamer.stop()
        streamer.start(quality)
    }

    private fun renderState(state: LiveStreamer.State) {
        when (state) {
            LiveStreamer.State.CONNECTING -> {
                statusText.text = ""
                counterText.setText(R.string.live_state_connecting)
                showFallbackSnapshot()
            }
            LiveStreamer.State.CONNECTED -> {
                statusText.text = ""
                counterText.setText(R.string.live_state_connected)
            }
            LiveStreamer.State.RETRY -> {
                statusText.setText(R.string.live_status_retry)
                counterText.text = ""
                showFallbackSnapshot()
            }
            LiveStreamer.State.UNAUTHORIZED -> {
                statusText.setText(R.string.live_status_unauthorized)
                counterText.text = ""
            }
            LiveStreamer.State.BUSY -> {
                statusText.text = getString(R.string.live_status_busy, MAX_VIEWERS)
                counterText.text = ""
            }
            LiveStreamer.State.OFFLINE -> {
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
            onUi { showDeterrentResult(result) }
        }.start()
    }

    /**
     * 失败原因必须逐条翻译：冷却、夜间禁用、访客无权限、未在值守都是“没有执行”，
     * 一句笼统的“失败”会让用户以为设备出了问题，反而重复触发。
     */
    private fun showDeterrentResult(result: DeviceHttp.ControlResult) {
        val text = when {
            result.ok -> getString(R.string.live_deter_done)
            result.error == "cooldown" -> getString(R.string.live_deter_cooldown, result.retryAfterSec)
            result.error == "night_disabled" -> getString(R.string.live_deter_night)
            result.error == "forbidden" || result.error == "unauthorized" ->
                getString(R.string.live_deter_forbidden)
            result.error == "not_running" -> getString(R.string.live_deter_not_running)
            else -> getString(R.string.live_deter_failed)
        }
        toast(text)
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
        private const val QUALITY_AUTO = "auto"
        private const val QUALITY_MAIN = "main"
        // 服务端 MAX_VIEWERS 固定为 5，这里只用于把 429 翻译成人话
        private const val MAX_VIEWERS = 5
        // 兜底快照节流窗口：比重连间隔长一个量级，离线期间不至于每轮重连都拉一次图
        private const val FALLBACK_THROTTLE_MS = 10_000L
    }
}
