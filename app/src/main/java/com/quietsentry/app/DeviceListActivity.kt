package com.quietsentry.app

import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    /** 空态盒与离线提示条仅窄屏布局存在，宽屏双栏下为 null，使用点全部判空 */
    private var emptyBox: View? = null
    private var offlineBar: View? = null

    private val io = Executors.newFixedThreadPool(DeviceRegistry.MAX_DEVICES)
    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次拉取结果；null 表示这台设备当前不可达，不缓存过期状态 */
    private val statuses = HashMap<String, DeviceHttp.Status?>()

    // 卡片封面快照（T04046）：内存缓存上一张成功画面，离线后仍显示“最后见过的样子”；
    // 拉取间隔独立于状态轮询——快照是整张 JPEG，5 秒一张对相机端与观看端都不划算
    private val snapshots = HashMap<String, Bitmap>()
    private val lastSnapshotAt = HashMap<String, Long>()
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

    /**
     * 大屏事件侧栏（T04058）：仅 sw840dp 布局存在，取到 null 即窄/中屏。
     * 侧栏只展示时间线——远端片段回放能力未提供，行不接点击。
     */
    private var sideBar: View? = null
    private var sideEvents: LinearLayout? = null
    private var sideEmpty: TextView? = null

    /** 侧栏事件流的上次拉取时间：与封面快照共用 30 秒节流，远端查询不该跟着 5 秒轮询跑 */
    private var sideEventsAt = 0L

    /** 侧栏开合状态。折叠屏折叠/展开会 recreate，选择要跨重建保持，所以落 SharedPreferences */
    private var sideOpen = true

    /** 记录当前断点是否 ≥840dp：跨断点才 recreate 换布局，同断点内旋转不折腾播放流 */
    private var lastBreakWide = false

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
        // 空态盒与离线提示条只有窄屏布局存在；sw600dp 双栏仍是纯文字空态，取到 null 走原路径
        val emptyBox = findViewById<View>(R.id.device_list_empty_box)
        val offlineBar = findViewById<View>(R.id.device_list_offline_bar)
        this.emptyBox = emptyBox
        this.offlineBar = offlineBar

        findViewById<Button>(R.id.device_list_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.device_list_add).setOnClickListener { addDevice() }
        findViewById<Button>(R.id.device_empty_scan)?.setOnClickListener {
            startActivity(Intent(this, ScanPairActivity::class.java))
        }
        // 配对码路径 = 先局域网发现摄像头再输 6 位码（与“添加”对话框里的路径同源），
        // 空态直接暴露它，避免“发现不到时不知道还有别的路”
        findViewById<Button>(R.id.device_empty_code)?.setOnClickListener { discoverDevices() }
        findViewById<Button>(R.id.device_empty_manual)?.setOnClickListener { promptManual() }
        findViewById<Button>(R.id.device_list_rescan)?.setOnClickListener { rescan() }
        findViewById<Button>(R.id.device_list_diag)?.setOnClickListener {
            // 诊断面板在设置页内：跳过去比复制一套诊断 UI 更能保持“诊断只读”的一致口径
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 只有 sw600dp 布局提供右侧画面面板；窄屏下这几个 id 不存在，取到的是 null
        previewImage = findViewById(R.id.device_preview_image)
        previewStatus = findViewById(R.id.device_preview_status)
        previewTitle = findViewById(R.id.device_preview_title)
        findViewById<Button>(R.id.device_preview_full)?.setOnClickListener {
            val id = previewDeviceId ?: return@setOnClickListener
            registry.all().firstOrNull { it.id == id }?.let { openFullScreen(it) }
        }

        // 事件侧栏只有 sw840dp 布局存在；收起把手与展开按钮成对出现
        sideBar = findViewById(R.id.device_side_bar)
        sideEvents = findViewById(R.id.device_side_events)
        sideEmpty = findViewById(R.id.device_side_empty)
        findViewById<Button>(R.id.device_side_collapse)?.setOnClickListener { setSideOpen(false) }
        findViewById<Button>(R.id.device_side_expand)?.setOnClickListener { setSideOpen(true) }
        sideOpen = getPreferences(Context.MODE_PRIVATE).getBoolean(PREF_SIDE_OPEN, true)
        applySideVisibility()
        lastBreakWide = isWideBreakpoint()

        // 从重建（折叠切换）中恢复选中设备：画面流要重新握手，但设备选择不该丢
        savedInstanceState?.getString(KEY_PREVIEW_DEVICE)?.let { id ->
            registry.all().firstOrNull { it.id == id }?.let { showPreview(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        handler.post(pollRunnable)
        // 与全屏页同一策略：退后台停流，回前台自动重连，不在看不见的时候继续解码耗电
        if (previewDeviceId != null) preview?.start(QUALITY_AUTO)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // sw 最小宽度只在折叠/展开时变化，横竖屏不变；跨过 840dp 才 recreate 换布局，
        // 同断点内的任何尺寸变化都让画面流继续跑，这正是“不因折叠切换重建播放器”的落点
        if ((newConfig.smallestScreenWidthDp >= SIDE_BREAK_DP) != lastBreakWide) recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PREVIEW_DEVICE, previewDeviceId)
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
                // 在线才拉快照：离线设备连状态都没有，快照也一定拉不到，请求只会白等 3 秒超时
                if (status != null) refreshSnapshot(device, now)
                // 侧栏事件流跟随轮询但自带 30 秒节流：只刷当前选中设备
                if (device.id == previewDeviceId) refreshSideEvents(device, now)
                if (!stopped) runOnUiThread { render() }
            }
        }
    }

    /** 低频刷新卡片封面快照；缓存到内存里，离线后卡片仍显示最后见过的画面 */
    private fun refreshSnapshot(device: DeviceRegistry.Device, now: Long) {
        val last = synchronized(lastSnapshotAt) { lastSnapshotAt[device.id] ?: 0L }
        if (now - last < SNAPSHOT_INTERVAL_MS) return
        synchronized(lastSnapshotAt) { lastSnapshotAt[device.id] = now }
        val shot = runCatching {
            DeviceHttp.snapshot(device.host, device.port, device.token)?.let {
                decodeThumbnail(it.jpeg)
            }
        }.getOrNull() ?: return
        synchronized(snapshots) { snapshots[device.id] = shot }
    }

    /** 缩略图只有 56dp：按像素下采样解码，避免一张 1080p 快照占 8MB 内存 */
    private fun decodeThumbnail(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val ordered = registry.all()
            .map { it to stateOf(it, now) }
            // 状态优先级先于用户排序：PRD 要求“状态优先”，告警与离线设备必须浮到最上面
            .sortedWith(compareBy({ it.second.level.ordinal }, { it.first.order }))

        container.removeAllViews()
        // 空态分流（PRD 07 §4.2）：“从未添加”给三步说明与添加按钮；
        // “全部离线”给同一网络提示与重扫/诊断入口——两种空的原因不同，用户要做的动作也不同
        emptyText.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
        emptyBox?.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
        val allOffline = ordered.isNotEmpty() &&
            ordered.all { it.second.level == DeviceState.Level.OFFLINE }
        offlineBar?.visibility = if (allOffline) View.VISIBLE else View.GONE
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
        // 封面优先用最近快照；没有（从未拍到或拉取失败）才退回首字，这正是 PRD 的兜底顺序
        val cover = view.findViewById<TextView>(R.id.device_cover)
        val thumb = view.findViewById<ImageView>(R.id.device_thumb)
        val shot = synchronized(snapshots) { snapshots[device.id] }
        if (shot != null) {
            thumb.setImageBitmap(shot)
            thumb.visibility = View.VISIBLE
            cover.visibility = View.GONE
        } else {
            cover.text = device.name.take(1).ifEmpty { "?" }
        }
        view.findViewById<TextView>(R.id.device_name).text = device.name
        val room = device.room.ifEmpty { getString(R.string.device_room_unset) }
        view.findViewById<TextView>(R.id.device_meta).text = getString(
            R.string.device_card_meta,
            room,
            getString(stateLabel(state.level))
        )
        view.findViewById<TextView>(R.id.device_state).apply {
            text = getString(stateLabel(state.level))
            animateTextColor(this, stateColor(state.level))
        }
        view.findViewById<TextView>(R.id.device_detail).text = detailText(state, now)
        // 双栏里列表和右侧画面是同一件事的两半，选中卡片必须能一眼看出来
        // 用带圆角的选中底而不是 setBackgroundColor，否则卡片会被打回方角
        view.setBackgroundResource(
            if (device.id == previewDeviceId) R.drawable.qs_card_bg_selected
            else R.drawable.comp_shape_card
        )
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

                override fun onRetryWait(attempt: Int) {
                    if (gen != previewGeneration) return
                    runOnUiThread {
                        // 与全屏口径一致：状态之外再给次数，用户才知道还在推进而不是卡死
                        if (!stopped) previewStatus?.text = getString(R.string.live_retry_count, attempt)
                    }
                }
            }
        )
        previewDeviceId = device.id
        if (!stopped) preview?.start(QUALITY_AUTO)
        // 选中即强制拉一次事件流：30 秒节流只约束同一设备的持续刷新
        sideEventsAt = 0L
        refreshSideEvents(device, System.currentTimeMillis())
        // 宽屏右侧与全屏同等待遇（T04049）：连接建立前先垫一张最近快照，避免白屏等待；
        // 只拉这一次，重连期间的全屏兜底由 LiveViewActivity 自己的节流逻辑负责
        io.execute {
            val shot = runCatching { DeviceHttp.snapshot(device.host, device.port, device.token) }
                .getOrNull() ?: return@execute
            val bitmap = BitmapFactory.decodeByteArray(shot.jpeg, 0, shot.jpeg.size)
                ?: return@execute
            runOnUiThread {
                // 快照回来的同时可能已切到别的设备或直播帧已到达，代号比对防止旧图盖新画面
                if (gen == previewGeneration && !stopped && previewImage != null) {
                    previewImage?.setImageBitmap(bitmap)
                }
            }
        }
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
        // 侧栏跟着清空：留着已移除设备的事件会让用户以为还在看这台设备
        sideEvents?.removeAllViews()
        sideEmpty?.visibility = View.VISIBLE
        sideEmpty?.setText(R.string.device_side_empty)
    }

    /** 侧栏开合状态落盘：recreate（折叠切换）后用户的选择原样恢复 */
    private fun setSideOpen(open: Boolean) {
        sideOpen = open
        getPreferences(Context.MODE_PRIVATE).edit().putBoolean(PREF_SIDE_OPEN, open).apply()
        applySideVisibility()
        // 收起期间不拉事件（省流），展开时立即补一次，不等下一轮轮询
        val id = previewDeviceId
        if (open && id != null) {
            sideEventsAt = 0L
            registry.all().firstOrNull { it.id == id }?.let {
                refreshSideEvents(it, System.currentTimeMillis())
            }
        }
    }

    private fun applySideVisibility() {
        sideBar?.visibility = if (sideOpen) View.VISIBLE else View.GONE
        findViewById<View>(R.id.device_side_expand)?.visibility =
            if (sideOpen) View.GONE else View.VISIBLE
    }

    private fun isWideBreakpoint(): Boolean =
        resources.configuration.smallestScreenWidthDp >= SIDE_BREAK_DP

    /**
     * 拉取选中设备的远端事件流。节流与封面快照一致（30 秒）：
     * 事件列表是整页 JSON，跟着 5 秒轮询拉对相机端与观看端都不划算。
     */
    private fun refreshSideEvents(device: DeviceRegistry.Device, now: Long) {
        if (sideBar == null || !sideOpen) return
        if (now - sideEventsAt < SNAPSHOT_INTERVAL_MS) return
        sideEventsAt = now
        val host = device.host
        val port = device.port
        val token = device.token
        val id = device.id
        io.execute {
            val events = runCatching { DeviceHttp.events(host, port, token) }.getOrNull()
            runOnUiThread {
                // 拉取期间可能已切换设备：旧结果落进新设备侧栏会张冠李戴
                if (!stopped && previewDeviceId == id) renderSideEvents(events)
            }
        }
    }

    private fun renderSideEvents(events: List<DeviceHttp.RemoteEvent>?) {
        val list = sideEvents ?: return
        val empty = sideEmpty ?: return
        // 拉取失败不能显示“没有事件”：离线与无事件是两回事，混在一起会误导用户判断设备状态
        if (events == null) {
            empty.setText(R.string.device_side_unavailable)
            empty.visibility = View.VISIBLE
            list.removeAllViews()
            return
        }
        val now = System.currentTimeMillis()
        list.removeAllViews()
        events.forEach { event ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_remote_event, list, false)
            row.findViewById<TextView>(R.id.side_event_type).text =
                getString(EventType.fromRaw(event.type).labelRes)
            row.findViewById<TextView>(R.id.side_event_time).text = relativeTime(event.occurredAt, now)
            list.addView(row)
        }
        empty.setText(R.string.device_side_empty)
        empty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun detailText(state: DeviceState.View, now: Long): String {
        val parts = mutableListOf<String>()
        // 告警是时间线的第一句：用户扫一眼卡片要立刻知道“刚刚发生了什么”；
        // 时间由末尾的“最后事件”统一给出，这里不重复拼一遍相对时间
        if (state.level == DeviceState.Level.ALERT) {
            parts += getString(R.string.device_card_alert)
        } else if (state.level == DeviceState.Level.OFFLINE) {
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
                // 快照连同设备一起清掉：留着只会让已移除的设备在“最近删除”边缘占内存
                synchronized(snapshots) { snapshots.remove(device.id) }
                synchronized(lastSnapshotAt) { lastSnapshotAt.remove(device.id) }
                Toast.makeText(this, R.string.device_removed, Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
    }

    /**
     * 添加设备：三条路径（扫码 / 局域网发现 / 手动 IP）并列展示（PRD 07 §4.5），
     * 不再默认先跑 mDNS 窗口——发现不到的用户此前根本不知道还有别的路。
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
        val options = arrayOf(
            getString(R.string.scan_pair_title),
            getString(R.string.device_list_discover),
            getString(R.string.device_list_manual_title),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.device_list_add)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ScanPairActivity::class.java))
                    1 -> discoverDevices()
                    else -> promptManual()
                }
            }
            .show()
    }

    /** 全部离线时的“重新扫描”：不等下一轮 5 秒轮询，立即拉一次状态给用户即时反馈 */
    private fun rescan() {
        Toast.makeText(this, R.string.device_list_discovering, Toast.LENGTH_SHORT).show()
        poll()
    }

    private fun discoverDevices() {
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
            // mDNS 发现不到时允许手动填 IP：跨网段、模拟器 NAT、或路由器禁用多播时唯一可用路径
            promptManual()
            return
        }
        val labels = peers.map { "${it.name}\n${it.host}:${it.port}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.device_list_pick)
            .setItems(labels.toTypedArray()) { _, which -> promptCode(peers[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptManual() {
        val host = EditText(this).apply { hint = getString(R.string.device_list_manual_host_hint) }
        val port = EditText(this).apply {
            hint = getString(R.string.device_list_manual_port_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val code = EditText(this).apply {
            hint = getString(R.string.device_list_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(host)
            addView(port)
            addView(code)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.device_list_manual_title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.device_list_pair) { _, _ ->
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull()
                val c = code.text.toString().trim()
                if (h.isNotEmpty() && p != null && c.isNotEmpty()) {
                    // 手动地址通常是端口映射或跨网段，证书指纹无法从 mDNS 取得，按 HTTP 配对
                    pairWith(LanDiscovery.Peer("manual", h, h, p, ""), c, preferTls = false)
                }
            }
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

    private fun pairWith(peer: LanDiscovery.Peer, code: String, preferTls: Boolean = true) {
        io.execute {
            val info = DeviceHttp.info(peer.host, peer.port)
            // 先用 HTTP 的 /api/info 取得 HTTPS 端口与证书指纹（公开信息），
            // 注册信任锚后配对与控制全部走 HTTPS；旧版本设备没有 tls 字段则继续 HTTP。
            // 手动地址（端口映射/跨网段）无法保证 8443 可达，preferTls=false 时按用户给的端口走 HTTP。
            val securePort = if (preferTls && info != null && info.tlsPort > 0 && info.tlsFingerprint.isNotEmpty()) {
                TlsTrust.register(peer.host, info.tlsPort, info.tlsFingerprint)
                info.tlsPort
            } else {
                peer.port
            }
            val direct = DeviceHttp.pair(peer.host, securePort, pairing.publicKeyBase64(), code)
            // 普通配对失败时把同一输入当作一次性恢复码再试一次：用户不必理解两套码的差别，
            // 恢复码正确会清空旧配对并以 Owner 身份接管
            val result = direct ?: DeviceHttp.pair(
                peer.host, securePort, pairing.publicKeyBase64(), code, recoveryCode = code
            )
            val usedRecovery = direct == null && result != null
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
                        port = securePort,
                        tlsFingerprint = if (preferTls && securePort != peer.port) info?.tlsFingerprint.orEmpty() else "",
                        token = result.token,
                        order = 0,
                        lastSeenAt = System.currentTimeMillis(),
                        lastEventAt = info.lastEventAt,
                    )
                )
                if (added) {
                    Toast.makeText(
                        this,
                        if (usedRecovery) R.string.device_list_paired_recovery else R.string.device_list_paired,
                        Toast.LENGTH_SHORT
                    ).show()
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

    /**
     * 状态切换的 120ms 颜色过渡（PRD 4.6）：状态行随 5 秒轮询频繁重绑，
     * 直接跳色会让整排卡片“闪一下”；从当前色平滑过渡到目标色，同色时不启动动画。
     */
    private fun animateTextColor(view: TextView, toColor: Int) {
        val from = view.currentTextColor
        if (from == toColor) return
        ValueAnimator.ofArgb(from, toColor).apply {
            duration = 120
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
            start()
        }
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

        /** 卡片快照低频刷新：整张 JPEG 比状态 JSON 重得多，30 秒一张足够表达“最后见过什么” */
        const val SNAPSHOT_INTERVAL_MS = 30_000L

        /** 缩略图目标边长：56dp 的封面按 2x 屏幕密度预留即可 */
        const val THUMB_TARGET_PX = 128
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        const val LONG_OFFLINE_MS = DAY_MS

        /** 双栏预览默认走子码流：右侧面板小，原画只会让更多设备撞上并发达上限 */
        const val QUALITY_AUTO = "auto"

        // 服务端 MAX_VIEWERS 固定为 5，这里只用于把 429 翻译成人话
        const val MAX_VIEWERS = 5

        /** 大屏断点（PRD 4.7）：≥840dp 才挂事件侧栏 */
        const val SIDE_BREAK_DP = 840

        private const val PREF_SIDE_OPEN = "side_open"
        private const val KEY_PREVIEW_DEVICE = "preview_device"
    }
}
