package com.quietsentry.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 观看端的设备列表（PRD FR-03）。
 *
 * 状态刷新走 5 秒轮询而不是推送：相机端已经把每个请求限制在 3 秒内，
 * 局域网里最多 5 台设备并发拉取的开销远低于维持一条长连接的心跳与重连逻辑。
 * 列表只在页面可见时轮询，退到后台立即停止，不为了“设备列表”常驻耗电。
 */
class DeviceListActivity : AppCompatActivity() {

    private lateinit var registry: DeviceRegistry
    private lateinit var pairing: PairingStore
    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var hintText: TextView

    private val io = Executors.newFixedThreadPool(DeviceRegistry.MAX_DEVICES)
    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次拉取结果；null 表示这台设备当前不可达，不缓存过期状态 */
    private val statuses = HashMap<String, DeviceHttp.Status?>()
    private var discovery: LanDiscovery? = null
    private var stopped = false

    /**
     * 宽屏双栏（PRD §12.3：左侧设备列表、右侧实时画面）下才存在的控件。
     * 窄屏布局里没有这些 id，findViewById 会返回 null，所有使用点都必须判空。
     */
    private var previewImage: ImageView? = null
    private var previewStatus: TextView? = null
    private var previewTitle: TextView? = null
    private var preview: LiveStreamer? = null
    private var previewDeviceId: String? = null

