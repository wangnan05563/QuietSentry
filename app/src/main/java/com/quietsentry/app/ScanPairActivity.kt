package com.quietsentry.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * 观看端扫码添加（PRD 07 §4.5 P0）。
 *
 * 走马灯与配对页文案都承诺了“扫码”，此页负责兑现：扫摄像头端配对页上的二维码，
 * 直接换取令牌完成配对，不再经过 mDNS 窗口或手输配对码。
 *
 * 解码复用生成端同款的 zxing core（无相机解码器依赖），预览用 CameraX 与主界面一致。
 */
class ScanPairActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hintText: TextView

    private val pairing by lazy { PairingStore(this) }
    private val registry by lazy { DeviceRegistry(this) }

    // 分析帧只在这一个线程上解码：QRCodeReader 非线程安全，串行化免加锁
    private val decoder = QRCodeReader()
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "qr-decode") }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // 扫到第一张有效配对码后就不再处理后续帧：配对码每 6 位不变，重复触发只会让界面闪烁
    private val handled = java.util.concurrent.atomic.AtomicBoolean(false)

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else hintText.setText(R.string.scan_pair_camera_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_pair)
        previewView = findViewById(R.id.scan_preview)
        hintText = findViewById(R.id.scan_pair_hint)
        findViewById<Button>(R.id.scan_pair_back).setOnClickListener { finish() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    // 只保最新帧：解码慢于出帧时丢旧帧，避免延迟越积越大导致“画面已对准却扫不出”
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (handled.get()) return
            val text = decode(image) ?: return
            if (!handled.compareAndSet(false, true)) return
            val payload = PairingQr.parsePairLink(text)
            if (payload == null) {
                // 不是静哨配对码：不配对，允许继续扫别的码
                runOnUiThread { hintText.setText(R.string.scan_pair_not_qr) }
                handled.set(false)
                return
            }
            runOnUiThread { hintText.setText(R.string.scan_pair_pairing) }
            pair(payload)
        } finally {
            image.close()
        }
    }

    /** Y 平面直接当灰度图喂给 zxing：二维码只需要亮度信息，UV 平面完全不用 */
    private fun decode(image: ImageProxy): String? = runCatching {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        val gray = ByteArray(width * height)
        if (plane.rowStride == width) {
            buffer.get(gray)
        } else {
            // rowStride 可能大于 width（行尾对齐 padding），必须逐行搬，否则会读到越界或图像错位
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                buffer.get(gray, row * width, width)
            }
        }
        // 末位 reverseHorizontal=false：镜像画面只会出现在自拍镜头，这里固定后置
        val source = com.google.zxing.PlanarYUVLuminanceSource(
            gray, width, height, 0, 0, width, height, false
        )
        // TRY_HARDER 让 zxing 对同一帧尝试四个方向：省掉手动按 rotationDegrees 转置，横竖屏都能识别
        decoder.decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true)
        ).text
    }.getOrNull()

    /** 与 DeviceListActivity 的手动/发现配对同一套握手：info 取 TLS → 指纹校验 → pair 换令牌 */
    private fun pair(payload: PairingQr.PairPayload) {
        ioExecutor.execute {
            val info = DeviceHttp.info(payload.host, payload.port)
            if (info == null) {
                fail(R.string.scan_pair_unreachable)
                return@execute
            }
            // 二维码里的指纹就是设备身份；不一致说明这不是配对页当前生成的那台设备
            if (payload.fingerprint != info.id) {
                fail(R.string.device_list_pair_mismatch)
                return@execute
            }
            if (registry.count() >= DeviceRegistry.MAX_DEVICES) {
                failText(getString(R.string.device_list_limit, DeviceRegistry.MAX_DEVICES))
                return@execute
            }
            val securePort = if (info.tlsPort > 0 && info.tlsFingerprint.isNotEmpty()) {
                TlsTrust.register(payload.host, info.tlsPort, info.tlsFingerprint)
                info.tlsPort
            } else {
                payload.port
            }
            val result = DeviceHttp.pair(payload.host, securePort, pairing.publicKeyBase64(), payload.code)
                ?: DeviceHttp.pair(
                    payload.host, securePort, pairing.publicKeyBase64(), payload.code,
                    recoveryCode = payload.code
                )
            if (result == null) {
                fail(R.string.device_list_pair_failed)
                return@execute
            }
            registry.add(
                DeviceRegistry.Device(
                    id = info.id,
                    name = info.name,
                    room = "",
                    host = payload.host,
                    port = securePort,
                    tlsFingerprint = if (securePort != payload.port) info.tlsFingerprint else "",
                    token = result.token,
                    order = 0,
                    lastSeenAt = System.currentTimeMillis(),
                    lastEventAt = info.lastEventAt,
                )
            )
            runOnUiThread {
                Toast.makeText(this, R.string.device_list_paired, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun fail(messageRes: Int) {
        runOnUiThread { failText(getString(messageRes)) }
    }

    private fun failText(message: String) {
        hintText.text = message
        // 失败后允许重试同一张码：重扫一次比让用户退出重进更快
        handled.set(false)
    }

    override fun onDestroy() {
        analysisExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
