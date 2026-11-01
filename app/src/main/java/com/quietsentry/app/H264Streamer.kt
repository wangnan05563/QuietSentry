package com.quietsentry.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

private const val AVC_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
private const val AVC_FRAME_RATE = 12
private const val AVC_IFRAME_SECONDS = 2
private const val AVC_MAIN_BITRATE = 900_000
private const val AVC_SUB_BITRATE = 250_000

/** 子码流宽度上限：NVR 预览与移动端弱网只需要轮廓，再宽就会把两路编码的算力翻倍 */
private const val AVC_SUB_MAX_WIDTH = 360

/** 与 LanFrameHub 的 12fps 对齐；两路按同一节流，主/子码流不会出现时间戳漂移 */
private const val AVC_PUBLISH_INTERVAL_MS = 80L

/**
 * H.264 主/子码流编码与分发（PRD FR-15：RTSP 拉流与 NVR 接入）。
 *
 * 为什么不复用 LanFrameHub 的 JPEG：RTSP/NVR 只认 H.264，而且 JPEG 每帧都要整帧重编码，
 * 字节量远高于一次硬件编码加帧间压缩，第三方长时间拉流会把局域网带宽和整机电量一起拖垮。
 *
 * 为什么主/子码流各自一个 MediaCodec：H.264 无法在服务端从主码流抽帧降分辨率，
 * 子码流必须由编码器独立产出；两路共用同一路相机帧，时间轴天然对齐。
 */
object H264Streamer {

    enum class Stream { MAIN, SUB }

    /** 一次编码输出视为一帧，帧内可能包含多个 NAL（如 SPS+PPS+IDR），由 RTP 层负责分片 */
    fun interface Sink {
        fun onFrame(stream: Stream, nals: List<ByteArray>, idr: Boolean, atMs: Long)
    }

    private const val TAG = "H264Streamer"

    private val sinks = CopyOnWriteArrayList<Sink>()
    private var main: H264Encoder? = null
    private var sub: H264Encoder? = null
    private var lastPublishMs = 0L

    @Volatile
    var mainWidth = 0
        private set

    @Volatile
    var mainHeight = 0
        private set

    @Volatile
    var subWidth = 0
        private set

    @Volatile
    var subHeight = 0
        private set

