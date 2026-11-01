package com.quietsentry.app

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** RTSP 端口段：554 是标准端口但需要系统权限，这里从 8554 起，实际端口由 ONVIF 的 GetStreamUri 回填 */
private val RTSP_PORT_RANGE = 8554..8564
private const val RTSP_MAX_SESSIONS = 4
private const val RTP_PAYLOAD_TYPE = 96
private const val RTP_MAX_PAYLOAD = 1400
private const val RTP_QUEUE_FRAMES = 32

/** SSRC 固定：客户端按 SSRC 聚合统计，随机值会让同一次拉流的丢包/抖动统计反复重置 */
private const val RTP_SSRC = 0x5153454E

/**
 * RTSP over TCP 服务（PRD FR-15：Frigate / Home Assistant / VLC 拉流）。
 *
 * 为什么只做 TCP interleaved 不做 UDP：家庭 Wi-Fi 下 UDP 丢包会让 NVR 端画面持续花屏，
 * 且 UDP 需要额外端口与 NAT 穿透；TCP 复用同一条已认证连接，也天然复用 HTTP 的 Basic 凭据。
 *
 * 为什么推流与编码解耦（每会话一个队列 + 发送线程）：客户端读得慢时写 socket 会阻塞，
 * 若直接在编码回调里写，卡住的是相机分析线程，值守录制会跟着掉帧。
 */
object RtspServer {

    private const val TAG = "RtspServer"

    @Volatile
    var port = 0
        private set

    private var serverSocket: ServerSocket? = null
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private var appContext: Context? = null

