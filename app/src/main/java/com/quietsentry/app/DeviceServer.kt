package com.quietsentry.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网 HTTP 服务（PRD FR-15 / §12：HTTP 流 + 令牌认证，默认仅局域网可访问）。
 *
 * 为什么不引入 NanoHTTPD：本项目其余部分零第三方依赖，服务端点也只有 8 个；
 * 手写一个只处理单请求、Connection: close 的最小实现，避免为一层薄封装引入供应链面，
 * 也便于把“令牌优先于路径”的鉴权顺序写死在路由入口。
 *
 * 安全边界：除 /api/info 与 /api/pair 外一律要求配对令牌；
 * 事件与片段仅 Owner/Member 可见，Guest 只能拿实时流与快照（PRD §4.2）。
 */
class DeviceServer(
    private val context: Context,
    private val events: EventStore,
    private val pairing: PairingStore,
) {

    private val pool = Executors.newFixedThreadPool(MAX_THREADS)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    /**
     * 走 Basic 认证接入的第三方连接（Web 控制台、外部拉流）。
     * 一键关闭集成时只踢这些：自有观看端靠配对令牌，误踢会让正常值守的画面无故中断。
     */
    private val thirdParty = ConcurrentHashMap.newKeySet<Socket>()
    private val viewers = AtomicInteger()

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    var port: Int = 0
        private set

    /** 返回实际绑定端口。端口固定段而不是随机段，方便用户手输 IP 时不用每次找端口。 */
    fun start(): Int {
        if (running) return port
        var bound: ServerSocket? = null
        var chosen = 0
        for (candidate in PORT_RANGE) {
            try {
                bound = ServerSocket(candidate, BACKLOG)
                chosen = candidate
                break
            } catch (e: IOException) {
                // 端口被其他应用占用时继续试下一个；全段占用才向调用方报失败
            }
        }
        val socket = bound ?: throw IOException("no free port in $PORT_RANGE")
        serverSocket = socket
        port = chosen
        running = true
        Thread({ acceptLoop(socket) }, "device-server").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "lan server listening on $chosen")
        return chosen
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        // 直播是长连接，不主动断开会让 stop() 之后 accept 线程之外的工作线程继续跑
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        thirdParty.clear()
        pool.shutdownNow()
    }

    fun watchingCount(): Int = viewers.get()

    /**
     * 断开所有走 Basic 认证的第三方连接，返回断开数。
     * 设置页与 HTTP 端点共用这一份实现：两处各自写一遍，必然有一条漏掉某个集合的清理。
     * [except] 用于“由某个第三方连接发起关闭”的场景，否则会先把请求方自己踢掉再写响应。
     */
    fun kickThirdParty(except: Socket? = null): Int {
        val targets = thirdParty.filter { it !== except }
        thirdParty.removeAll(targets.toSet())
        targets.forEach { runCatching { it.close() } }
        return targets.size
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed", e)
                break
            }
            clients += client
            pool.execute {
                try {
                    handle(client)
                } catch (e: Exception) {
                    Log.w(TAG, "request failed", e)
                } finally {
                    clients -= client
                    thirdParty -= client
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val request = Request.parse(input) ?: return
        val output = socket.getOutputStream()
        route(socket, request, output)
        output.flush()
    }

    private fun route(socket: Socket, req: Request, out: OutputStream) {
        when {
            req.method == "GET" && req.path == "/api/info" -> handleInfo(req, out)
            req.method == "POST" && req.path == "/api/pair" -> handlePair(req, out)
            req.method == "GET" && req.path == "/api/status" -> handleStatus(req, out)
            req.method == "GET" && req.path == "/api/events" -> handleEvents(req, out)
            req.method == "GET" && req.path == "/api/thumbnail" -> handleThumbnail(req, out)
            req.method == "GET" && req.path == "/api/clip" -> handleClip(req, out)
            req.method == "GET" && req.path == "/api/snapshot" -> handleSnapshot(req, out)
            req.method == "GET" && req.path == "/api/live" -> handleLive(socket, req, out)
            req.method == "POST" && req.path == "/api/leave" -> handleLeave(req, out)
            req.method == "POST" && req.path == "/api/revoke" -> handleRevoke(req, out)
            // 远程暂停（PRD FR-14）：Owner/Member 都可请求，Guest 不能
            req.method == "POST" && req.path == "/api/pause" -> handlePause(req, out)
            // 双向语音与威慑（PRD FR-16）：对讲 / 警笛 / 手电，同属远程控制，Guest 不可用
            req.method == "POST" && req.path == "/api/talk" -> handleTalk(req, out)
            req.method == "POST" && req.path == "/api/siren" -> handleSiren(req, out)
            req.method == "POST" && req.path == "/api/torch" -> handleTorch(req, out)
            // 第三方集成（PRD FR-15）：仅集成开启时可访问，鉴权走 Basic 或配对令牌
            req.method == "GET" && req.path == "/web" -> handleConsole(socket, req, out)
            req.method == "GET" && req.path == "/api/integration" -> handleIntegrationState(socket, req, out)
            req.method == "POST" && req.path == "/api/integration/disable" -> handleIntegrationDisable(socket, req, out)
            // ONVIF Profile S（PRD FR-15）：NVR 先连 device_service 取能力，再按 Media 服务取流地址
            req.method == "POST" && req.path.startsWith("/onvif/") -> handleOnvif(socket, req, out)
            else -> respond(out, 404, errorBody("not_found"))
        }
    }

    /**
     * 公开信息只够“发现 + 判断能不能连”，不放事件数、片段、令牌等任何内容：
     * 局域网里任何设备都能请求它，泄露越多越容易成为被扫描的目标。
     */
    private fun handleInfo(req: Request, out: OutputStream) {
        val grants = pairing.activeGrants()
        val body = JSONObject()
            .put("id", pairing.fingerprint())
            .put("name", deviceName())
            .put("model", Build.MODEL)
            .put("appVersion", appVersion())
            .put("port", port)
            .put("mode", currentMode())
            .put("live", LanFrameHub.hasFrame())
            .put("hasOwner", grants.any { it.role == PairingStore.Role.OWNER })
            .put("lastEventAt", lastEventAt())
            .put("serverTime", System.currentTimeMillis())
        respond(out, 200, body.toString())
    }

    private fun handlePair(req: Request, out: OutputStream) {
        val body = runCatching { JSONObject(req.body) }.getOrNull()
            ?: return respond(out, 400, errorBody("bad_json"))
        val publicKey = body.optString("publicKey")
        if (publicKey.isEmpty()) return respond(out, 400, errorBody("missing_public_key"))
        val invite = body.optString("invite").takeIf { it.isNotEmpty() }
        // 上限检查放在消耗配对码之前：满员时不该先把用户手里的码作废
        if (pairing.atCapacity() && !pairing.hasGrant(publicKey)) {
            return respond(out, 403, errorBody("limit_reached"))
        }
        if (invite != null) {
            // 邀请令牌自带有效期，兑换成功即失效（PairingStore 负责），这里只做角色降级
            val grant = pairing.redeemInvite(invite, publicKey)
                ?: return respond(out, 403, errorBody("invalid_invite"))
            AuditLog.record(context, AuditLog.PAIR, "配对成功 role=${grant.role.raw} invite")
            return respond(out, 200, grantJson(grant).toString())
        }
        val code = body.optString("code")
        if (code.isEmpty()) return respond(out, 403, errorBody("missing_code"))
        // 首个配对者成为 Owner；之后一律 Member。Guest 只能走邀请，不能靠猜 6 位码获得
        val hasOwner = pairing.activeGrants().any { it.role == PairingStore.Role.OWNER }
        val role = if (hasOwner) PairingStore.Role.MEMBER else PairingStore.Role.OWNER
        val grant = pairing.pair(publicKey, role, 0L, code)
            ?: return respond(out, 403, errorBody("invalid_code"))
        // 审计只记角色与来源方式，不记配对码与令牌：日志一旦泄露不能变成第二把钥匙
        AuditLog.record(context, AuditLog.PAIR, "配对成功 role=${grant.role.raw} code")
        respond(out, 200, grantJson(grant).toString())
    }

    private fun grantJson(grant: PairingStore.Grant): JSONObject = JSONObject()
        .put("token", grant.token)
        .put("role", grant.role.raw)
        // 客户端必须拿到服务端公钥才能算出与服务端相同的会话密钥（ECDH），
        // 扫码时已知指纹，因此这里返回公钥不会削弱“核对防中间人”的强度
        .put("serverPublicKey", pairing.publicKeyBase64())
        .put("fingerprint", grant.fingerprint)
        .put("expiresAt", grant.expiresAt)

    private fun handleStatus(req: Request, out: OutputStream) {
        val grant = requireGrant(req, out) ?: return
        val body = JSONObject()
            .put("mode", currentMode())
            .put("battery", batteryPercent())
            .put("charging", isCharging())
            .put("thermal", thermalStatus())
            .put("storageFreeMb", storageFreeMb())
            .put("version", appVersion())
            .put("watching", viewers.get())
            .put("maxViewers", MAX_VIEWERS)
            .put("grants", pairing.activeGrants().size)
            .put("live", LanFrameHub.hasFrame())
            .put("lastEventAt", lastEventAt())
            // 心跳与降载标记由 CaptureService 写入：观看端据此区分“正常采集”“降载中”与“疑似中断”
            .put("heartbeatAt", GuardRuntime.heartbeatAt)
            .put("throttled", GuardRuntime.throttled)
            // 暂停状态与遮罩状态必须由观看端可见：不然用户只会看到"设备离线"，无从判断是被人为暂停
            .put("paused", GuardState.isPaused(context))
            .put("masked", LanFrameHub.maskEnabled())
            // 码率与集成开关都要能被自有观看端看到：用户需要判断“画面卡”是链路问题还是集成拉走了带宽
            .put("bitrateKbps", LanFrameHub.bitrateKbps)
            .put("integration", LanIntegration.isEnabled(context))
            .put("serverTime", System.currentTimeMillis())
        respond(out, 200, body.toString())
    }

    private fun handleEvents(req: Request, out: OutputStream) {
        requireGrant(req, out) ?: return
        val limit = (req.query["limit"]?.toIntOrNull() ?: DEFAULT_EVENT_LIMIT).coerceIn(1, MAX_EVENT_LIMIT)
        val type = req.query["type"]?.let { EventType.fromRaw(it) }
        val onlyDeleted = req.query["deleted"] == "1"
        val since = req.query["since"]?.toLongOrNull()
        // EventStore 的筛选支持类型/未读/锁定；since 只在这里做一次轻量过滤，
        // 事件量级（本地保留 7 天）远小于需要下推到 SQL 的规模
        val list = events.timeline(type, false, false, onlyDeleted, limit)
            .filter { since == null || it.occurredAt > since }
        val array = JSONArray()
        list.forEach { array.put(eventJson(it)) }
        respond(out, 200, JSONObject().put("events", array).toString())
    }

    private fun eventJson(event: Event): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.type.raw)
        .put("occurredAt", event.occurredAt)
        .put("confidence", event.confidence)
        .put("triggers", event.triggers)
        .put("status", event.status.raw)
        .put("read", event.read)
        .put("locked", event.locked)
        .put("synced", event.synced)
        .put("clipOffsetMs", event.clipOffsetMs)
        .put("hasThumbnail", event.thumbnail?.isFile == true)
        .put("hasClip", event.clip?.isFile == true || event.clipUri != null)
        .put("hasPrev", event.prevClip?.isFile == true || event.prevClipUri != null)

    private fun handleThumbnail(req: Request, out: OutputStream) {
        requireGrant(req, out) ?: return
        val id = req.query["id"]?.toLongOrNull()
            ?: return respond(out, 400, errorBody("missing_id"))
        val file = events.get(id)?.thumbnail?.takeIf { it.isFile }
            ?: return respond(out, 404, errorBody("thumbnail_not_found"))
        writeHead(out, 200, "image/jpeg", file.length())
        file.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
    }

    private fun handleClip(req: Request, out: OutputStream) {
        requireGrant(req, out) ?: return
        val id = req.query["id"]?.toLongOrNull()
            ?: return respond(out, 400, errorBody("missing_id"))
        val event = events.get(id) ?: return respond(out, 404, errorBody("event_not_found"))
        val part = req.query["part"] ?: "main"
        val file = if (part == "prev") event.prevClip else event.clip
        val uri = if (part == "prev") event.prevClipUri else event.clipUri
        when {
            file != null && file.isFile -> streamFile(req, out, file)
            uri != null -> streamContent(req, out, Uri.parse(uri))
            else -> respond(out, 404, errorBody("clip_not_found"))
        }
    }

    /**
     * 快照兜底（PRD FR-08 异常）：连接超时时观看端先展示最近画面再重试。
     * 相机还没出帧（停止值守/黑屏记录模式）时退回最近一条事件的缩略图，
     * 这两者都拿不到才报 404，让观看端明确显示“设备未在直播”。
     */
    private fun handleSnapshot(req: Request, out: OutputStream) {
        requireGrant(req, out, allowGuest = true) ?: return
        val main = req.query["quality"] == "main"
        val frame = LanFrameHub.snapshot(main)?.second
        if (frame != null) {
            writeHead(out, 200, "image/jpeg", frame.jpeg.size.toLong(), mapOf("X-Timestamp" to frame.atMs.toString()))
            out.write(frame.jpeg)
            return
        }
        val thumbnail = events.recent(1).firstOrNull()?.thumbnail?.takeIf { it.isFile }
            ?: return respond(out, 404, errorBody("no_snapshot"))
        writeHead(out, 200, "image/jpeg", thumbnail.length(), mapOf("X-Timestamp" to thumbnail.lastModified().toString()))
        thumbnail.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
    }

    /**
     * MJPEG 直播（PRD FR-15 / FR-08）。
     * 首帧先写缓存帧而不是等下一帧：CameraX 在低光下曝光时间可能超过 1 秒，
     * 等下一帧会让观看端白屏到 2 秒以上，直接违反“打开实时画面到首帧 ≤2 秒”。
     */
    private fun handleLive(socket: Socket, req: Request, out: OutputStream) {
        // 自有观看端用配对令牌（Guest 也可看实时）；集成开启后外部工具可用 Basic 接入，
        // 这条路径不受“集成未开启”的 404 限制，否则关闭第三方集成会连带打断自有观看端
        val grant = grantOf(req)
        if (grant == null && !requireIntegration(socket, req, out, requireEnabled = false)) return
        if (viewers.incrementAndGet() > MAX_VIEWERS) {
            viewers.decrementAndGet()
            // 第 6 人明确收到提示与上限值，而不是无声地转圈（PRD FR-08 异常：并发已满）
            return respond(
                out,
                429,
                JSONObject().put("error", "too_many_viewers").put("maxViewers", MAX_VIEWERS).toString()
            )
        }
        try {
            // 直播是长连接：请求读取超时在这里没有意义，写超时才由 Socket 层拦截
            socket.soTimeout = 0
            // quality=auto 先在主码流上试跑，写不动再降子码流：
            // 客户端无法预知链路余量，只有服务端写到卡住时才知道该降级
            val auto = req.query["quality"] == "auto"
            var main = auto || req.query["quality"] == "main"
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            var lastSequence = 0L
            val cached = LanFrameHub.snapshot(main)
            if (cached != null) {
                writeFrame(out, cached.second)
                lastSequence = cached.first
                out.flush()
            }
            var idleTicks = 0
            var lastAuthMs = System.currentTimeMillis()
            while (running) {
                // 撤销必须在下一次心跳内生效：Owner 踢人后若画面还在继续推送，
                // “撤销”就只是删了一条记录，PRD FR-14 的 5 秒内生效无从保证。
                // 第三方 Basic 连接没有配对令牌，只能靠关闭集成来收回（那条路径由 disable 踢出）
                if (grant != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastAuthMs >= AUTH_CHECK_MS) {
                        lastAuthMs = now
                        if (pairing.authenticate(grant.token) == null) break
                    }
                }
                val next = LanFrameHub.await(lastSequence, main, LIVE_WAIT_MS)
                if (next == null) {
                    // 无新帧可能是相机被停止或黑屏记录模式；连续多个空窗后关闭连接，
                    // 让观看端切到“最近快照 + 最后在线时间”而不是无限等待
                    if (++idleTicks >= LIVE_IDLE_TICKS) break
                    continue
                }
                idleTicks = 0
                val writeStart = System.nanoTime()
                writeFrame(out, next.second)
                lastSequence = next.first
                out.flush()
                // 一帧 JPEG 在局域网通常 <10ms；超过 300ms 说明链路已经跟不上主码流。
                // 只降不升：回升会与降级反复抖动，画面反而更卡
                if (auto && main && (System.nanoTime() - writeStart) / 1_000_000 >= DEGRADE_WRITE_MS) {
                    main = false
                }
            }
        } catch (e: IOException) {
            // 观看端主动断开是最常见的结束方式，不当作错误
        } finally {
            viewers.decrementAndGet()
        }
    }

    private fun writeFrame(out: OutputStream, frame: LanFrameHub.Frame) {
        val head = "--$BOUNDARY\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${frame.jpeg.size}\r\n" +
            "X-Timestamp: ${frame.atMs}\r\n\r\n"
        out.write(head.toByteArray())
        out.write(frame.jpeg)
        out.write("\r\n".toByteArray())
    }

    /** 观看端主动离开设备：只撤销自己这一条，不能借此踢掉其他成员 */
    private fun handleLeave(req: Request, out: OutputStream) {
        val grant = grantOf(req) ?: return respond(out, 401, errorBody("unauthorized"))
        pairing.revoke(grant.token)
        AuditLog.record(context, AuditLog.LEAVE, "观看端主动离开 role=${grant.role.raw}")
        respond(out, 200, JSONObject().put("ok", true).toString())
    }

    /** Owner 撤销成员（PRD FR-02 异常：原 Owner 确认解除），按指纹或令牌定位 */
    private fun handleRevoke(req: Request, out: OutputStream) {
        val grant = requireGrant(req, out) ?: return
        if (grant.role != PairingStore.Role.OWNER) {
            return respond(out, 403, errorBody("owner_required"))
        }
        val body = runCatching { JSONObject(req.body) }.getOrNull()
            ?: return respond(out, 400, errorBody("bad_json"))
        if (body.optBoolean("all")) {
            pairing.revokeAll()
            AuditLog.record(context, AuditLog.REVOKE, "撤销全部授权")
            return respond(out, 200, JSONObject().put("ok", true).toString())
        }
        val token = body.optString("token").takeIf { it.isNotEmpty() }
        if (token != null) {
            pairing.revoke(token)
            AuditLog.record(context, AuditLog.REVOKE, "撤销单个令牌")
            return respond(out, 200, JSONObject().put("ok", true).toString())
        }
        val fingerprint = body.optString("fingerprint").takeIf { it.isNotEmpty() }
            ?: return respond(out, 400, errorBody("missing_target"))
        // PairingStore 的撤销以令牌为单位；指纹是用户可见的稳定标识，这里做一次映射
        val targets = pairing.activeGrants().filter { it.fingerprint == fingerprint }
        targets.forEach { pairing.revoke(it.token) }
        AuditLog.record(context, AuditLog.REVOKE, "按指纹撤销 ${targets.size} 个授权")
        respond(out, 200, JSONObject().put("ok", true).put("revoked", targets.size).toString())
    }

    /** 观看端请求暂停采集：暂停动作由 CaptureService 在服务内完成，服务没在跑就没有可暂停的对象 */
    private fun handlePause(req: Request, out: OutputStream) {
        requireGrant(req, out) ?: return
        // 盲目返回 200 会让观看端提示“已暂停成功”，而设备其实早就停了——必须如实告诉它没有在采集
        if (!CaptureService.isRunning) return respond(out, 409, errorBody("not_running"))
        val intent = Intent(context, CaptureService::class.java)
            .setAction(CaptureService.ACTION_PAUSE)
        // 服务已在运行，这里只是给它发一个指令，不会触发后台启动限制
        runCatching { context.startService(intent) }
            .onFailure { return respond(out, 503, errorBody("pause_failed")) }
        respond(out, 200, JSONObject().put("ok", true).toString())
    }

    /**
     * 对讲（PRD FR-16）：观看端按住说话时按 0.5 秒一块上传 PCM，服务端顺序写入播放器。
     * 只在“从空闲变为播放”时记一条审计：每块都记会让一条对讲刷满整个日志。
     */
    private fun handleTalk(req: Request, out: OutputStream) {
        val grant = requireGrant(req, out) ?: return
        if (!CaptureService.isRunning) return respond(out, 409, errorBody("not_running"))
        if (req.bodyBytes.isEmpty()) return respond(out, 400, errorBody("empty_audio"))
        val wasIdle = !TalkPlayer.isPlaying()
        if (!TalkPlayer.feed(req.bodyBytes)) return respond(out, 503, errorBody("talk_failed"))
        if (wasIdle) {
            AuditLog.record(context, AuditLog.DETERRENT, "对讲开始 role=${grant.role.raw}")
            // 相机端唯一常驻可见面是前台通知；只在开始时刷新，逐块刷新会把通知变成噪声源
            CaptureService.showRemoteActivity(context.getString(R.string.capture_remote_talk))
        }
        respond(out, 200, JSONObject().put("ok", true).toString())
    }

    /** 警笛：10 秒自动停止，结束后冷却 30 秒，夜间禁用（PRD FR-16 处理规则） */
    private fun handleSiren(req: Request, out: OutputStream) {
        val grant = requireGrant(req, out) ?: return
        when (Deterrent.siren()) {
            Deterrent.Result.OK -> {
                AuditLog.record(context, AuditLog.DETERRENT, "警笛 人工触发 role=${grant.role.raw}")
                CaptureService.showRemoteActivity(context.getString(R.string.capture_remote_siren))
                respond(
                    out, 200,
                    JSONObject().put("ok", true).put("durationMs", Deterrent.DURATION_MS).toString()
                )
            }
            Deterrent.Result.NOT_RUNNING -> respond(out, 409, errorBody("not_running"))
            Deterrent.Result.NIGHT -> respond(out, 403, errorBody("night_disabled"))
            Deterrent.Result.COOLDOWN ->
                respond(out, 429, cooldownBody("cooldown", Deterrent.sirenCooldownRemainingMs()))
            Deterrent.Result.UNAVAILABLE -> respond(out, 503, errorBody("siren_unavailable"))
        }
    }

    /** 手电：与警笛共用冷却思路，但不做夜间禁用——夜间补光恰恰是最常用的场景 */
    private fun handleTorch(req: Request, out: OutputStream) {
        val grant = requireGrant(req, out) ?: return
        when (Deterrent.torch()) {
            Deterrent.Result.OK -> {
                AuditLog.record(context, AuditLog.DETERRENT, "手电 人工触发 role=${grant.role.raw}")
                CaptureService.showRemoteActivity(context.getString(R.string.capture_remote_torch))
                respond(
                    out, 200,
                    JSONObject().put("ok", true).put("durationMs", Deterrent.DURATION_MS).toString()
                )
            }
            Deterrent.Result.NOT_RUNNING -> respond(out, 409, errorBody("not_running"))
            Deterrent.Result.NIGHT -> respond(out, 403, errorBody("night_disabled"))
            Deterrent.Result.COOLDOWN ->
                respond(out, 429, cooldownBody("cooldown", Deterrent.torchCooldownRemainingMs()))
            Deterrent.Result.UNAVAILABLE -> respond(out, 503, errorBody("torch_unavailable"))
        }
    }

    /** 冷却响应要带剩余秒数：观看端才能说出“再等 12 秒”，而不是一句笼统的失败 */
    private fun cooldownBody(code: String, remainingMs: Long): String = JSONObject()
        .put("error", code)
        .put("retryAfterSec", remainingMs / 1000 + 1)
        .toString()

    /**
     * 局域网 Web 控制台（PRD FR-15）。页面内的相对请求会复用浏览器缓存的 Basic 凭据，
     * 因此模板里不需要写入令牌——令牌一旦出现在 HTML 里，截图分享就会变成新的泄露路径。
     */
    private fun handleConsole(socket: Socket, req: Request, out: OutputStream) {
        if (!requireIntegration(socket, req, out)) return
        val html = runCatching {
            context.resources.openRawResource(R.raw.console).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return respond(out, 500, errorBody("console_unavailable"))
        respond(out, 200, html, HTML_TYPE)
    }

    /** 控制台的状态轮询入口：只暴露运行态与计数，不含配对列表、事件内容与令牌 */
    private fun handleIntegrationState(socket: Socket, req: Request, out: OutputStream) {
        if (!requireIntegration(socket, req, out)) return
        val body = JSONObject()
            .put("enabled", LanIntegration.isEnabled(context))
            .put("username", LanIntegration.USERNAME)
            .put("mode", currentMode())
            .put("live", LanFrameHub.hasFrame())
            .put("paused", GuardState.isPaused(context))
            .put("masked", LanFrameHub.maskEnabled())
            .put("viewers", viewers.get())
            .put("maxViewers", MAX_VIEWERS)
            .put("bitrateKbps", LanFrameHub.bitrateKbps)
            .put("thirdPartyClients", thirdParty.size)
            .put("lockRemainingSec", LanIntegration.lockRemainingMs(context) / 1000L)
            .put("battery", batteryPercent())
            .put("charging", isCharging())
            .put("thermal", thermalStatus())
            .put("serverTime", System.currentTimeMillis())
        respond(out, 200, body.toString())
    }

    /**
     * ONVIF SOAP 入口（PRD FR-15）。认证与 Web 控制台完全一致，
     * 且设备发现（WS-Discovery）也只在集成开启时才应答，避免未授权探测拿到设备存在的信息。
     */
    private fun handleOnvif(socket: Socket, req: Request, out: OutputStream) {
        if (!requireIntegration(socket, req, out)) return
        // Host 头里的地址才是客户端实际连得到的地址；设备可能有多张网卡，写死 Wi-Fi IP 会让有线端拉不到流
        val host = req.header("host")?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: lanIp()
        val soap = OnvifService.handle(req.body, host, port, RtspServer.port)
        respond(out, 200, soap, SOAP_TYPE)
    }

    /**
     * 一键关闭并踢出所有第三方客户端（PRD FR-15 结果与状态）。
     * 只关集成开关、只断 Basic 连接：自有观看端继续可用，值守不会被误中断。
     */
    private fun handleIntegrationDisable(socket: Socket, req: Request, out: OutputStream) {
        if (!requireIntegration(socket, req, out)) return
        LanIntegration.setEnabled(context, false)
        // 请求方自己不能被提前关掉，否则后面的 200 响应没有接收方
        val kicked = kickThirdParty(except = socket)
        AuditLog.record(context, AuditLog.INTEGRATION, "关闭第三方集成并踢出 $kicked 个连接")
        respond(out, 200, JSONObject().put("ok", true).put("kicked", kicked).toString())
    }

    /**
     * 第三方鉴权（PRD FR-15）。配对令牌与 Basic 凭据任一通过即可，两条路径都失败才拒绝：
     * 自有观看端用户不该为了看控制台再记一份密码，外部工具也不该被迫走配对流程。
     *
     * 缺凭据只回 401 + WWW-Authenticate（浏览器据此弹登录框），不计数：
     * 否则用户刷新一次页面就消耗掉一半失败额度，5 次锁定会变成“打开两次就锁”。
     */
    private fun requireIntegration(
        socket: Socket,
        req: Request,
        out: OutputStream,
        requireEnabled: Boolean = true,
    ): Boolean {
        if (requireEnabled && !LanIntegration.isEnabled(context)) {
            // 未开启时连“这里有什么”都不透露，避免成为局域网扫描的固定指路灯
            respond(out, 404, errorBody("not_found"))
            return false
        }
        if (grantOf(req) != null) return true
        val credentials = basicCredentials(req)
        if (credentials == null) {
            // ONVIF 客户端默认发 SOAP WS-UsernameToken 而不是 HTTP Basic；
            // 只对 /onvif/ 放行这条通道，其他端点仍强制 Basic，避免扩大绕过面
            if (req.path.startsWith("/onvif/") && onvifTokenOk(req.body)) {
                thirdParty += socket
                return true
            }
            writeHead(
                out, 401, JSON_TYPE, null,
                mapOf("WWW-Authenticate" to "Basic realm=\"QuietSentry\", charset=\"UTF-8\"")
            )
            return false
        }
        when (LanIntegration.verify(context, credentials.first, credentials.second)) {
            LanIntegration.Result.OK -> {
                thirdParty += socket
                return true
            }

            LanIntegration.Result.LOCKED -> {
                val remain = (LanIntegration.lockRemainingMs(context) / 1000L).coerceAtLeast(1L)
                respond(
                    out, 429,
                    JSONObject().put("error", "auth_locked").put("retryAfterSec", remain).toString()
                )
                return false
            }

            LanIntegration.Result.INVALID -> {
                respond(out, 401, errorBody("unauthorized"))
                return false
            }
        }
    }

    private fun basicCredentials(req: Request): Pair<String, String>? {
        val header = req.header("authorization") ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = runCatching {
            Base64.decode(header.substring(6).trim(), Base64.NO_WRAP).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        val colon = decoded.indexOf(':')
        if (colon < 0) return null
        return decoded.substring(0, colon) to decoded.substring(colon + 1)
    }

    /**
     * WS-Security UsernameToken 校验（ONVIF Profile S）。
     * 支持 PasswordText 与 PasswordDigest：HA 默认 Digest，部分 NVR 用 Text。
     * 密码来源与 Web 控制台一致，仍是集成令牌，不新增第二套凭据。
     */
    private fun onvifTokenOk(body: String): Boolean {
        val user = Regex("<(?:[A-Za-z0-9]+:)?Username>([^<]+)<").find(body)
            ?.groupValues?.get(1)?.trim() ?: return false
        if (!MessageDigest.isEqual(user.toByteArray(), LanIntegration.USERNAME.toByteArray())) return false
        val passwordText = Regex("<(?:[A-Za-z0-9]+:)?PasswordText>([^<]+)<").find(body)
            ?.groupValues?.get(1)?.trim()
        if (passwordText != null) {
            return LanIntegration.verify(context, user, passwordText) == LanIntegration.Result.OK
        }
        val digest = Regex("<(?:[A-Za-z0-9]+:)?Password>([^<]+)<").find(body)
            ?.groupValues?.get(1)?.trim() ?: return false
        val nonceRaw = Regex("<(?:[A-Za-z0-9]+:)?Nonce>([^<]+)<").find(body)
            ?.groupValues?.get(1)?.trim() ?: return false
        val created = Regex("<(?:[A-Za-z0-9]+:)?Created>([^<]+)<").find(body)
            ?.groupValues?.get(1)?.trim() ?: return false
        val nonce = runCatching { Base64.decode(nonceRaw, Base64.NO_WRAP) }.getOrNull() ?: return false
        val raw = nonce +
            created.toByteArray(Charsets.UTF_8) +
            LanIntegration.token(context).toByteArray(Charsets.UTF_8)
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(raw),
            Base64.NO_WRAP
        )
        return MessageDigest.isEqual(expected.toByteArray(), digest.toByteArray())
    }

    private fun grantOf(req: Request): PairingStore.Grant? {
        // 浏览器/播放器不方便自定义头，因此同时接受 query token；
        // 两者都在时优先头，避免 URL 日志里出现有效令牌时仍然依赖它
        val token = req.header("x-auth-token") ?: req.query["token"] ?: return null
        return pairing.authenticate(token)
    }

    private fun requireGrant(req: Request, out: OutputStream, allowGuest: Boolean = false): PairingStore.Grant? {
        val grant = grantOf(req)
        if (grant == null) {
            respond(out, 401, errorBody("unauthorized"))
            return null
        }
        if (!allowGuest && grant.role == PairingStore.Role.GUEST) {
            respond(out, 403, errorBody("forbidden"))
            return null
        }
        return grant
    }

    /** 私有片段直接按文件流；浏览器/播放器拖动依赖 Range，不能整段先读进内存 */
    private fun streamFile(req: Request, out: OutputStream, file: File) {
        val total = file.length()
        val range = parseRange(req.header("range"), total)
        if (range == null) {
            writeHead(out, 200, "video/mp4", total, mapOf("Accept-Ranges" to "bytes"))
            file.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
            return
        }
        val length = range.last - range.first + 1
        writeHead(
            out, 206, "video/mp4", length,
            mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.first}-${range.last}/$total")
        )
        file.inputStream().use { input ->
            input.skipFully(range.first)
            copyLimited(input, out, length)
        }
    }

    /** 导出到相册后的片段只有 content:// 定位，用 AFD 的偏移实现同等的 Range 语义 */
    private fun streamContent(req: Request, out: OutputStream, uri: Uri) {
        val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
            ?: return respond(out, 404, errorBody("clip_not_found"))
        afd.use {
            val total = it.length
            if (total <= 0L) {
                // 长度未知时退化为整段传输：客户端按连接关闭判定结束
                writeHead(out, 200, "video/mp4", null)
                it.createInputStream().use { input -> input.copyTo(out, BUFFER_SIZE) }
                return
            }
            val range = parseRange(req.header("range"), total)
            if (range == null) {
                writeHead(out, 200, "video/mp4", total, mapOf("Accept-Ranges" to "bytes"))
                it.createInputStream().use { input -> input.copyTo(out, BUFFER_SIZE) }
                return
            }
            val length = range.last - range.first + 1
            writeHead(
                out, 206, "video/mp4", length,
                mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.first}-${range.last}/$total")
            )
            it.createInputStream().use { input ->
                // createInputStream 已经定位在 AssetFileDescriptor 的 startOffset，
                // 再叠加一次 startOffset 会让 Range 请求整体后移，播放器表现为“拖动后花屏/跳段”
                input.skipFully(range.first)
                copyLimited(input, out, length)
            }
        }
    }

    private fun parseRange(header: String?, total: Long): LongRange? {
        if (header == null || total <= 0L || !header.startsWith("bytes=")) return null
        // 只支持单范围；多范围需要 multipart/byteranges，播放器通常只发单范围
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startRaw = spec.substring(0, dash).trim()
        val endRaw = spec.substring(dash + 1).trim()
        val start: Long
        val end: Long
        if (startRaw.isEmpty()) {
            val suffix = endRaw.toLongOrNull() ?: return null
            start = (total - suffix).coerceAtLeast(0L)
            end = total - 1
        } else {
            start = startRaw.toLongOrNull() ?: return null
            end = if (endRaw.isEmpty()) total - 1 else (endRaw.toLongOrNull() ?: return null)
        }
        if (start > end || start >= total) return null
        return start..end.coerceAtMost(total - 1)
    }

    private fun copyLimited(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip() 在服务端流上可能返回 0，必须真正读掉一个字节避免死循环
            if (read() < 0) return
            remaining--
        }
    }

    private fun respond(out: OutputStream, status: Int, body: String, contentType: String = JSON_TYPE) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, status, contentType, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeHead(
        out: OutputStream,
        status: Int,
        contentType: String,
        length: Long?,
        extra: Map<String, String> = emptyMap(),
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        builder.append("Content-Type: ").append(contentType).append("\r\n")
        if (length != null) builder.append("Content-Length: ").append(length).append("\r\n")
        extra.forEach { (key, value) -> builder.append(key).append(": ").append(value).append("\r\n") }
        builder.append("Cache-Control: no-store\r\n")
        builder.append("Connection: close\r\n\r\n")
        out.write(builder.toString().toByteArray(Charsets.UTF_8))
    }

    private fun errorBody(code: String): String = JSONObject().put("error", code).toString()

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        else -> "Error"
    }

    private fun deviceName(): String = Build.MODEL

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "1.0"

    private fun currentMode(): String = if (CaptureService.isRunning) "guard" else "idle"

    private fun lastEventAt(): Long = events.recent(1).firstOrNull()?.occurredAt ?: 0L

    private fun storageFreeMb(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    private fun batteryPercent(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * 具体温度读数需要系统权限（HardwarePropertiesManager），普通 App 只能拿热状态等级。
     * 这里必须返回数字档位（0-6，与 PowerManager.THERMAL_STATUS_* 一致）：
     * 之前返回 "moderate" 这类字符串，观看端用 optInt 读到的永远是 0，过热状态从未真正出现过。
     */
    private fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val power = context.getSystemService(PowerManager::class.java) ?: return 0
        return power.currentThermalStatus
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
        /** 对讲上传的是 PCM 二进制，按 UTF-8 解码会丢字节，必须同时保留原始字节 */
        val bodyBytes: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        companion object {
            fun parse(input: InputStream): Request? {
                val requestLine = readLine(input) ?: return null
                val parts = requestLine.split(' ')
                if (parts.size < 2) return null
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = HashMap<String, String>()
                target.substringAfter('?', "").split('&').forEach { pair ->
                    if (pair.isEmpty()) return@forEach
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    query[runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)] =
                        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                }
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                val declared = headers["content-length"]?.toIntOrNull() ?: 0
                // 配对请求体只有几十字节；把上限写死，避免被一个超大 body 撑爆内存
                if (declared > MAX_BODY_BYTES) return null
                val raw = if (declared > 0) readExactly(input, declared) ?: return null else ByteArray(0)
                return Request(
                    parts[0].uppercase(), path, query, headers,
                    String(raw, Charsets.UTF_8), raw
                )
            }

            private fun readLine(input: InputStream): String? {
                val builder = StringBuilder()
                while (true) {
                    val value = input.read()
                    if (value < 0) return if (builder.isEmpty()) null else builder.toString()
                    // 这里只按 LF 判定；同时去掉 CR，兼容 CRLF 与纯 LF 的客户端
                    if (value == '\n'.code) return builder.toString().trimEnd('\r')
                    if (value == '\r'.code) continue
                    builder.append(value.toChar())
                    if (builder.length > MAX_LINE_BYTES) return null
                }
            }

            private fun readExactly(input: InputStream, length: Int): ByteArray? {
                val buffer = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(buffer, offset, length - offset)
                    if (read < 0) return null
                    offset += read
                }
                return buffer
            }
        }
    }

    private companion object {
        private const val TAG = "QuietSentry"

        /** 8081 起避开常见的 8080 冲突；段内全占用才失败，观看端通过 mDNS 拿到真实端口 */
        private val PORT_RANGE = 8080..8099
        private const val BACKLOG = 16
        private const val MAX_THREADS = 8

        /** PRD FR-08：单台摄像头并发观看上限 5 人 */
        private const val MAX_VIEWERS = 5

        private const val READ_TIMEOUT_MS = 15_000

        /**
         * 取帧等待切到 1 秒一次：这是令牌心跳的节拍上限。
         * 若沿用 5 秒一次，撤销最长要 5 秒才被发现，再加上写入失败重试就超出 PRD 的 5 秒窗口。
         */
        private const val LIVE_WAIT_MS = 1_000L

        /** 连续 15 个空窗（15 秒）无新帧即关闭直播连接，让观看端落到“设备未在直播” */
        private const val LIVE_IDLE_TICKS = 15

        /** 直播中的令牌复查间隔；撤销后最多 2 秒断开（含取帧等待） */
        private const val AUTH_CHECK_MS = 2_000L

        /** 自动画质降级阈值：单帧写入超过该耗时即认为链路跟不上主码流 */
        private const val DEGRADE_WRITE_MS = 300L

        private const val DEFAULT_EVENT_LIMIT = 50
        private const val MAX_EVENT_LIMIT = 200
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private const val HTML_TYPE = "text/html; charset=utf-8"
        /** ONVIF 客户端（Frigate/HA）按 SOAP 1.2 的响应类型解析，回 json 会直接判为设备不可用 */
        private const val SOAP_TYPE = "application/soap+xml; charset=utf-8"
        private const val BOUNDARY = "quietsentry-frame"
    }
}
