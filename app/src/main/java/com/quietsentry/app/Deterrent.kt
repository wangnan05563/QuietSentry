package com.quietsentry.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Calendar

/**
 * 威慑动作（PRD FR-16）：警笛与手电都只持续 10 秒，结束后冷却 30 秒，夜间禁用警笛。
 *
 * 为什么把两者放进同一个 object：它们共享同一套“自动停止 + 冷却 + 与采集运行态绑定”的规则。
 * 分散到 DeviceServer 与 CaptureService 会各自维护一份冷却状态，第二份一定是错的。
 * 这里只持有状态与计时器，真正的开灯动作由 CaptureService 注入，
 * 避免 object 长期持有 Service 引用造成泄漏。
 */
object Deterrent {

    enum class Result { OK, NOT_RUNNING, COOLDOWN, NIGHT, UNAVAILABLE }

    const val DURATION_MS = 10_000L
    const val COOLDOWN_MS = 30_000L

    /** 22:00–07:00 是居民休息时段，警笛默认禁用（PRD FR-16 处理规则），不提供临时覆盖 */
    private const val NIGHT_START_HOUR = 22
    private const val NIGHT_END_HOUR = 7

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    /** 冷却按“动作结束后”起算：10 秒警笛刚停就允许再来一次，等于没有冷却 */
    private var sirenCooldownUntil = 0L
    private var torchCooldownUntil = 0L

    /** 由 CaptureService 绑定相机后注入；服务销毁时必须置空 */
    @Volatile
    var torchSwitch: ((Boolean) -> Boolean)? = null

    private val torchOff = Runnable { torchSwitch?.invoke(false) }

    @Synchronized
    fun siren(): Result {
        // 没有在采集就没有“威慑”的对象，也避免待机时被远程随便放警笛
        if (!CaptureService.isRunning) return Result.NOT_RUNNING
        if (isNight()) return Result.NIGHT
        val now = SystemClock.elapsedRealtime()
        if (now < sirenCooldownUntil) return Result.COOLDOWN
        val generator = tone ?: runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
        }.getOrNull()?.also { tone = it } ?: return Result.UNAVAILABLE
        // TONE_CDMA_EMERGENCY_RINGBACK 是系统内置的紧急回铃音，不需要额外音频资产
        if (!generator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, DURATION_MS.toInt())) {
            return Result.UNAVAILABLE
        }
        sirenCooldownUntil = now + DURATION_MS + COOLDOWN_MS
        return Result.OK
    }

    @Synchronized
    fun torch(): Result {
        if (!CaptureService.isRunning) return Result.NOT_RUNNING
        val now = SystemClock.elapsedRealtime()
        if (now < torchCooldownUntil) return Result.COOLDOWN
        val switch = torchSwitch ?: return Result.UNAVAILABLE
        if (!switch(true)) return Result.UNAVAILABLE
        // 重复触发时先撤掉上一次的关闭任务，否则旧任务会在新灯刚亮起时把它关掉
        handler.removeCallbacks(torchOff)
        handler.postDelayed(torchOff, DURATION_MS)
        torchCooldownUntil = now + DURATION_MS + COOLDOWN_MS
        return Result.OK
    }

    fun sirenCooldownRemainingMs(now: Long = SystemClock.elapsedRealtime()): Long =
        (sirenCooldownUntil - now).coerceAtLeast(0L)

    fun torchCooldownRemainingMs(now: Long = SystemClock.elapsedRealtime()): Long =
        (torchCooldownUntil - now).coerceAtLeast(0L)

    /**
     * 观看端“停止”按钮的远程停止（T04052）：提前停止也按“动作结束”起算冷却，
     * 否则手动停掉后立刻再触发，等于绕过了冷却的防误触设计。
     */
    @Synchronized
    fun stopSiren() {
        // 对未在播放的音调调用 stopTone 是无害的：服务端可能在动作已自然结束后才收到停止请求
        runCatching { tone?.stopTone() }
        sirenCooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
    }

    @Synchronized
    fun stopTorch() {
        handler.removeCallbacks(torchOff)
        torchSwitch?.invoke(false)
        torchCooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
    }

    /** 服务销毁时清理：ToneGenerator 不释放会一直占着音频输出通道 */
    @Synchronized
    fun release() {
        handler.removeCallbacks(torchOff)
        torchSwitch?.invoke(false)
        torchSwitch = null
        runCatching { tone?.release() }
        tone = null
        sirenCooldownUntil = 0L
        torchCooldownUntil = 0L
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // 区间跨午夜：22、23、0…6 都算夜间
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    private const val TONE_VOLUME = 100
}
