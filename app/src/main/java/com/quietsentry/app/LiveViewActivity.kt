package com.quietsentry.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var device: DeviceRegistry.Device
    private lateinit var streamer: LiveStreamer
    private var quality = QUALITY_AUTO

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
        super.onStop()
    }

    override fun onDestroy() {
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
            }
            LiveStreamer.State.CONNECTED -> {
                statusText.text = ""
                counterText.setText(R.string.live_state_connected)
            }
            LiveStreamer.State.RETRY -> {
                statusText.setText(R.string.live_status_retry)
                counterText.text = ""
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
            }
        }
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
    }
}
