package com.quietsentry.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 端侧声音检测（PRD FR-07）：只看环境音量是否持续超过阈值，不做识别、不留原始音频。
 *
 * 为什么用 VOICE_RECOGNITION 音源：它关闭了系统 AGC 与降噪，阈值才稳定；
 * 同时它是可共享的输入源，能与 CameraX 的录音共存（MIC 源容易与录制互斥导致一路静音）。
 */
class SoundDetector(
    sensitivity: Int,
    private val onSound: (confidence: Int) -> Unit,
) {

    // 灵敏度 1（迟钝，-35dBFS）到 5（灵敏，-51dBFS），越灵敏阈值越低
    private val thresholdDb = -35.0 - (sensitivity.coerceIn(1, 5) - 1) * 4.0

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var record: AudioRecord? = null
    private var lastEventAtMs = 0L

    fun start(context: Context) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        running = true
        thread = Thread({ loop() }, "sound-detector").apply { start() }
    }

    fun stop() {
        running = false
        // 先 stop 打断阻塞中的 read；release 交给采集线程收尾，避免在主线程上等 IO
        runCatching { record?.stop() }
        thread?.join(300)
        thread = null
    }

    @SuppressLint("MissingPermission") // start() 已校验 RECORD_AUDIO
    private fun loop() {
        var recorder: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                // 两倍最小缓冲：单块读取不会因为调度抖动而丢样本
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return
            record = recorder
            recorder.startRecording()

            val chunk = ShortArray(CHUNK_SAMPLES)
            var hotMs = 0L
            while (running) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                var sum = 0.0
                for (i in 0 until read) {
                    val v = chunk[i].toDouble()
                    sum += v * v
                }
                val rms = sqrt(sum / read)
                // 转换为 dBFS：安静环境约 -70，正常说话约 -40 ~ -30
                val db = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else SILENCE_DB

                if (db >= thresholdDb) hotMs += CHUNK_MS else hotMs = 0
                if (hotMs >= SUSTAIN_MS) {
                    hotMs = 0
                    val now = System.currentTimeMillis()
                    // 同一事件源 60 秒内合并为一条（PRD FR-07）
                    if (now - lastEventAtMs >= MERGE_WINDOW_MS) {
                        lastEventAtMs = now
                        val confidence = ((db - thresholdDb) / 12.0 * 100).toInt().coerceIn(1, 100)
                        onSound(confidence)
                    }
                }
            }
        } catch (e: Exception) {
            // 麦克风被占用或权限被回收：静默退出，视频录制不受影响
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            record = null
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 10 // 100ms
        private const val CHUNK_MS = 100L

        /** 持续超过阈值 1.2 秒才算声音事件：敲门、短暂碰撞不应触发 */
        private const val SUSTAIN_MS = 1_200L
        private const val MERGE_WINDOW_MS = 60_000L
        private const val SILENCE_DB = -100.0
    }
}
