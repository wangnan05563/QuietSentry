package com.quietsentry.app

import org.json.JSONObject
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import java.net.URL

/**
 * 观看端访问相机端 HTTP 服务的最小客户端（PRD §12：局域网 HTTP + 令牌）。
 *
 * 为什么手写而不引 OkHttp：这里只有 info / status / pair 三个调用，
 * 全部是短连接的小 JSON；为了这点需求引入一个网络库，会让“零第三方依赖”的构建原则破例。
 * 所有方法都是阻塞的，调用方必须在 IO 线程执行。
 */
object DeviceHttp {

    data class Info(
        val id: String,
        val name: String,
        val port: Int,
        /** 相机端 HTTPS 端口与证书指纹；0/空表示旧版本或证书不可用，继续走 HTTP */
        val tlsPort: Int = 0,
        val tlsFingerprint: String = "",
        val mode: String,
        val lastEventAt: Long,
    )

    data class Status(
        val mode: String,
        val battery: Int,
        val charging: Boolean,
        val thermal: Int,
        val storageFreeMb: Long,
        val watching: Int,
        val maxViewers: Int,
        val lastEventAt: Long,
        /** 相机端最近一次电源巡检时间；0 表示本次进程还没有值守心跳 */
        val heartbeatAt: Long = 0L,
        /** 相机端时间戳：心跳年龄用两端各自的时钟算会在时区/校时差异下失真，必须同源相减 */
        val serverTime: Long = 0L,
        /** 相机端是否处于省电降载（停推流、隔帧检测） */
        val throttled: Boolean = false,
        /** 相机端是否已被观看端远程暂停（PRD FR-14），暂停后不会自动恢复 */
        val paused: Boolean = false,
        /** 相机端是否开启隐私遮罩：观看端要能解释"画面为什么是黑的" */
        val masked: Boolean = false,
    )

    data class PairResult(val token: String, val role: String, val fingerprint: String)

    /**
     * 大屏事件侧栏的最小字段集（T04058）。远端片段回放能力尚未提供，
     * 侧栏只做时间线展示，所以不携带缩略图/片段路径，够区分类型与新旧即可。
     */
    data class RemoteEvent(
        val id: Long,
        val type: String,
        val occurredAt: Long,
        val confidence: Int,
        val read: Boolean,
        val locked: Boolean,
    )

