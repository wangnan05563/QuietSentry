package com.quietsentry.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单条事件回放（PRD FR-11）：拖动、暂停、0.5×/1×/2×、逐帧、静音、保存快照。
 *
 * 为什么用 VideoView 而不是 ExoPlayer：项目当前只依赖 CameraX，为了一个本地 MP4 播放器
 * 引入整套 Media3 会增加包体与构建风险；VideoView 已能覆盖拖动/暂停/倍速（API 23+ 的 MediaPlayer.playbackParams）。
 * 逐帧用 seekTo 近似：MediaPlayer 只能 seek 到关键帧附近，无法保证帧精确，
 * 但用于“看清某一下动作”已够用，注释里也保留了这个限制，避免后续误以为做到了帧级精确。
 */
class EventPlayerActivity : AppCompatActivity() {

    private val store by lazy { EventStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var videoView: VideoView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: Button
    private lateinit var muteButton: Button
    private lateinit var titleText: TextView
    private lateinit var missingText: TextView
    private lateinit var waveform: WaveformView

    private var mediaPlayer: MediaPlayer? = null
    /** 响度历史条的数据源；随 MediaPlayer 会话走，换片（前置→主片）必须重挂 */
    private var visualizer: Visualizer? = null
    private var videoUri: Uri? = null
    private var prepared = false
    private var dragging = false
    private var muted = false

    /** 事件所在片段；前置画面播完要切回它 */
    private var mainUri: Uri? = null
    /** 当前是否在播“事件前画面”。前置片段的进度条对用户没有意义，播放期间隐藏 */
    private var inPreRoll = false
    private var playerTitle = ""

    private val progressTicker = object : Runnable {
        override fun run() {
            if (prepared && !dragging) {
                seekBar.progress = videoView.currentPosition
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_player)

        videoView = findViewById(R.id.video_view)
        seekBar = findViewById(R.id.seek_bar)
        playButton = findViewById(R.id.play_button)
        muteButton = findViewById(R.id.mute_button)
        titleText = findViewById(R.id.player_title)
        missingText = findViewById(R.id.missing_text)
        waveform = findViewById(R.id.waveform)

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val event = if (eventId > 0) store.get(eventId) else null
        if (event == null) {
            Toast.makeText(this, R.string.timeline_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        playerTitle = getString(event.type.labelRes) + " · " + timeFormat.format(Date(event.occurredAt))
        titleText.text = playerTitle

        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }

        val uri = resolveUri(event)
        if (uri == null) {
            showMissing()
            return
        }
        setupPlayer(event, uri)
    }

    /**
     * 前置画面（PRD FR-07：事件从事件前 5 秒开始）：
     * 事件不在片头 5 秒内时，所在片段自身就覆盖了前置时长；
     * 否则先播上一片尾部不足的秒数，播完无缝切回事件片段。
     * 两种情况都不从分片开头播起，用户不必先看十几秒无关画面。
     */
    private fun setupPlayer(event: Event, uri: Uri) {
        mainUri = uri
        bindControls()
        val prev = resolvePrevUri(event)
        val shortOfPreRoll = event.clipOffsetMs < PRE_ROLL_MS
        if (shortOfPreRoll && prev != null) {
            inPreRoll = true
            loadVideo(prev, tailMs = PRE_ROLL_MS - event.clipOffsetMs, autoPlay = true)
        } else {
            if (shortOfPreRoll) {
                // 本场第一个分片就命中事件时没有上一片可借，只能如实告知前置画面不足
                titleText.text = getString(R.string.player_pre_roll_short, playerTitle)
            }
            // 事件前不足则从分片开头播；否则回退到事件前 5 秒，避免让用户先看无关画面
            loadVideo(
                uri,
                startMs = (event.clipOffsetMs - PRE_ROLL_MS).coerceAtLeast(0L),
                autoPlay = false
            )
        }
    }

    private fun resolvePrevUri(event: Event): Uri? {
        event.prevClipUri?.let { return Uri.parse(it) }
        return event.prevClip?.takeIf { it.isFile && it.length() > 0L }?.let { Uri.fromFile(it) }
    }

    /**
     * 优先用导出后回写的相册定位；相册定位缺失时才退回私有原件。
     * 两者都没有说明文件已被配额清理或分片未收敛，界面必须明确告诉用户，而不是留下一个永远转圈的黑屏。
     */
    private fun resolveUri(event: Event): Uri? {
        event.clipUri?.let { return Uri.parse(it) }
        return event.clip?.takeIf { it.isFile && it.length() > 0L }?.let { Uri.fromFile(it) }
    }

    private fun showMissing() {
        missingText.visibility = View.VISIBLE
        videoView.visibility = View.GONE
        // 没有可播的文件就没有音频会话，波形留着会一直画无意义的空底柱
        waveform.visibility = View.GONE
        seekBar.isEnabled = false
        playButton.isEnabled = false
        muteButton.isEnabled = false
        listOf(R.id.speed_half, R.id.speed_normal, R.id.speed_double, R.id.frame_prev,
            R.id.frame_next, R.id.snapshot_button).forEach {
            findViewById<Button>(it).isEnabled = false
        }
    }

    /**
     * 每次换片（前置→主片）都要重新 prepare，但控件监听只需绑一次，否则重复设置会叠加。
     */
    private fun loadVideo(uri: Uri, startMs: Long = 0L, tailMs: Long = 0L, autoPlay: Boolean) {
        videoUri = uri
        prepared = false
        // 前置片段的进度条没有用户意义，播放期间隐藏；主片就绪后恢复
        seekBar.visibility = if (tailMs > 0L) View.INVISIBLE else View.VISIBLE
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            prepared = true
            seekBar.max = mp.duration
            val target = when {
                // 只取上一片尾部：前置覆盖分片边界时正是尾部这段画面
                tailMs > 0L -> (mp.duration - tailMs).coerceAtLeast(0L).toInt()
                // 事件不在片头时，本片自身也要从“事件前 5 秒”起播，而不是从分片开头
                else -> startMs.coerceIn(0L, mp.duration.toLong()).toInt()
            }
            if (target > 0) {
                videoView.seekTo(target)
                seekBar.progress = target
            }
            mp.setOnCompletionListener { onSegmentFinished() }
            if (autoPlay) {
                mp.start()
                playButton.setText(R.string.player_pause)
            } else {
                playButton.setText(R.string.player_play)
            }
            mainHandler.post(progressTicker)
            attachVisualizer(mp)
        }
        videoView.setOnErrorListener { _, _, _ ->
            // 相册条目可能被用户在系统相册里删掉，解码失败就按“文件不可用”处理
            showMissing()
            true
        }
    }

    /**
     * 响度历史条数据源（T04059）：Visualizer 只读回放的音频输出采样，不录任何内容，
     * 但系统仍要求持有 RECORD_AUDIO 权限才允许实例化——未授权时整条隐藏而不是留空视图。
     * 采集尺寸取最小、采样率取最大：波形只需要 0..1 的粗略响度，尺寸大纯属浪费算力。
     * 部分 ROM 的 Visualizer 对回放输出恒为全零（只支持全局混音采样），此时波形
     * 退化为恒定的最低柱，如实表现为“无声”，不做额外探测兜底以免过度设计。
     */
    private fun attachVisualizer(mp: MediaPlayer) {
        releaseVisualizer()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            waveform.visibility = View.GONE
            return
        }
        runCatching {
            val viz = Visualizer(mp.audioSessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[0]
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer, bytes: ByteArray, samplingRate: Int
                    ) {
                        // 历史条每柱要的是“这一瞬有多响”，取峰值比均值更贴合人耳对突发声的感知
                        var peak = 0
                        for (b in bytes) {
                            val magnitude = b.toInt() and 0xFF
                            if (magnitude > peak) peak = magnitude
                        }
                        waveform.push(peak / 255f)
                    }

                    override fun onFftDataCapture(
                        v: Visualizer, bytes: ByteArray, samplingRate: Int
                    ) = Unit
                },
                Visualizer.getMaxCaptureRate(), true, false
            )
            viz.enabled = true
            visualizer = viz
        }.onFailure {
            // 个别 ROM 拒绝挂音频会话（session 未激活等）：波形隐藏即可，不能影响播放本身
            waveform.visibility = View.GONE
        }
    }

    private fun releaseVisualizer() {
        visualizer?.let { runCatching { it.enabled = false; it.release() } }
        visualizer = null
    }

    private fun onSegmentFinished() {
        val main = mainUri
        // 前置画面播完直接切回事件片段并续播，避免用户以为播放结束
        if (inPreRoll && main != null) {
            inPreRoll = false
            loadVideo(main, tailMs = 0L, autoPlay = true)
            return
        }
        playButton.setText(R.string.player_play)
    }

    private fun bindControls() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) videoView.seekTo(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                videoView.seekTo(bar.progress)
            }
        })

        playButton.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                playButton.setText(R.string.player_play)
            } else {
                mp.start()
                playButton.setText(R.string.player_pause)
            }
        }

        findViewById<Button>(R.id.speed_half).setOnClickListener { applySpeed(0.5f) }
        findViewById<Button>(R.id.speed_normal).setOnClickListener { applySpeed(1f) }
        findViewById<Button>(R.id.speed_double).setOnClickListener { applySpeed(2f) }

        findViewById<Button>(R.id.frame_prev).setOnClickListener { stepFrame(-FRAME_MS) }
        findViewById<Button>(R.id.frame_next).setOnClickListener { stepFrame(FRAME_MS) }

        muteButton.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            muted = !muted
            mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
            muteButton.setText(
                if (muted) R.string.player_unmute else R.string.player_mute
            )
        }

        findViewById<Button>(R.id.snapshot_button).setOnClickListener { saveSnapshot() }
    }

    private fun applySpeed(value: Float) {
        val mp = mediaPlayer ?: return
        val wasPlaying = mp.isPlaying
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(value) }
            .onFailure {
                Toast.makeText(this, R.string.player_missing, Toast.LENGTH_SHORT).show()
                return
            }
        // 部分 ROM 在设置 playbackParams 时会自动开播，这里强制回到用户原来的播放状态
        if (!wasPlaying) mp.pause()
    }

    private fun stepFrame(deltaMs: Long) {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            playButton.setText(R.string.player_play)
        }
        val duration = videoView.duration
        val target = (videoView.currentPosition + deltaMs).coerceIn(0L, duration.toLong())
        videoView.seekTo(target.toInt())
        seekBar.progress = target.toInt()
    }

    private fun saveSnapshot() {
        val uri = videoUri ?: return
        val retriever = MediaMetadataRetriever()
        val frame = runCatching {
            retriever.setDataSource(this, uri)
            retriever.getFrameAtTime(
                videoView.currentPosition * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        }.getOrNull()
        runCatching { retriever.release() }

        if (frame == null) {
            Toast.makeText(this, R.string.player_snapshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = runCatching { writeSnapshot(frame) }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) R.string.player_snapshot_saved else R.string.player_snapshot_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun writeSnapshot(bitmap: Bitmap): Boolean {
        val name = "quiet_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/QuietSentry"
                )
            }
            val uri = contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { output ->
                return bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            return false
        }

        // API 28 只能写公共目录，写完再让媒体库扫描，否则相册里看不到
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "QuietSentry"
        ).apply { mkdirs() }
        val target = File(dir, name)
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)
        return true
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTicker)
        releaseVisualizer()
        runCatching { videoView.stopPlayback() }
        mediaPlayer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"

        /** 30fps 下的一帧时长；seekTo 只能落到附近关键帧，这里把它当作“逐帧”的实际精度上限。 */
        private const val FRAME_MS = 33L

        /** 事件前覆盖时长（PRD FR-07），与 EventStore 的清理口径保持一致 */
        private const val PRE_ROLL_MS = 5_000L
    }
}
