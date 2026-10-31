package com.quietsentry.app

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 局域网服务的常驻宿主（PRD FR-02 / FR-15）。
 *
 * 为什么从 CaptureService 搬到这里：配对整个流程都发生在“待机”状态——
 * 用户先在观看端看到设备在线，再扫码换令牌，最后才可能开始值守。
 * 若把服务绑在采集生命周期上，待机时设备在局域网里根本不存在，
 * “在线·待机”这条 PRD 要求的设备状态就无从体现。
 *
 * 代价是监听端口常驻，但端点除 /api/info、/api/pair 外一律要令牌，
 * 且服务只绑定本机网卡、不开公网映射，暴露面与原来相同。
 */
object LanServerHost {

    private const val TAG = "LanServerHost"

    private var server: DeviceServer? = null
    private var advertiser: LanAdvertiser? = null

    /** 幂等启动；返回实际端口，端口段全被占用时返回 null（不影响本地采集与回看） */
    @Synchronized
    fun ensure(context: Context): Int? {
        server?.let { return it.port }
        val app = context.applicationContext
        val pairing = PairingStore(app)
        val instance = DeviceServer(app, EventStore(app), pairing)
        val port = runCatching { instance.start() }.getOrElse {
            Log.w(TAG, "lan server unavailable", it)
            return null
        }
        // mDNS 失败不算致命：用户仍可用二维码里的 IP 手输，只是失去自动发现
        runCatching {
            LanAdvertiser(app).also {
                it.advertise(port, pairing.fingerprint(), Build.MODEL)
                advertiser = it
            }
        }.onFailure { Log.w(TAG, "mdns advertise failed", it) }
        server = instance
        return port
    }

    @Synchronized
    fun port(): Int = server?.port ?: 0

    /** 目前只有测试或进程级重置需要用；正常使用中服务不随值守停止 */
    @Synchronized
    fun stop() {
        advertiser?.stop()
        advertiser = null
        server?.stop()
        server = null
    }
}