    @Synchronized
    fun ensure(context: Context): Int {
        appContext = context.applicationContext
        if (port > 0) return port
        var bound: ServerSocket? = null
        var chosen = 0
        for (candidate in RTSP_PORT_RANGE) {
            try {
                bound = ServerSocket(candidate, 8)
                chosen = candidate
                break
            } catch (e: Exception) {
                // 端口被占用就试下一个：端口不固定也能用，ONVIF 会把实际端口写进 GetStreamUri
            }
        }
        val socket = bound ?: return 0
        serverSocket = socket
        port = chosen
        Thread({ acceptLoop(socket) }, "rtsp-server").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "rtsp listening on $chosen")
        return chosen
    }

    @Synchronized
    fun stop() {
        port = 0
        runCatching { serverSocket?.close() }
        serverSocket = null
        sessions.forEach { it.close() }
        sessions.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            val context = appContext
            if (context == null || sessions.size >= RTSP_MAX_SESSIONS) {
                runCatching { client.close() }
                continue
            }
            val session = Session(client, context)
            sessions += session
            session.begin()
        }
    }

    private data class RtspRequest(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
    )

    private class Frame(val nals: List<ByteArray>, val atMs: Long, val idr: Boolean)

    private class Session(private val socket: Socket, private val context: Context) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private val sessionId = String.format("%08X", System.nanoTime() and 0xFFFFFFFFL)
        private var stream = H264Streamer.Stream.MAIN
        private var channel = 0
        private var playing = false
        private var sink: H264Streamer.Sink? = null
        private var sequence = 0

        fun begin() {
            Thread({ run() }, "rtsp-session").apply {
                isDaemon = true
                start()
            }
        }

        fun close() {
            playing = false
            sink?.let { H264Streamer.removeSink(it) }
            sink = null
            runCatching { socket.close() }
        }

        private fun run() {
            try {
                while (true) {
                    val request = readRequest() ?: break
                    handle(request)
                }
            } catch (e: Exception) {
                Log.w(TAG, "session finished", e)
            } finally {
                close()
            }
        }

        private fun handle(request: RtspRequest) {
            val denied = denyStatus(request)
            if (denied != null) {
                val reason = if (denied == 503) "Service Unavailable" else "Unauthorized"
                respond(
                    request, denied, reason,
                    if (denied == 401) {
                        mapOf("WWW-Authenticate" to "Basic realm=\"QuietSentry\", charset=\"UTF-8\"")
                    } else {
                        emptyMap()
                    }
                )
                return
            }
            when (request.method) {
                "OPTIONS" -> respond(
                    request, 200, "OK",
                    mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER")
                )

                "DESCRIBE" -> describe(request)
                "SETUP" -> setup(request)
                "PLAY" -> play(request)
                "TEARDOWN" -> {
                    respond(request, 200, "OK", mapOf("Session" to sessionId))
                    close()
                }

                "GET_PARAMETER" -> respond(request, 200, "OK", mapOf("Session" to sessionId))
                else -> respond(request, 405, "Method Not Allowed")
            }
        }

        /**
         * 与 HTTP 侧同一套凭据：集成未开启直接 503，不透露"这里有 RTSP"；
         * 缺凭据只回 401 不计数，避免播放器每次重连都把失败次数消耗掉。
         */
        private fun denyStatus(request: RtspRequest): Int? {
            if (!LanIntegration.isEnabled(context)) return 503
            val header = request.headers["authorization"] ?: return 401
            if (!header.startsWith("Basic ", ignoreCase = true)) return 401
            val decoded = runCatching {
                Base64.decode(header.substring(6).trim(), Base64.NO_WRAP).toString(Charsets.UTF_8)
            }.getOrNull() ?: return 401
            val colon = decoded.indexOf(':')
            if (colon < 0) return 401
            return when (LanIntegration.verify(context, decoded.substring(0, colon), decoded.substring(colon + 1))) {
                LanIntegration.Result.OK -> null
                else -> 401
            }
        }

        private fun describe(request: RtspRequest) {
            val ip = lanIp()
            val sdp = buildString {
                append("v=0\r\n")
                append("o=- 0 0 IN IP4 ").append(ip).append("\r\n")
                append("s=QuietSentry\r\n")
                append("c=IN IP4 ").append(ip).append("\r\n")
                append("t=0 0\r\n")
                appendTrack(this, 0, H264Streamer.sizeOf(H264Streamer.Stream.MAIN))
                appendTrack(this, 1, H264Streamer.sizeOf(H264Streamer.Stream.SUB))
            }
            respond(
                request, 200, "OK",
                mapOf(
                    "Content-Type" to "application/sdp",
                    "Content-Base" to "rtsp://$ip:${RtspServer.port}/",
                ),
                sdp
            )
        }

        private fun appendTrack(builder: StringBuilder, trackId: Int, size: Pair<Int, Int>) {
            builder.append("m=video 0 RTP/AVP ").append(RTP_PAYLOAD_TYPE).append("\r\n")
            builder.append("a=rtpmap:").append(RTP_PAYLOAD_TYPE).append(" H264/90000\r\n")
            builder.append("a=fmtp:").append(RTP_PAYLOAD_TYPE).append(" packetization-mode=1")
            val parameters = H264Streamer.parameterSets()
            if (parameters != null) {
                val sps = Base64.encodeToString(parameters.first, Base64.NO_WRAP)
                val pps = Base64.encodeToString(parameters.second, Base64.NO_WRAP)
                builder.append(";sprop-parameter-sets=").append(sps).append(',').append(pps)
            }
            builder.append("\r\n")
            if (size.first > 0 && size.second > 0) {
                builder.append("a=x-dimensions:").append(size.first).append(',').append(size.second).append("\r\n")
            }
            builder.append("a=control:trackID=").append(trackId).append("\r\n")
        }

        private fun setup(request: RtspRequest) {
            val transport = request.headers["transport"] ?: ""
            val match = Regex("interleaved=(\\d+)(?:-(\\d+))?").find(transport)
            channel = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            stream = if (request.uri.contains("trackID=1")) H264Streamer.Stream.SUB else H264Streamer.Stream.MAIN
            respond(
                request, 200, "OK",
                mapOf(
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}",
                    "Session" to sessionId,
                )
            )
        }

        private fun play(request: RtspRequest) {
            respond(
                request, 200, "OK",
                mapOf("Session" to sessionId, "Range" to "npt=0.000-")
            )
            startSender()
        }

        private fun startSender() {
            if (playing) return
            playing = true
            val target = stream
            val queue = ArrayBlockingQueue<Frame>(RTP_QUEUE_FRAMES)
            val sink = H264Streamer.Sink { stream, nals, idr, atMs ->
                if (stream == target) {
                    // 队列满说明拉流端已经落后，丢当前帧而不是阻塞编码线程
                    queue.offer(Frame(nals, atMs, idr))
                }
            }
            this.sink = sink
            H264Streamer.addSink(sink)
            Thread({ sendLoop(queue) }, "rtsp-rtp").apply {
                isDaemon = true
                start()
            }
        }

        private fun sendLoop(queue: ArrayBlockingQueue<Frame>) {
            try {
                sendParameterSets()
                while (playing && !socket.isClosed) {
                    val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    val timestamp = frame.atMs * 90L
                    frame.nals.forEachIndexed { index, nal ->
                        val marker = index == frame.nals.lastIndex
                        writeNal(nal, timestamp, marker)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "rtp send stopped", e)
            } finally {
                close()
            }
        }

        /** SDP 里的 sprop 只覆盖"编码器早已就绪"的情况，PLAY 期再补发一次参数集最稳 */
        private fun sendParameterSets() {
            val parameters = H264Streamer.parameterSets() ?: return
            writeNal(parameters.first, 0L, false)
            writeNal(parameters.second, 0L, false)
        }

        private fun writeNal(nal: ByteArray, timestamp: Long, marker: Boolean) {
            if (nal.isEmpty()) return
            if (nal.size <= RTP_MAX_PAYLOAD) {
                sendPacket(timestamp, marker, nal)
                return
            }
            // FU-A：H.264 单个 NAL 超过 MTU 时必须分片，FU indicator 保留 NRI 位，FU header 带起止标志
            val indicator = (nal[0].toInt() and 0xE0) or 28
            val type = nal[0].toInt() and 0x1F
            var offset = 1
            var first = true
            while (offset < nal.size) {
                val size = minOf(RTP_MAX_PAYLOAD - 2, nal.size - offset)
                val last = offset + size >= nal.size
                val payload = ByteArray(size + 2)
                payload[0] = indicator.toByte()
                payload[1] = (
                    (if (first) 0x80 else 0x00) or
                        (if (last) 0x40 else 0x00) or
                        type
                    ).toByte()
                System.arraycopy(nal, offset, payload, 2, size)
                sendPacket(timestamp, marker && last, payload)
                offset += size
                first = false
            }
        }

        private fun sendPacket(timestamp: Long, marker: Boolean, payload: ByteArray) {
            val packet = ByteArray(12 + payload.size)
            packet[0] = 0x80.toByte()
            packet[1] = ((if (marker) 0x80 else 0x00) or RTP_PAYLOAD_TYPE).toByte()
            packet[2] = (sequence shr 8).toByte()
            packet[3] = sequence.toByte()
            packet[4] = (timestamp shr 24).toByte()
            packet[5] = (timestamp shr 16).toByte()
            packet[6] = (timestamp shr 8).toByte()
            packet[7] = timestamp.toByte()
            packet[8] = (RTP_SSRC shr 24).toByte()
            packet[9] = (RTP_SSRC shr 16).toByte()
            packet[10] = (RTP_SSRC shr 8).toByte()
            packet[11] = RTP_SSRC.toByte()
            System.arraycopy(payload, 0, packet, 12, payload.size)
            sequence++
            writeInterleaved(packet)
        }

        /** RTSP over TCP 的 RTP 帧格式：'$' + 通道号 + 2 字节长度 + RTP 包 */
        private fun writeInterleaved(packet: ByteArray) {
            val frame = ByteArray(4 + packet.size)
            frame[0] = '$'.code.toByte()
            frame[1] = channel.toByte()
            frame[2] = ((packet.size shr 8) and 0xFF).toByte()
            frame[3] = (packet.size and 0xFF).toByte()
            System.arraycopy(packet, 0, frame, 4, packet.size)
            synchronized(output) {
                output.write(frame)
                output.flush()
            }
        }

        private fun respond(
            request: RtspRequest,
            status: Int,
            reason: String,
            extra: Map<String, String> = emptyMap(),
            body: String = "",
        ) {
            val builder = StringBuilder()
            builder.append("RTSP/1.0 ").append(status).append(' ').append(reason).append("\r\n")
            request.headers["cseq"]?.let { builder.append("CSeq: ").append(it).append("\r\n") }
            builder.append("Server: QuietSentry\r\n")
            extra.forEach { (key, value) -> builder.append(key).append(": ").append(value).append("\r\n") }
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty()) {
                builder.append("Content-Length: ").append(bytes.size).append("\r\n")
            }
            builder.append("\r\n")
            synchronized(output) {
                output.write(builder.toString().toByteArray(Charsets.UTF_8))
                if (bytes.isNotEmpty()) output.write(bytes)
                output.flush()
            }
        }

        private fun readRequest(): RtspRequest? {
            val requestLine = readLine(input) ?: return null
            if (requestLine.isEmpty()) return null
            val parts = requestLine.split(' ')
            if (parts.size < 3) return null
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            return RtspRequest(parts[0], parts[1], headers)
        }
    }
}

private fun readLine(input: InputStream): String? {
    val builder = StringBuilder()
    while (true) {
        val read = input.read()
        if (read == -1) return if (builder.isEmpty()) null else builder.toString()
        if (read == '\n'.code) break
        if (read != '\r'.code) builder.append(read.toChar())
    }
    return builder.toString()
}

/** 本机 IPv4，供 SDP 的 o=/c= 与 ONVIF 的 XAddrs 使用 */
internal fun lanIp(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addresses = nif.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress
                    if (host != null) return host
                }
            }
        }
    } catch (e: Exception) {
        // 取不到时退回回环：SDP 只是客户端提示，实际链路仍是已建立的 TCP 连接
    }
    return "127.0.0.1"
}
