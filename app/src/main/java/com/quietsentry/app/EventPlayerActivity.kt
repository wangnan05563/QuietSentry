package com.quietsentry.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaScannerConnection
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

    private var mediaPlayer: MediaPlayer? = null
    private var videoUri: Uri? = null
    private var prepared = false
    private var dragging = false
    private var muted = false

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

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val event = if (eventId > 0) store.get(eventId) else null
        if (event == null) {
            Toast.makeText(this, R.string.timeline_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        titleText.text = getString(
            if (event.type == EventType.MOTION) R.string.timeline_type_motion
            else R.string.timeline_type_sound
        ) + " · " + timeFormat.format(Date(event.occurredAt))

        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }

        val uri = resolveUri(event)
        if (uri == null) {
            showMissing()
            return
        }
        videoUri = uri
        setupPlayer(uri)
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
        seekBar.isEnabled = false
        playButton.isEnabled = false
        muteButton.isEnabled = false
        listOf(R.id.speed_half, R.id.speed_normal, R.id.speed_double, R.id.frame_prev,
            R.id.frame_next, R.id.snapshot_button).forEach {
            findViewById<Button>(it).isEnabled = false
        }
    }

    private fun setupPlayer(uri: Uri) {
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            prepared = true
            seekBar.max = mp.duration
            mp.setOnCompletionListener {
                playButton.setText(R.string.player_play)
            }
            mainHandler.post(progressTicker)
        }
        videoView.setOnErrorListener { _, _, _ ->
            // 相册条目可能被用户在系统相册里删掉，解码失败就按“文件不可用”处理
            showMissing()
            true
        }

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
        runCatching { videoView.stopPlayback() }
        mediaPlayer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"

        /** 30fps 下的一帧时长；seekTo 只能落到附近关键帧，这里把它当作“逐帧”的实际精度上限。 */
        private const val FRAME_MS = 33L
    }
}