    fun events(host: String, port: Int, token: String, limit: Int = 20): List<RemoteEvent>? {
        val body = call(host, port, "/api/events?limit=$limit", token = token) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val array = json.optJSONArray("events") ?: return null
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            RemoteEvent(
                id = item.optLong("id"),
                type = item.optString("type"),
                occurredAt = item.optLong("occurredAt"),
                confidence = item.optInt("confidence"),
                read = item.optBoolean("read"),
                locked = item.optBoolean("locked"),
            )
        }
    }

    fun info(host: String, port: Int): Info? {
        val body = call(host, port, "/api/info") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return Info(
            id = json.optString("id"),
            name = json.optString("name"),
            port = json.optInt("port", port),
            tlsPort = json.optInt("tlsPort", 0),
            tlsFingerprint = json.optString("tlsFingerprint"),
            mode = json.optString("mode"),
            lastEventAt = json.optLong("lastEventAt"),
        )
    }

    fun status(host: String, port: Int, token: String): Status? {
        val body = call(host, port, "/api/status", token = token) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return Status(
            mode = json.optString("mode"),
            battery = json.optInt("battery", -1),
            charging = json.optBoolean("charging"),
            thermal = json.optInt("thermal", 0),
            storageFreeMb = json.optLong("storageFreeMb", -1),
            watching = json.optInt("watching"),
            // 上限由服务端下发；旧版本服务端不返回时按 5 兜底，界面仍可显示 “2/5”
            maxViewers = json.optInt("maxViewers", DEFAULT_MAX_VIEWERS),
            lastEventAt = json.optLong("lastEventAt"),
            heartbeatAt = json.optLong("heartbeatAt"),
            serverTime = json.optLong("serverTime"),
            throttled = json.optBoolean("throttled"),
            paused = json.optBoolean("paused"),
            masked = json.optBoolean("masked"),
        )
    }

    /**
     * 用 6 位配对码换取令牌。角色由服务端判定（首个配对者为 Owner），
     * 客户端不自行决定，避免两台观看端都以为自己是所有者。
     */
    fun pair(
        host: String,
        port: Int,
        publicKey: String,
        code: String,
        recoveryCode: String? = null,
    ): PairResult? {
        val body = JSONObject()
            .put("publicKey", publicKey)
            .put("code", code)
            .apply { if (recoveryCode != null) put("recoveryCode", recoveryCode) }
            .toString()
        val response = call(host, port, "/api/pair", method = "POST", body = body) ?: return null
        val json = runCatching { JSONObject(response) }.getOrNull() ?: return null
        val token = json.optString("token")
        if (token.isEmpty()) return null
        return PairResult(
            token = token,
            role = json.optString("role"),
            fingerprint = json.optString("fingerprint"),
        )
    }

    /**
     * 移除设备时主动交还令牌：否则相机端的授权列表里会一直躺着一条本机已经不再使用的记录，
     * 用户也无法从相机端确认“谁还在看”。失败可以忽略——设备离线时下次也无法再连。
     */
    fun leave(host: String, port: Int, token: String): Boolean =
        call(host, port, "/api/leave", method = "POST", token = token) != null

    /**
     * 远程暂停（PRD FR-14）：只发暂停，不提供远程恢复。
     * 恢复必须由摄像头端本机确认，否则任何拿到令牌的设备都能悄悄把采集重新打开。
     */
    fun pause(host: String, port: Int, token: String): Boolean =
        call(host, port, "/api/pause", method = "POST", token = token) != null

    /** 威慑操作的结果：冷却与夜间禁用必须能被区分，界面才能说清“为什么没响” */
    data class ControlResult(val ok: Boolean, val error: String = "", val retryAfterSec: Int = 0)

    /**
     * 快照兜底（PRD 3.2 防白屏）：首帧未到或断流时观看端先展示最近画面。
     * 时间戳取自服务端 X-Timestamp——缓存帧是拍摄时刻、事件缩略图是文件时间，
     * 两者都能当“最后在线”展示；缺失时返回 0，界面退化为只显示画面不标时间。
     */
    data class SnapshotResult(val jpeg: ByteArray, val atMs: Long)

    fun snapshot(host: String, port: Int, token: String): SnapshotResult? = runCatching {
        val factory = TlsTrust.factoryFor(host, port)
        val scheme = if (factory != null) "https" else "http"
        val connection = URL("$scheme://$host:$port/api/snapshot").openConnection() as HttpURLConnection
        if (factory != null && connection is HttpsURLConnection) {
            connection.sslSocketFactory = factory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("X-Auth-Token", token)
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            SnapshotResult(
                connection.inputStream.use { it.readBytes() },
                connection.getHeaderField("X-Timestamp")?.toLongOrNull() ?: 0L,
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun siren(host: String, port: Int, token: String): ControlResult =
        trigger(host, port, "/api/siren", token)

    fun torch(host: String, port: Int, token: String): ControlResult =
        trigger(host, port, "/api/torch", token)

    // 触发态中途停止（T04052）：与服务端 /api/{siren,torch}/stop 一一对应
    fun sirenStop(host: String, port: Int, token: String): ControlResult =
        trigger(host, port, "/api/siren/stop", token)

    fun torchStop(host: String, port: Int, token: String): ControlResult =
        trigger(host, port, "/api/torch/stop", token)

    /**
     * 对讲：每 0.5 秒一块 16kHz/16bit 单声道 PCM。
     * 返回 false 就让采集线程停下——继续上传只会在服务端越积越多，听起来像延迟越来越大。
     */
    fun talk(host: String, port: Int, token: String, pcm: ByteArray): Boolean {
        val reply = request(
            host, port, "/api/talk",
            method = "POST", token = token, bytes = pcm, contentType = OCTET_TYPE
        ) ?: return false
        return reply.code in 200..299
    }

    private fun trigger(host: String, port: Int, path: String, token: String): ControlResult {
        val reply = request(host, port, path, method = "POST", token = token)
            ?: return ControlResult(false, "unreachable")
        if (reply.code in 200..299) return ControlResult(true)
        val json = runCatching { JSONObject(reply.body) }.getOrNull()
        return ControlResult(
            ok = false,
            error = json?.optString("error").orEmpty().ifEmpty { "http_${reply.code}" },
            retryAfterSec = json?.optInt("retryAfterSec") ?: 0,
        )
    }

    private data class Reply(val code: Int, val body: String)

    private fun call(
        host: String,
        port: Int,
        path: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
    ): String? = request(host, port, path, method, token, body)
        ?.takeIf { it.code in 200..299 }
        ?.body

    private fun request(
        host: String,
        port: Int,
        path: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
        bytes: ByteArray? = null,
        contentType: String = JSON_TYPE,
    ): Reply? = runCatching {
        val factory = TlsTrust.factoryFor(host, port)
        val scheme = if (factory != null) "https" else "http"
        val connection = URL("$scheme://$host:$port$path").openConnection() as HttpURLConnection
        if (factory != null && connection is HttpsURLConnection) {
            connection.sslSocketFactory = factory
            // 自签证书 CN 写的是设备名而不是 IP，hostname 校验只能交给指纹 pin
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        connection.requestMethod = method
        // 局域网内设备响应通常 <50ms；3 秒足够区分“离线”与“慢”，也避免轮询线程被一台掉线设备拖住
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        token?.let { connection.setRequestProperty("X-Auth-Token", it) }
        val payload = bytes ?: body?.toByteArray(Charsets.UTF_8)
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(payload) }
        }
        try {
            val code = connection.responseCode
            // 失败时体里是 error 码，必须读 errorStream；只判状态码会让界面失去区分原因的能力
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Reply(code, text)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private const val TIMEOUT_MS = 3_000
    private const val DEFAULT_MAX_VIEWERS = 5
    private const val JSON_TYPE = "application/json"
    private const val OCTET_TYPE = "application/octet-stream"
}
