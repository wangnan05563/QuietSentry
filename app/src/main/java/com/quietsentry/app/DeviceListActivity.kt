package com.quietsentry.app

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        discovery?.stop()
        discovery = null
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
        view.setOnClickListener { showActions(device) }
        return view
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
        DeviceState.Level.ALERT, DeviceState.Level.OVERHEAT -> 0xFFFF5252.toInt()
        DeviceState.Level.LOW_BATTERY, DeviceState.Level.STORAGE -> 0xFFFFB74D.toInt()
        DeviceState.Level.GUARDING -> 0xFF4CD964.toInt()
        DeviceState.Level.OFFLINE, DeviceState.Level.IDLE -> 0xFF9E9E9E.toInt()
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
    }
}
