package com.quietsentry.app

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import java.net.URL

/**
 * 观看端拉取 MJPEG 直播（PRD FR-08：局域网实时画面）。
 *
 * 为什么不用 WebView 直接打开地址：WebView 无法带自定义令牌头（X-Auth-Token），
 * 也不方便把每一帧交给原生控件做全屏、截图与画质切换；
 * 这里按服务端约定（multipart/x-mixed-replace + 每帧 Content-Length）逐帧读取，
 * 帧边界由长度字段决定，比按 JPEG 的 FFD8/FFD9 扫描更省 CPU，也不会被缩略数据误判。
 */
class MjpegClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val quality: String,
) : Closeable {

    private var connection: HttpURLConnection? = null
    private var input: BufferedInputStream? = null

    /** 打不开通常意味着设备离线、令牌被撤销或并发达上限，调用方据此给出不同提示 */
    var failure: Failure = Failure.NONE
        private set

    fun open(): Boolean = runCatching {
        val factory = TlsTrust.factoryFor(host, port)
        val scheme = if (factory != null) "https" else "http"
        val conn = URL("$scheme://$host:$port/api/live?quality=$quality").openConnection()
            as HttpURLConnection
        if (factory != null && conn is HttpsURLConnection) {
            conn.sslSocketFactory = factory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        // 服务端超过 15 秒无新帧会主动断开；留出余量后仍未收到数据就当作断流，触发重连
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("X-Auth-Token", token)
        connection = conn
        if (conn.responseCode != 200) {
            failure = if (conn.responseCode == 401) Failure.UNAUTHORIZED else Failure.BUSY
            return false
        }
        input = BufferedInputStream(conn.inputStream)
        true
    }.getOrElse {
        failure = Failure.OFFLINE
        false
    }

    /** 阻塞读下一帧 JPEG；返回 null 表示流已断开或数据不完整 */
    fun readFrame(): ByteArray? = runCatching {
        val source = input ?: return null
        var length = -1
        while (length < 0) {
            val line = readLine(source) ?: return null
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            if (lower.startsWith(HEADER_LENGTH)) {
                length = lower.substring(HEADER_LENGTH.length).trim().toIntOrNull() ?: return null
                if (length <= 0) return null
                // 服务端每帧头部还有 X-Timestamp 与结尾空行；不把它们读完就直接读 JPEG，
                // 会把剩余头部当成像素数据，每帧都解码失败（表现为"已连接"但画面全黑）
                while (true) {
                    val rest = readLine(source) ?: return null
                    if (rest.isEmpty()) break
                }
            }
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = source.read(bytes, read, length - read)
            if (count < 0) return null
            read += count
        }
        bytes
    }.getOrNull()

    override fun close() {
        input = null
        runCatching { connection?.disconnect() }
        connection = null
    }

    private fun readLine(source: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = source.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) break
            if (byte != '\r'.code) builder.append(byte.toChar())
        }
        return builder.toString()
    }

    enum class Failure { NONE, OFFLINE, UNAUTHORIZED, BUSY }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 20_000
        const val HEADER_LENGTH = "content-length:"
    }
}
