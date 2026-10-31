package com.quietsentry.app

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * 实时流的单一编码源（PRD FR-08：单台摄像头并发观看 ≤5，由摄像头端保持单一编码并分发）。
 *
 * 为什么只压缩一次而不是每个观看连接各自 compress()：5 个观众意味着 5 倍 JPEG 编码开销，
 * 旧手机在 640x480 上会被编码而不是网络先拖垮。所有连接共享同一份字节，只是在不同时刻读取。
 *
 * 主码流与子码流都只在这里生成：观看端请求 quality=sub 时拿到的是缩小的同一帧，
 * 两路流在时间上严格对齐，切换画质不会出现画面跳变。
 */
object LanFrameHub {

    data class Frame(val jpeg: ByteArray, val width: Int, val height: Int, val atMs: Long)

    /** 主码流目标尺寸；CameraX 分析路按 640x480 绑定，低端机可能被降级，此时主码流跟随实际尺寸 */
    private const val MAIN_QUALITY = 60
    private const val SUB_WIDTH = 320
    private const val SUB_HEIGHT = 240
    private const val SUB_QUALITY = 45

    /**
     * 发布节流：12 fps 对“实时观察”已经足够连贯，
     * 却能把 JPEG 编码与 Wi-Fi 占用压到旧机能长时间承受的范围（PRD 延迟 ≤1 秒量级）。
     */
    private const val MIN_PUBLISH_INTERVAL_MS = 80L

    private val lock = Object()

    // 分析线程写、HTTP 线程读，用 @Volatile 保证可见性；一致性由 lock 与 sequence 共同保证
    @Volatile
    private var mainFrame: Frame? = null
    @Volatile
    private var subFrame: Frame? = null

    private var sequence = 0L
    private var lastPublishMs = 0L

    fun hasFrame(): Boolean = mainFrame != null

    /**
     * 从相机分析帧发布。调用方仍负责 image.close()，
     * 因此这里只读取像素、不触碰生命周期，避免与 MotionDetector 的 close 争抢缓冲。
     */
    fun publish(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastPublishMs < MIN_PUBLISH_INTERVAL_MS) return
        val bitmap = runCatching { image.toBitmap() }.getOrNull() ?: return
        try {
            publishBitmap(bitmap, now)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 读取当前帧及其序号。首帧优化靠它：新连接不必等下一次相机出帧，
     * 直接拿到缓存里的最近一帧，观看端“打开实时画面到首帧 ≤2 秒”才成立。
     */
    fun snapshot(main: Boolean): Pair<Long, Frame>? = synchronized(lock) {
        val frame = if (main) mainFrame else subFrame
        if (frame == null) null else sequence to frame
    }

    /**
     * 等待 after 之后的新帧。超时返回 null 让调用方有机会检查服务是否还在运行，
     * 而不是把一个已经关闭的直播连接永久挂住。
     */
    fun await(after: Long, main: Boolean, timeoutMs: Long): Pair<Long, Frame>? {
        synchronized(lock) {
            val frame = if (main) mainFrame else subFrame
            if (frame != null && sequence > after) return sequence to frame
            lock.wait(timeoutMs)
            val next = if (main) mainFrame else subFrame
            return if (next != null && sequence > after) sequence to next else null
        }
    }

    private fun publishBitmap(bitmap: Bitmap, atMs: Long) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val mainBytes = compress(bitmap, MAIN_QUALITY)
        // 源帧本身就比子码流小（低端机降级到 320x240）时不再放大：
        // 放大只会增加字节数而不增加信息量，反而让弱网更卡
        val subBytes: ByteArray
        val subW: Int
        val subH: Int
        if (bitmap.width <= SUB_WIDTH) {
            subBytes = mainBytes
            subW = bitmap.width
            subH = bitmap.height
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, SUB_WIDTH, SUB_HEIGHT, true)
            subBytes = compress(scaled, SUB_QUALITY)
            subW = SUB_WIDTH
            subH = SUB_HEIGHT
            scaled.recycle()
        }

        synchronized(lock) {
            mainFrame = Frame(mainBytes, bitmap.width, bitmap.height, atMs)
            subFrame = Frame(subBytes, subW, subH, atMs)
            sequence++
            lastPublishMs = atMs
            lock.notifyAll()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
