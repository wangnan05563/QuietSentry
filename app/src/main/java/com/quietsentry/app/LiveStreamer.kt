package com.quietsentry.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.Executors

/**
 * 实时画面拉流循环（PRD FR-08）。
 *
 * 从 LiveViewActivity 抽出来是为了让宽屏双栏（PRD §12.3：左侧设备列表、右侧实时画面）
 * 与全屏观看共用同一套重连与失败分类逻辑——两份拷贝必然会在“401 要不要停下来”这类
 * 边界判断上分叉，用户看到的就是两个页面行为不一致。
 *
 * 这里只做网络与解码，不碰任何 View：回调线程是后台线程，切换 UI 由调用方负责，
 * 这样同一个类可以直接服务 Activity、双栏面板，将来接入 Fragment 也不用改。
 */
class LiveStreamer(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val listener: Listener,
) {

    enum class State { CONNECTING, CONNECTED, RETRY, OFFLINE, UNAUTHORIZED, BUSY }

    interface Listener {
        fun onState(state: State)

        fun onFrame(frame: Bitmap)

        /**
         * 每次进入重连等待时回调累计尝试次数（T04050）。
         * 默认空实现：不关心次数的调用方（未来接入的面板）不必被迫实现。
         */
        fun onRetryWait(attempt: Int) {}
    }

    private val io = Executors.newSingleThreadExecutor()

    /** 每次启动/停止都递增：旧循环即使卡在阻塞读里，醒来也会发现代号已过期而退出 */
    @Volatile
    private var generation = 0

    @Volatile
    private var running = false
    private var client: MjpegClient? = null

    @Volatile
    private var latest: Bitmap? = null

    /** 连续失败计数：连上一次就归零，退避只针对“一直连不上”的场景累加 */
    private var retryAttempt = 0

    /** 双栏右侧的截图按钮不需要等下一帧，直接取最近解码成功的一帧 */
    fun currentFrame(): Bitmap? = latest

    fun start(quality: String) {
        generation++
        val gen = generation
        running = true
        listener.onState(State.CONNECTING)
        io.execute { loop(quality, gen) }
    }

    fun stop() {
        running = false
        generation++
        client?.close()
        client = null
    }

    fun release() {
        stop()
        io.shutdownNow()
    }

    private fun loop(quality: String, gen: Int) {
        while (running && gen == generation) {
            val stream = MjpegClient(host, port, token, quality)
            client = stream
            if (!stream.open()) {
                val failure = stream.failure
                stream.close()
                client = null
                if (!alive(gen)) return
                val state = stateOf(failure)
                listener.onState(state)
                // 令牌撤销后重连只会一直拿到 401，停下来让用户重新配对
                if (state == State.UNAUTHORIZED) {
                    running = false
                    return
                }
                retryAttempt++
                listener.onRetryWait(retryAttempt)
                if (!sleepRetry(gen, backoffDelay(retryAttempt))) return
                continue
            }

            listener.onState(State.CONNECTED)
            retryAttempt = 0
            while (running && gen == generation) {
                val bytes = stream.readFrame() ?: break
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                latest = bitmap
                listener.onFrame(bitmap)
            }
            stream.close()
            client = null
            if (alive(gen)) {
                listener.onState(State.RETRY)
                retryAttempt++
                listener.onRetryWait(retryAttempt)
                if (!sleepRetry(gen, backoffDelay(retryAttempt))) return
            }
        }
    }

    /**
     * 退避序列 1/3/5/10 秒（PRD FR-08）：前两次快重连抓住“Wi-Fi 抖一下”这类瞬时故障，
     * 之后逐步拉长，避免设备真离线时每 3 秒打一次连接+快照请求把它彻底拖垮。
     */
    private fun backoffDelay(attempt: Int): Long =
        BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /**
     * 用 200ms 分片轮询而不是一次长 sleep：stop() 之后最多 200ms 就能让循环退出，
     * 否则用户切走页面时线程还会带着一条连接多活几秒。
     */
    private fun sleepRetry(gen: Int, delayMs: Long): Boolean {
        var waited = 0L
        while (running && gen == generation && waited < delayMs) {
            Thread.sleep(RETRY_STEP_MS)
            waited += RETRY_STEP_MS
        }
        return alive(gen)
    }

    private fun alive(gen: Int): Boolean = running && gen == generation

    private fun stateOf(failure: MjpegClient.Failure): State = when (failure) {
        MjpegClient.Failure.UNAUTHORIZED -> State.UNAUTHORIZED
        MjpegClient.Failure.BUSY -> State.BUSY
        else -> State.OFFLINE
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000L, 3_000L, 5_000L, 10_000L)
        const val RETRY_STEP_MS = 200L
    }
}
