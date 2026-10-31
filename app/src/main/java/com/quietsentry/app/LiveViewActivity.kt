package com.quietsentry.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

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
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    /** 每次切换画质或恢复前台都换一个代号，旧循环即使还在阻塞读也会自行退出 */
    @Volatile
    private var streamToken = 0
    private var client: MjpegClient? = null
    private var currentFrame: Bitmap? = null
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

        // 看实时画面时不应因为锁屏而中断观察，也不该让系统栏占掉画面
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
        }
        statusText.text = getString(R.string.live_state_connecting)
    }

    override fun onStart() {
        super.onStart()
        startStream()
    }

    override fun onStop() {
        // 退到后台就停流：为“可能还要看”而一直解码耗电不值得，回前台会立即重新连上
        stopStream()
        super.onStop()
    }

    override fun onDestroy() {
        stopStream()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun toggleQuality() {
        quality = if (quality == QUALITY_AUTO) QUALITY_MAIN else QUALITY_AUTO
        qualityButton.setText(
            if (quality == QUALITY_AUTO) R.string.live_quality_auto else R.string.live_quality_main
        )
        // 换画质只能换一条流：服务端按连接决定码流，不断开就没法重新协商
        stopStream()
        startStream()
    }

    private fun startStream() {
        streamToken++
        val token = streamToken
        val target = device
        running = true
        setCounter(R.string.live_state_connecting)
        io.execute { runStream(target, token) }
    }

    private fun stopStream() {
        running = false
        streamToken++
        client?.close()
        client = null
    }

    private fun runStream(target: DeviceRegistry.Device, token: Int) {
        while (running && token == streamToken) {
            val stream = MjpegClient(target.host, target.port, target.token, quality)
            client = stream
            if (!stream.open()) {
                val failure = stream.failure
                stream.close()
                client = null
                if (token != streamToken) return
                showFailure(failure)
                // 令牌被撤销后重连只会一直拿到 401，停下来让用户重新配对
                if (failure == MjpegClient.Failure.UNAUTHORIZED) {
                    running = false
                    return
                }
                if (!sleepRetry(token)) return
                continue
            }

            clearStatus()
            setCounter(R.string.live_state_connected)
            while (running && token == streamToken) {
                val bytes = stream.readFrame() ?: break
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                currentFrame = bitmap
                showFrame(bitmap)
            }
            stream.close()
            client = null
            if (running && token == streamToken) {
                showRetry()
                if (!sleepRetry(token)) return
            }
        }
    }

    private fun showFailure(failure: MjpegClient.Failure) {
        val text = when (failure) {
            MjpegClient.Failure.UNAUTHORIZED -> getString(R.string.live_status_unauthorized)
            MjpegClient.Failure.BUSY -> getString(R.string.live_status_busy, MAX_VIEWERS)
            else -> getString(R.string.live_status_offline)
        }
        onUi {
            statusText.text = text
            counterText.text = ""
        }
    }

    private fun showRetry() = onUi {
        statusText.text = getString(R.string.live_status_retry)
        counterText.text = ""
    }

    private fun clearStatus() = onUi {
        statusText.text = ""
        counterText.text = getString(R.string.live_state_connected)
    }

    private fun setCounter(resId: Int) = onUi { counterText.setText(resId) }

    private fun showFrame(bitmap: Bitmap) = onUi { image.setImageBitmap(bitmap) }

    private fun onUi(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun sleepRetry(token: Int): Boolean {
        var waited = 0L
        while (running && token == streamToken && waited < RETRY_DELAY_MS) {
            Thread.sleep(RETRY_STEP_MS)
            waited += RETRY_STEP_MS
        }
        return running && token == streamToken
    }

    private fun snapshot() {
        val frame = currentFrame
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
        private const val RETRY_DELAY_MS = 3_000L
        private const val RETRY_STEP_MS = 200L
    }
}
