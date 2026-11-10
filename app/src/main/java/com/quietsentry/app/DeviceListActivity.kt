package com.quietsentry.app

import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    /**
     * 多设备 2×2 网格（T04759 / UX-01）：仅 sw840dp 布局存在，取到 null 即窄/中屏。
     * 网格模式下对前 4 台在线设备各起一路流，聚焦时停网格流切换到单画面，返回时重启。
     */
    private var gridContainer: LinearLayout? = null
    private var gridCells: GridLayout? = null
    private var gridEmpty: TextView? = null
    private val gridStreams = HashMap<String, LiveStreamer>()
    private var gridGeneration = 0

    /** 网格是否处于展示状态（用于聚焦/返回切换时判断该停网格流还是单画面流） */
    private var gridMode = false

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
        bindPullToRefresh()
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

        // 多设备网格只有 sw840dp 布局存在；窄/中屏无这些 id，取到 null 走原单画面路径
        gridContainer = findViewById(R.id.device_grid)
        gridCells = findViewById(R.id.device_grid_cells)
        gridEmpty = findViewById(R.id.device_grid_empty)
        // 网格里点某格聚焦：复用单画面面板 showPreview，返回按钮切回网格
        findViewById<Button>(R.id.device_grid_back)?.setOnClickListener { exitGridFocus() }
        // sw840dp 才有网格控件：有即默认进入网格模式；窄/中屏无网格走原单画面
        gridMode = gridContainer != null

        // 从重建（折叠切换）中恢复选中设备：画面流要重新握手，但设备选择不该丢。
        // 重建说明此前处于聚焦态（网格模式不会把单台设备写进 savedInstanceState），
        // 因此恢复时退出网格、回到聚焦单画面，并亮出“返回网格”按钮
        savedInstanceState?.getString(KEY_PREVIEW_DEVICE)?.let { id ->
            registry.all().firstOrNull { it.id == id }?.let {
                gridMode = false
                findViewById<Button>(R.id.device_grid_back)?.visibility = View.VISIBLE
                findViewById<Button>(R.id.device_preview_full)?.visibility = View.GONE
                showPreview(it)
            }
        }
    }

    /**
     * T04742 / docs/07 §4.3：顶部下拉触发一次立即轮询，不必等 5 秒周期。
     * 不引入 androidx.swiperefreshlayout（仓库无该依赖且离线环境不便下载），
     * 直接在三档布局共用的 ScrollView 上判「已到顶 + 向下拖动超过阈值」。
     * 返回 false 不消费事件，ScrollView 自身的滚动行为完全保留。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindPullToRefresh() {
        val scroll = findViewById<ScrollView>(R.id.device_list_scroll) ?: return
        var startY = 0f
        var armed = false
        var triggered = false
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    armed = scroll.scrollY == 0
                    triggered = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (armed && !triggered && scroll.scrollY == 0 &&
                        event.y - startY > PULL_THRESHOLD_PX
                    ) {
                        triggered = true
                        refreshNow()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    armed = false
                    triggered = false
                }
            }
            false
        }
    }

    /** 立即轮询一次，并给一段可见反馈：轮询异步执行，反馈文案到点自动还原 */
    private fun refreshNow() {
        val hint = hintText ?: return
        hint.setText(R.string.device_list_refreshing)
        poll()
        handler.postDelayed({
            if (!stopped) hint.setText(R.string.device_list_hint)
        }, REFRESH_FEEDBACK_MS)
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        handler.post(pollRunnable)
        // 与全屏页同一策略：退后台停流，回前台自动重连，不在看不见的时候继续解码耗电
        if (previewDeviceId != null) preview?.start(QUALITY_AUTO)
        // 网格模式回前台重启网格流（onPause 已停）
        if (gridMode) gridStreams.values.forEach { it.start(QUALITY_AUTO) }
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
        gridStreams.values.forEach { it.stop() }
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        discovery?.stop()
        discovery = null
        preview?.release()
        preview = null
        gridStreams.values.forEach { it.release() }
        gridStreams.clear()
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
            // 状态优先级先于用户排序：PRD FR-03 要求告警设备浮到最上面。
            // T04741：告警是叠加标识（View.alerting），离线/低电设备刚发生过事件也要顶到最前，
            // 不能再只按 level.ordinal 排——那会让「离线但刚刚告警」沉到列表底部。
            .sortedWith(
                compareBy(
                    { if (it.second.alerting) 0 else 1 },
                    { it.second.level.ordinal },
                    { it.first.order }
                )
            )

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
        // 网格随列表状态一起刷新：在线设备不足 2 台时自动退回单画面
        renderGrid(ordered)
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
        val label = getString(stateLabel(state.level))
        // T04741：告警标识叠加在运行状态之上（PRD §7）。在线设备 level 已是 ALERT，
        // 无需重复；离线/低电/过载设备则在房间·状态后再补一段「告警中」角标文案。
        view.findViewById<TextView>(R.id.device_meta).text =
            if (state.alerting && state.level != DeviceState.Level.ALERT) {
                getString(
                    R.string.device_card_meta_alert,
                    room,
                    label,
                    getString(R.string.state_alert)
                )
            } else {
                getString(R.string.device_card_meta, room, label)
            }
        val stateLabelRes = stateLabel(state.level)
        view.findViewById<TextView>(R.id.device_state).apply {
            text = getString(stateLabelRes)
            animateTextColor(this, stateColor(state.level))
        }
        // §3.4 状态色点：复用 DeviceState 状态色，contentDescription 描述状态语义（不单靠颜色）
        view.findViewById<View>(R.id.device_state_dot).apply {
            contentDescription = getString(stateLabelRes)
            background?.setTint(stateColor(state.level))
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

    // ---------- 多设备 2×2 网格（T04759 / UX-01） ----------

    /**
     * 渲染网格：取前 4 台在线设备各填一格。网格模式下单画面面板隐藏。
     * 在线设备 <2 时退回单画面（网格没有“同屏多台”的价值）。
     */
    private fun renderGrid(ordered: List<Pair<DeviceRegistry.Device, DeviceState.View>>) {
        val cells = gridCells ?: return
        val container = gridContainer ?: return
        // 网格只在 ≥840dp 且非聚焦态时展示；聚焦到单台设备时由 showPreview 切走
        val online = ordered.filter { it.second.level != DeviceState.Level.OFFLINE }
        val showGrid = gridMode && online.size >= 2
        container.visibility = if (showGrid) View.VISIBLE else View.GONE
        if (!showGrid) {
            // 在线设备不足 2 台或聚焦态：停掉所有网格流，避免并发/内存泄漏
            stopGridStreams()
            cells.removeAllViews()
            return
        }

        cells.removeAllViews()
        val targets = online.take(GRID_MAX_CELLS)
        // 停掉不在目标里的流（设备离线/超出 4 格/被移除），避免并发泄漏
        val targetIds = targets.map { it.first.id }.toSet()
        val stale = gridStreams.keys.filter { it !in targetIds }.toList()
        stale.forEach { id ->
            gridStreams.remove(id)?.release()
        }
        targets.forEach { (device, state) ->
            cells.addView(gridCell(device, state))
            ensureGridStream(device)
        }
    }

    /** 单格：画面 + 底部名称/状态色点，点击聚焦到单画面 */
    private fun gridCell(device: DeviceRegistry.Device, state: DeviceState.View): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_grid_cell, gridCells, false)
        // 2×2 均分：GridLayout 子项若用 match_parent 会每个都占满整个容器，
        // 这里改用 weight 让 4 格按行列均分（columnCount=2，自动换行成 2 行）
        val lp = GridLayout.LayoutParams()
        lp.width = 0
        lp.height = 0
        lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        val margin = resources.getDimensionPixelSize(R.dimen.qs_space_4)
        lp.setMargins(margin, margin, margin, margin)
        view.layoutParams = lp
        // 用 tag 记住设备 id：帧/状态回调按 id 定位对应格，避免张冠李戴
        view.tag = device.id
        view.findViewById<TextView>(R.id.grid_cell_name).text = device.name
        val dot = view.findViewById<View>(R.id.grid_cell_dot)
        dot.contentDescription = getString(stateLabel(state.level))
        dot.background?.setTint(stateColor(state.level))
        view.findViewById<TextView>(R.id.grid_cell_status).apply {
            // T04741：告警角标用叠加标识判断，离线但近期有事件的格子也要能标出来
            if (state.alerting) {
                visibility = View.VISIBLE
                text = getString(R.string.state_alert)
            } else {
                visibility = View.GONE
            }
        }
        view.setOnClickListener { focusGridCell(device) }
        return view
    }

    /** 为一台设备确保存在一路网格流；已有则跳过，避免重复 start */
    private fun ensureGridStream(device: DeviceRegistry.Device) {
        if (gridStreams.containsKey(device.id)) return
        val gen = gridGeneration
        val stream = LiveStreamer(
            device.host, device.port, device.token,
            object : LiveStreamer.Listener {
                override fun onState(state: LiveStreamer.State) {
                    if (gen != gridGeneration) return
                    runOnUiThread { if (!stopped && gridMode) renderGridCellState(device.id, state) }
                }

                override fun onFrame(frame: Bitmap) {
                    if (gen != gridGeneration) return
                    runOnUiThread { if (!stopped && gridMode) setGridCellFrame(device.id, frame) }
                }
            }
        )
        gridStreams[device.id] = stream
        if (!stopped) stream.start(QUALITY_AUTO)
    }

    /** 按设备 id 找到网格中对应格的 ImageView 并设帧 */
    private fun setGridCellFrame(deviceId: String, frame: Bitmap) {
        val cells = gridCells ?: return
        val cell = cells.findViewWithTag<View>(deviceId) ?: return
        cell.findViewById<ImageView>(R.id.grid_cell_image).setImageBitmap(frame)
    }

    /** 按设备 id 更新格内状态文案（连接中/重试/离线等） */
    private fun renderGridCellState(deviceId: String, state: LiveStreamer.State) {
        val cells = gridCells ?: return
        val cell = cells.findViewWithTag<View>(deviceId) ?: return
        val status = cell.findViewById<TextView>(R.id.grid_cell_status)
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

    /** 聚焦到单台设备：停掉网格所有流，复用单画面面板 showPreview */
    private fun focusGridCell(device: DeviceRegistry.Device) {
        stopGridStreams()
        gridMode = false
        gridContainer?.visibility = View.GONE
        // 显示“返回网格”按钮，隐藏“全屏”按钮（聚焦态下返回网格比全屏更符合心智）
        findViewById<Button>(R.id.device_grid_back)?.visibility = View.VISIBLE
        findViewById<Button>(R.id.device_preview_full)?.visibility = View.GONE
        showPreview(device)
    }

    /** 从聚焦态返回网格：停单画面流，重启网格 */
    private fun exitGridFocus() {
        previewGeneration++
        preview?.release()
        preview = null
        previewDeviceId = null
        previewImage?.setImageDrawable(null)
        findViewById<Button>(R.id.device_grid_back)?.visibility = View.GONE
        findViewById<Button>(R.id.device_preview_full)?.visibility = View.VISIBLE
        gridMode = true
        startGrid()
    }

    /** 进入网格模式并拉流 */
    private fun startGrid() {
        gridMode = true
        gridGeneration++
        render()
    }

    /** 停掉所有网格流并清空引用 */
    private fun stopGridStreams() {
        gridGeneration++
        gridStreams.values.forEach { it.release() }
        gridStreams.clear()
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
        if (now - sideEventsAt < SIDE_EVENTS_INTERVAL_MS) return
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
        // 告警是时间线的第一句：用户扫一眼卡片要立刻知道“刚刚发生了什么”。
        // T04741：用叠加标识而非 level，这样「离线 + 刚告警」也不会把告警句吞掉。
        if (state.alerting) {
            parts += getString(R.string.device_card_alert)
        }
        // 运行状态细节与告警标识并列而非互斥（PRD §7）：离线时长、电量、心跳异常
        // 在任何情况下都要照常给出，不能因为「刚有告警」就丢掉设备不可达这个事实。
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

        /** 卡片快照低频刷新：整张 JPEG 比状态 JSON 重得多，缓存 60 秒（docs/07 §7 NFR-01） */
        const val SNAPSHOT_INTERVAL_MS = 60_000L

        /** 侧栏事件流维持 30 秒节流，不跟着快照缓存一起放宽 */
        const val SIDE_EVENTS_INTERVAL_MS = 30_000L

        /** 缩略图目标边长 320px（docs/07 §7）；按 2 倍密度下采样 */
        const val THUMB_TARGET_PX = 320

        /** T04742：到顶后下拉超过 120px 触发一次立即轮询 */
        const val PULL_THRESHOLD_PX = 120f
        const val REFRESH_FEEDBACK_MS = 800L
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

        /** 2×2 网格最多 4 格；服务端 MAX_VIEWERS=5，4 路并发不撞上限（T04759/UX-01） */
        const val GRID_MAX_CELLS = 4

        private const val PREF_SIDE_OPEN = "side_open"
        private const val KEY_PREVIEW_DEVICE = "preview_device"
    }
}