    /** 每次切换预览设备都递增：旧流的回调即使已经排队到主线程，也会因为代号过期被丢弃 */
    private var previewGeneration = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)
        registry = DeviceRegistry(this)
        // 本机身份同时用于配对，与摄像头端用的是一套密钥，避免维护两份“我是谁”
        pairing = PairingStore(this)
        container = findViewById(R.id.device_list_container)
        emptyText = findViewById(R.id.device_list_empty)
        hintText = findViewById(R.id.device_list_hint)

        findViewById<Button>(R.id.device_list_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.device_list_add).setOnClickListener { addDevice() }

        // 只有 sw600dp 布局提供右侧画面面板；窄屏下这几个 id 不存在，取到的是 null
        previewImage = findViewById(R.id.device_preview_image)
        previewStatus = findViewById(R.id.device_preview_status)
        previewTitle = findViewById(R.id.device_preview_title)
        findViewById<Button>(R.id.device_preview_full)?.setOnClickListener {
            val id = previewDeviceId ?: return@setOnClickListener
            registry.all().firstOrNull { it.id == id }?.let { openFullScreen(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        handler.post(pollRunnable)
        // 与全屏页同一策略：退后台停流，回前台自动重连，不在看不见的时候继续解码耗电
        if (previewDeviceId != null) preview?.start(QUALITY_AUTO)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
        preview?.stop()
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        discovery?.stop()
        discovery = null
        preview?.release()
        preview = null
        io.shutdownNow()
        super.onDestroy()
    }

    private fun poll() {
        val devices = registry.all()
        if (devices.isEmpty()) {
            render()
            return
        }
        val now = System.currentTimeMillis()
        devices.forEach { device ->
            io.execute {
                val status = runCatching { DeviceHttp.status(device.host, device.port, device.token) }
                    .getOrNull()
                synchronized(statuses) { statuses[device.id] = status }
                // 只有拿到状态才算“见过”：设备离线时 lastSeenAt 保持不变，界面才能显示离线了多久
                if (status != null) registry.markSeen(device.id, now, status.lastEventAt)
                if (!stopped) runOnUiThread { render() }
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val ordered = registry.all()
            .map { it to stateOf(it, now) }
            // 状态优先级先于用户排序：PRD 要求“状态优先”，告警与离线设备必须浮到最上面
            .sortedWith(compareBy({ it.second.level.ordinal }, { it.first.order }))

        container.removeAllViews()
        emptyText.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
        hintText.visibility = if (ordered.isEmpty()) View.GONE else View.VISIBLE
        // 预览设备被移除（或被别的页面删掉）后右侧面板不能继续显示上一次的画面
        if (previewDeviceId != null && ordered.none { it.first.id == previewDeviceId }) clearPreview()
        ordered.forEach { (device, state) -> container.addView(row(device, state, now)) }
    }

    private fun stateOf(device: DeviceRegistry.Device, now: Long): DeviceState.View {
        val status = synchronized(statuses) { statuses[device.id] }
        // 在线设备直接用“现在”作为最近在线时间；持久化的 lastSeenAt 最多每 30 秒才落盘一次，
        // 拿它显示会让在线设备看起来永远停在半分钟前
        val seenAt = if (status != null) now else device.lastSeenAt
        return DeviceState.evaluate(status, device.lastEventAt, seenAt, now)
    }

    private fun row(device: DeviceRegistry.Device, state: DeviceState.View, now: Long): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_device, container, false)
        view.findViewById<TextView>(R.id.device_cover).text = device.name.take(1).ifEmpty { "?" }
        view.findViewById<TextView>(R.id.device_name).text = device.name
        val room = device.room.ifEmpty { getString(R.string.device_room_unset) }
        view.findViewById<TextView>(R.id.device_meta).text = getString(
            R.string.device_card_meta,
            room,
            getString(stateLabel(state.level))
        )
        view.findViewById<TextView>(R.id.device_state).apply {
            text = getString(stateLabel(state.level))
            setTextColor(stateColor(state.level))
        }
        view.findViewById<TextView>(R.id.device_detail).text = detailText(state, now)
        // 双栏里列表和右侧画面是同一件事的两半，选中卡片必须能一眼看出来
        if (device.id == previewDeviceId) view.setBackgroundColor(getColor(R.color.qs_accent_soft))
        view.setOnClickListener { openLive(device) }
        // 管理动作放进长按：卡片的主用途是看画面，点一下就弹菜单会挡住最常见的操作
        view.setOnLongClickListener {
            showActions(device)
            true
        }
        return view
    }

    /**
     * 宽屏双栏（PRD §12.3）里点卡片不跳页，直接在右侧换流：用户比对两台设备时不用反复进出全屏页。
     * 窄屏没有双栏空间，仍然按原路径打开全屏观看页。
     */
    private fun openLive(device: DeviceRegistry.Device) {
        if (previewImage == null) {
            openFullScreen(device)
        } else {
            showPreview(device)
        }
    }

    private fun openFullScreen(device: DeviceRegistry.Device) {
        startActivity(
            Intent(this, LiveViewActivity::class.java)
                .putExtra(LiveViewActivity.EXTRA_DEVICE_ID, device.id)
        )
    }

    private fun showPreview(device: DeviceRegistry.Device) {
        previewGeneration++
        val gen = previewGeneration
        previewTitle?.text = device.name
        previewStatus?.visibility = View.VISIBLE
        previewStatus?.setText(R.string.device_preview_connecting)
        preview?.release()
        preview = LiveStreamer(
            device.host, device.port, device.token,
            object : LiveStreamer.Listener {
                override fun onState(state: LiveStreamer.State) {
                    // 换设备后旧流的回调可能已经在主线程队列里，代号比对是最后一道保险
                    if (gen != previewGeneration) return
                    runOnUiThread { if (!stopped) renderPreviewState(state) }
                }

                override fun onFrame(frame: Bitmap) {
                    if (gen != previewGeneration) return
                    runOnUiThread { if (!stopped) previewImage?.setImageBitmap(frame) }
                }
            }
        )
        previewDeviceId = device.id
        if (!stopped) preview?.start(QUALITY_AUTO)
        // 立即重绘一次，选中高亮不用等下一轮 5 秒轮询
        render()
    }

    private fun renderPreviewState(state: LiveStreamer.State) {
        val status = previewStatus ?: return
        when (state) {
            LiveStreamer.State.CONNECTED -> status.visibility = View.GONE
            LiveStreamer.State.CONNECTING -> {
                status.visibility = View.VISIBLE
                status.setText(R.string.device_preview_connecting)
            }
            LiveStreamer.State.RETRY -> {
                status.visibility = View.VISIBLE
                status.setText(R.string.live_status_retry)
            }
            LiveStreamer.State.UNAUTHORIZED -> {
                status.visibility = View.VISIBLE
                status.setText(R.string.live_status_unauthorized)
            }
            LiveStreamer.State.BUSY -> {
                status.visibility = View.VISIBLE
                status.text = getString(R.string.live_status_busy, MAX_VIEWERS)
            }
            LiveStreamer.State.OFFLINE -> {
                status.visibility = View.VISIBLE
                status.setText(R.string.live_status_offline)
            }
        }
    }

    /** 预览设备被移除后必须连同流一起清掉，否则右侧会一直对着空面板解码耗电 */
    private fun clearPreview() {
        previewGeneration++
        preview?.release()
        preview = null
        previewDeviceId = null
        previewImage?.setImageDrawable(null)
        previewTitle?.text = ""
        previewStatus?.visibility = View.VISIBLE
        previewStatus?.setText(R.string.device_preview_hint)
    }

    private fun detailText(state: DeviceState.View, now: Long): String {
        val parts = mutableListOf<String>()
        if (state.level == DeviceState.Level.OFFLINE) {
            parts += getString(R.string.device_card_offline_for, relativeTime(state.seenAt, now))
            // PRD FR-03 异常：离线超过 24 小时要给出“长期离线”，普通“3 天前”容易被当成刚掉线
            if (now - state.seenAt >= LONG_OFFLINE_MS) {
                parts += getString(R.string.device_card_long_offline)
            }
        } else {
            parts += when {
                state.battery < 0 -> getString(R.string.device_card_unknown_battery)
                state.charging -> getString(R.string.device_card_charging, state.battery)
                else -> getString(R.string.device_card_battery, state.battery)
            }
            if (state.watching > 0) {
                parts += getString(R.string.device_card_watching, state.watching, state.maxViewers)
            }
            // 心跳断了说明采集已停但服务还在应答，这比“离线”更容易被忽略，必须显式提示
            if (state.heartbeatStale) parts += getString(R.string.device_card_interrupted)
            if (state.throttled) parts += getString(R.string.device_card_throttled)
        }
        parts += getString(R.string.device_card_last_event, relativeTime(state.lastEventAt, now))
        return parts.joinToString(" · ")
    }

    private fun showActions(device: DeviceRegistry.Device) {
        val items = arrayOf(
            getString(R.string.device_action_rename),
            getString(R.string.device_action_room),
            getString(R.string.device_action_move_up),
            getString(R.string.device_action_move_down),
            getString(R.string.device_action_remove),
            getString(R.string.remote_device_action),
        )
        AlertDialog.Builder(this)
            .setTitle(device.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptText(R.string.device_rename_title, device.name) {
                        registry.rename(device.id, it)
                        render()
                    }
                    1 -> promptText(R.string.device_room_title, device.room) {
                        registry.setRoom(device.id, it)
                        render()
                    }
                    2 -> {
                        registry.move(device.id, -1)
                        render()
                    }
                    3 -> {
                        registry.move(device.id, 1)
                        render()
                    }
                    5 -> startActivity(
                        // 把已知地址带过去，离线设备打开就能直接看到“到底卡在哪一段”
                        Intent(this, RemoteAccessActivity::class.java)
                            .putExtra(RemoteAccessActivity.EXTRA_HOST, device.host)
                            .putExtra(RemoteAccessActivity.EXTRA_PORT, device.port)
                    )
                    else -> confirmRemove(device)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptText(titleRes: Int, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onOk(value)
            }
            .show()
    }

    private fun confirmRemove(device: DeviceRegistry.Device) {
        AlertDialog.Builder(this)
            .setTitle(R.string.device_remove_title)
            .setMessage(R.string.device_remove_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.device_action_remove) { _, _ ->
                // 先交还令牌再删本地记录：顺序反过来，令牌会永远留在相机端的授权列表里
                io.execute { DeviceHttp.leave(device.host, device.port, device.token) }
                registry.remove(device.id)
                synchronized(statuses) { statuses.remove(device.id) }
                Toast.makeText(this, R.string.device_removed, Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
    }

    /**
     * 添加设备：先让 mDNS 发现跑一个短窗口，再让用户选择。
     * 不做“一直扫描直到有结果”：局域网里可能有很多台设备，长时间扫描既耗电，
     * 也让用户不确定到底什么时候能选。
     */
    private fun addDevice() {
        if (registry.count() >= DeviceRegistry.MAX_DEVICES) {
            Toast.makeText(
                this,
                getString(R.string.device_list_limit, DeviceRegistry.MAX_DEVICES),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val peers = mutableListOf<LanDiscovery.Peer>()
        val scanner = LanDiscovery(this)
        discovery?.stop()
        discovery = scanner
        Toast.makeText(this, R.string.device_list_discovering, Toast.LENGTH_SHORT).show()
        scanner.start(
            onFound = { peer ->
                if (peers.none { it.serviceName == peer.serviceName }) peers += peer
            },
            onLost = { peer -> peers.removeAll { it.serviceName == peer.serviceName } }
        )
        handler.postDelayed({
            scanner.stop()
            if (discovery === scanner) discovery = null
            showPeerPicker(peers)
        }, DISCOVERY_WINDOW_MS)
    }

    private fun showPeerPicker(peers: List<LanDiscovery.Peer>) {
        if (peers.isEmpty()) {
            Toast.makeText(this, R.string.device_list_no_device, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = peers.map { "${it.name}\n${it.host}:${it.port}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.device_list_pick)
            .setItems(labels.toTypedArray()) { _, which -> promptCode(peers[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptCode(peer: LanDiscovery.Peer) {
        val input = EditText(this).apply {
            hint = getString(R.string.device_list_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_list_code_title, peer.name))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.device_list_pair) { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) pairWith(peer, code)
            }
            .show()
    }

    private fun pairWith(peer: LanDiscovery.Peer, code: String) {
        io.execute {
            val info = DeviceHttp.info(peer.host, peer.port)
            val result = DeviceHttp.pair(peer.host, peer.port, pairing.publicKeyBase64(), code)
            runOnUiThread {
                if (info == null || result == null) {
                    Toast.makeText(this, R.string.device_list_pair_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // 发现记录的指纹与服务端自报不一致时拒绝：同名服务可以任意注册，
                // 只凭服务名会把令牌交给一台伪装设备
                if (peer.fingerprint.isNotEmpty() && peer.fingerprint != info.id) {
                    Toast.makeText(this, R.string.device_list_pair_mismatch, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val added = registry.add(
                    DeviceRegistry.Device(
                        // 用服务端公钥指纹作为身份：IP 与端口都会变，指纹不会
                        id = info.id,
                        name = if (peer.name.isBlank()) info.name else peer.name,
                        room = "",
                        host = peer.host,
                        port = peer.port,
                        token = result.token,
                        order = 0,
                        lastSeenAt = System.currentTimeMillis(),
                        lastEventAt = info.lastEventAt,
                    )
                )
                if (added) {
                    Toast.makeText(this, R.string.device_list_paired, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.device_list_limit, DeviceRegistry.MAX_DEVICES),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                render()
            }
        }
    }

    private fun stateLabel(level: DeviceState.Level): Int = when (level) {
        DeviceState.Level.ALERT -> R.string.state_alert
        DeviceState.Level.OFFLINE -> R.string.state_offline
        DeviceState.Level.LOW_BATTERY -> R.string.state_low_battery
        DeviceState.Level.OVERHEAT -> R.string.state_overheat
        DeviceState.Level.STORAGE -> R.string.state_storage
        DeviceState.Level.GUARDING -> R.string.state_guarding
        DeviceState.Level.IDLE -> R.string.state_idle
    }

    private fun stateColor(level: DeviceState.Level): Int = when (level) {
        DeviceState.Level.ALERT, DeviceState.Level.OVERHEAT -> getColor(R.color.qs_danger)
        DeviceState.Level.LOW_BATTERY, DeviceState.Level.STORAGE -> getColor(R.color.qs_warn)
        DeviceState.Level.GUARDING -> getColor(R.color.qs_ok)
        DeviceState.Level.OFFLINE, DeviceState.Level.IDLE -> getColor(R.color.qs_idle)
    }

    private fun relativeTime(at: Long, now: Long): String {
        if (at <= 0L) return getString(R.string.device_time_never)
        val diff = now - at
        return when {
            diff < MINUTE_MS -> getString(R.string.device_time_just_now)
            diff < HOUR_MS -> getString(R.string.device_time_minutes, diff / MINUTE_MS)
            diff < DAY_MS -> getString(R.string.device_time_hours, diff / HOUR_MS)
            else -> getString(R.string.device_time_days, diff / DAY_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val DISCOVERY_WINDOW_MS = 5_000L
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        const val LONG_OFFLINE_MS = DAY_MS

        /** 双栏预览默认走子码流：右侧面板小，原画只会让更多设备撞上并发达上限 */
        const val QUALITY_AUTO = "auto"

        // 服务端 MAX_VIEWERS 固定为 5，这里只用于把 429 翻译成人话
        const val MAX_VIEWERS = 5
    }
}