    fun addSink(sink: Sink) {
        sinks.add(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    /**
     * SDP 的 sprop-parameter-sets 需要裸 SPS/PPS。
     * 编码器第一帧之后才有参数集，未就绪时返回 null，让 RTSP 层先回 SDP 再在 PLAY 期补发。
     */
    fun parameterSets(): Pair<ByteArray, ByteArray>? {
        val encoder = main ?: return null
        val sps = encoder.sps ?: return null
        val pps = encoder.pps ?: return null
        return sps to pps
    }

    fun sizeOf(stream: Stream): Pair<Int, Int> =
        if (stream == Stream.MAIN) mainWidth to mainHeight else subWidth to subHeight

    fun hasStream(): Boolean = main != null

    /**
     * 从相机分析帧编码。调用方仍负责 image.close()，这里只读像素。
     * 编码器未就绪或输入缓冲暂时不可用时直接丢帧：RTSP 拉流失败不能回压到值守录制。
     */
    fun publish(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastPublishMs < AVC_PUBLISH_INTERVAL_MS) return
        val srcW = image.width
        val srcH = image.height
        if (srcW <= 0 || srcH <= 0) return
        lastPublishMs = now
        ensureEncoders(srcW, srcH)
        main?.let {
            it.encode(image, now)
            it.drain(now)
        }
        sub?.let {
            it.encode(image, now)
            it.drain(now)
        }
    }

    /** 停止值守或相机重绑时释放硬件编码器：实例常驻会一直占用编解码资源 */
    fun stop() {
        main?.release()
        sub?.release()
        main = null
        sub = null
        mainWidth = 0
        mainHeight = 0
        subWidth = 0
        subHeight = 0
    }

    private fun ensureEncoders(srcW: Int, srcH: Int) {
        if (main != null) return
        val w = (srcW / 2) * 2
        val h = (srcH / 2) * 2
        if (w < 160 || h < 120) return
        mainWidth = w
        mainHeight = h
        main = H264Encoder(Stream.MAIN, w, h, 1, AVC_MAIN_BITRATE, ::emit)
        val scale = scaleFor(w)
        val sw = ((w / scale) / 2) * 2
        val sh = ((h / scale) / 2) * 2
        subWidth = sw
        subHeight = sh
        sub = H264Encoder(Stream.SUB, sw, sh, scale, AVC_SUB_BITRATE, ::emit)
        Log.i(TAG, "encoders ready main=$w x $h sub=$sw x $sh scale=$scale")
    }

    private fun emit(stream: Stream, nals: List<ByteArray>, idr: Boolean, atMs: Long) {
        sinks.forEach { it.onFrame(stream, nals, idr, atMs) }
    }

    private fun scaleFor(width: Int): Int {
        var scale = 1
        while (width / scale > AVC_SUB_MAX_WIDTH && scale < 8) scale++
        return scale
    }
}

/**
 * 单个码流的最小编码器封装。之所以独立成类而不嵌在 H264Streamer 里：
 * 两个码流共享同一套 MediaCodec 生命周期处理，镶嵌写法会让作用域引用与副作用更容易混淆。
 */
private class H264Encoder(
    private val stream: H264Streamer.Stream,
    private val width: Int,
    private val height: Int,
    private val scale: Int,
    private val bitrate: Int,
    private val emit: (H264Streamer.Stream, List<ByteArray>, Boolean, Long) -> Unit,
) {
    var codec: MediaCodec? = null
    var sps: ByteArray? = null
    var pps: ByteArray? = null

    // 复用同一块输出缓冲：12fps 下每帧都新建 460KB 数组会把 GC 顶起来
    private val nv12 = ByteArray(width * height * 3 / 2)
    private val info = MediaCodec.BufferInfo()
    private var failed = false

    fun encode(image: ImageProxy, atMs: Long) {
        if (!ensure()) return
        val c = codec ?: return
        fillNv12(image)
        val index = c.dequeueInputBuffer(0)
        if (index < 0) return
        val buffer = c.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(nv12, 0, nv12.size)
        c.queueInputBuffer(index, 0, nv12.size, atMs * 1000L, 0)
    }

    fun drain(atMs: Long) {
        val c = codec ?: return
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    captureParameterSets(c.outputFormat)
                    continue
                }
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buffer.get(data)
                        val nals = splitAnnexB(data)
                        if (nals.isNotEmpty()) {
                            val idr = nals.any { (it[0].toInt() and 0x1F) == 5 }
                            emit(stream, nals, idr, atMs)
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    fun release() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun ensure(): Boolean {
        if (codec != null) return true
        if (failed) return false
        return try {
            val format = MediaFormat.createVideoFormat(AVC_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, AVC_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, AVC_IFRAME_SECONDS)
            }
            val c = MediaCodec.createEncoderByType(AVC_MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            Log.i("H264Streamer", "$stream encoder started $width x $height")
            true
        } catch (e: Exception) {
            // 设备不支持 NV12 输入时整条 RTSP 路径降级，不能把异常抛回相机分析线程拖垮录制
            failed = true
            Log.w("H264Streamer", "$stream encoder unavailable", e)
            false
        }
    }

    private fun captureParameterSets(format: MediaFormat) {
        format.getByteBuffer("csd-0")?.let { source ->
            val bytes = bytesOf(source)
            sps = splitAnnexB(bytes).firstOrNull() ?: bytes
        }
        format.getByteBuffer("csd-1")?.let { source ->
            val bytes = bytesOf(source)
            pps = splitAnnexB(bytes).firstOrNull() ?: bytes
        }
    }

    /** YUV_420_888 → NV12，同时按 scale 降采样；子码流靠这一份实现，避免再起一条相机管线 */
    private fun fillNv12(image: ImageProxy) {
        val planes = image.planes
        if (planes.size < 3) return
        val ySource = planes[0]
        val uSource = planes[1]
        val vSource = planes[2]
        val yBuffer = ySource.buffer
        val yRowStride = ySource.rowStride
        val yPixelStride = ySource.pixelStride
        for (row in 0 until height) {
            val sourceBase = row * scale * yRowStride
            val targetBase = row * width
            for (col in 0 until width) {
                val offset = sourceBase + col * scale * yPixelStride
                nv12[targetBase + col] = if (offset < yBuffer.limit()) yBuffer.get(offset) else 0
            }
        }
        val uBuffer = uSource.buffer
        val vBuffer = vSource.buffer
        val uvRowStride = uSource.rowStride
        val uvPixelStride = uSource.pixelStride
        val uvBase = width * height
        for (row in 0 until height / 2) {
            val sourceBase = row * scale * uvRowStride
            for (col in 0 until width / 2) {
                val offset = sourceBase + col * scale * uvPixelStride
                val target = uvBase + row * width + col * 2
                nv12[target] = if (offset < uBuffer.limit()) uBuffer.get(offset) else 0
                nv12[target + 1] = if (offset < vBuffer.limit()) vBuffer.get(offset) else 0
            }
        }
    }
}

private fun bytesOf(buffer: ByteBuffer): ByteArray {
    val copy = ByteArray(buffer.remaining())
    buffer.duplicate().get(copy)
    return copy
}

/**
 * 拆 Annex-B 起始码。MediaCodec 大多数实现输出带起始码，但个别设备输出裸 NAL，
 * 因此没找到起始码时整段当作一个 NAL，避免整帧丢弃。
 */
private fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val result = ArrayList<ByteArray>(4)
    var start = -1
    var i = 0
    while (i + 2 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            if (start >= 0) {
                val end = if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                if (end > start) result.add(data.copyOfRange(start, end))
            }
            start = i + 3
            i += 3
        } else {
            i++
        }
    }
    if (start >= 0) {
        if (start < data.size) result.add(data.copyOfRange(start, data.size))
    } else if (data.isNotEmpty()) {
        result.add(data)
    }
    return result
}
