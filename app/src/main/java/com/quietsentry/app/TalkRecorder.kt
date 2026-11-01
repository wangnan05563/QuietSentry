package com.quietsentry.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/**
 * 对讲上行采集（PRD FR-16）：按住说话时把 PCM 分块交给上传回调，松开即停。
 *
 * 为什么固定 16kHz / 16bit / 单声道：与相机端 TalkPlayer 的播放格式必须逐字节一致，
 * 否则对方听到的是加速、变调或噪声。语音带宽本来就不需要 44.1kHz。
 *
 * 每块 0.5 秒：太短会让 HTTP 请求数暴涨，太长会把延迟推到 1 秒以上。
 */
class TalkRecorder(
    private val upload: (ByteArray) -> Boolean,
    /** failed=true 表示不是用户松手，而是采集或上传出错，界面必须说出来 */
    private val onStopped: (Boolean) -> Unit = {},
) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    /** 返回 false 表示权限缺失或麦克风被占用，调用方需要给出具体原因 */
    fun start(context: Context): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val recorder = create() ?: return false
        record = recorder
        running = true
        thread = Thread {
            val buffer = ByteArray(CHUNK_BYTES)
            var failed = false
            runCatching {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    // 用户松手时 running 已为 false，read 被打断属于正常收尾
                    if (read <= 0) {
                        failed = running
                        break
                    }
                    // 上传失败就主动停：继续采集只会让延迟越滚越大，用户还以为自己在说话
                    if (!upload(buffer.copyOf(read))) {
                        failed = true
                        break
                    }
                }
            }
            stopInternal(failed)
        }.also { it.start() }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        // AudioRecord.stop() 会让阻塞中的 read() 立即返回，否则松手后还会多录半秒才发出去
        runCatching { record?.stop() }
    }

    private fun stopInternal(failed: Boolean) {
        running = false
        val recorder = record
        record = null
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        thread = null
        onStopped(failed)
    }

    private fun create(): AudioRecord? = runCatching {
        val minBuffer = AudioRecord.getMinBufferSize(
            TalkPlayer.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TalkPlayer.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES))
            .build()
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }.getOrNull()

    private companion object {
        /** 0.5 秒 × 16kHz × 2 字节（单声道 16 位） */
        const val CHUNK_BYTES = 16_000
    }
}
