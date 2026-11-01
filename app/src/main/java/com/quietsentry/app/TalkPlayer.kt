package com.quietsentry.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * 对讲下行播放（PRD FR-16）：观看端把 PCM 分块 POST 上来，这里按顺序写进 AudioTrack。
 *
 * 为什么用 16kHz/16bit 单声道裸流而不是 AAC：
 * 不需要编解码器、不需要容器，每一块都能独立播放；对讲只要“听得清”，
 * 用编码换来的那点带宽在局域网里没有意义，却会多出一整条无法在此环境验证的编解码链路。
 *
 * 为什么按“空闲超时”收尾：HTTP 是无状态的，服务端无法从连接本身知道对方已经松手，
 * 只能根据“多久没有新数据”判定结束。
 */
object TalkPlayer {

    const val SAMPLE_RATE = 16_000
    /** 16 位单声道：每毫秒 32 字节 */
    private const val BYTES_PER_MS = 32
    /** 1.5 秒没有新数据就判定“松手了”，避免残留缓冲把上一句话拖进下一句 */
    private const val IDLE_STOP_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())
    private val stopWhenIdle = Runnable { release() }

    private var track: AudioTrack? = null

    /** 服务线程写入、HTTP 线程读取，用 @Volatile 保证“是否在对讲”不会读到过期值 */
    @Volatile
    private var playing = false

    @Synchronized
    fun isPlaying(): Boolean = playing

    @Synchronized
    fun feed(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return false
        val player = track ?: create() ?: return false
        val written = runCatching {
            player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        }.getOrDefault(-1)
        if (written <= 0) {
            release()
            return false
        }
        playing = true
        // 每收到一块就重置空闲计时：只要对方一直按住说话，播放器就一直开着
        handler.removeCallbacks(stopWhenIdle)
        handler.postDelayed(stopWhenIdle, IDLE_STOP_MS)
        return true
    }

    @Synchronized
    fun release() {
        handler.removeCallbacks(stopWhenIdle)
        playing = false
        val player = track ?: return
        track = null
        runCatching {
            player.stop()
            player.release()
        }
    }

    private fun create(): AudioTrack? = runCatching {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 最小缓冲容易被"写一块、播一块"的节奏打断出杂音，给两倍余量留出抖动空间
        val bufferSize = maxOf(minBuffer, BYTES_PER_MS * 500)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 用 MUSIC 而不是 SYSTEM：警笛误触时用户能立刻从音量键关小
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize * 2)
            .build()
            .also { it.play() }
    }.getOrNull()

    /** 供状态接口判断对讲是否正在进行；界面只需要知道“有没有人说话”，不需要播放进度 */
    fun isActive(): Boolean = playing
}
