package com.quietsentry.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * mDNS 广播与发现（PRD FR-15：支持 mDNS 发现）。
 *
 * 用系统 NsdManager 而不是自带 Bonjour 库：不引入第三方依赖，也不需要额外权限，
 * 且摄像头端与观看端用的是同一套系统实现，服务名、端口与 TXT 记录的解析不会出现两端不一致。
 */
object LanDiscoverySpec {
    const val SERVICE_TYPE = "_quietsentry._tcp"

    /** TXT 记录里的公钥指纹，用于观看端把“发现到的设备”与扫码时核对的指纹对上 */
    const val ATTR_FINGERPRINT = "fp"
    const val ATTR_NAME = "name"
}

/**
 * 摄像头端的服务广播。注册失败不阻断 HTTP 服务：部分 ROM 的 mDNS 栈不可用，
 * 用户仍可通过二维码里的 IP:端口 手动连接。
 */
class LanAdvertiser(private val context: Context) {

    private var manager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun advertise(port: Int, fingerprint: String, deviceName: String) {
        stop()
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            // 服务名带上指纹前缀保证同一局域网内多台设备不重名；
            // 用户可见的设备名放 TXT，避免服务名里出现空格与过长字符串被系统截断
            serviceName = "QuietSentry-${fingerprint.take(6).replace(":", "")}"
            serviceType = LanDiscoverySpec.SERVICE_TYPE
            setPort(port)
            // API 34 起才有 String 版 setAttribute；旧版 byte[] 版在 compileSdk 35 的 stub 中已不可见。
            // 低版本设备因此不发 TXT 记录，观看端仍可通过 /api/info 读取指纹，自动发现不受影响
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setAttribute(LanDiscoverySpec.ATTR_FINGERPRINT, fingerprint)
                setAttribute(LanDiscoverySpec.ATTR_NAME, deviceName)
            }
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mdns registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // 广播失败只影响“自动发现”，不影响二维码/手输 IP，因此不向用户报错
                Log.w(TAG, "mdns registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mdns unregister failed: $errorCode")
            }
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onSuccess {
                manager = nsd
                listener = registration
            }
            .onFailure { Log.w(TAG, "mdns register threw", it) }
    }

    fun stop() {
        val nsd = manager ?: return
        val registration = listener ?: return
        runCatching { nsd.unregisterService(registration) }
        manager = null
        listener = null
    }

    private companion object {
        const val TAG = "QuietSentry"
    }
}

/**
 * 观看端的局域网发现（PRD FR-02：同一局域网内观看端可自动发现处于配对状态的设备）。
 *
 * NsdManager 同一时刻只允许一个 resolveService，因此用队列串行解析：
 * 同时发现多台设备时如果并发 resolve，后来的请求会以 FAILURE_ALREADY_ACTIVE 直接失败，
 * 表现为“列表里只有第一台设备”。
 */
class LanDiscovery(private val context: Context) {

    data class Peer(
        val serviceName: String,
        val name: String,
        val host: String,
        val port: Int,
        val fingerprint: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val known = HashMap<String, Peer>()

    private var manager: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var onFound: ((Peer) -> Unit)? = null
    private var onLost: ((Peer) -> Unit)? = null
    private var resolving = false
    private var running = false

    fun start(onFound: (Peer) -> Unit, onLost: (Peer) -> Unit) {
        if (running) return
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        manager = nsd
        this.onFound = onFound
        this.onLost = onLost
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // 系统已经按发现时传入的类型过滤，这里不再二次比较字符串：
                // 部分 ROM 会在回调里返回带大小写差异的类型，二次判断反而会漏掉设备
                enqueue(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val peer = known.remove(info.serviceName) ?: return
                runCatching { this@LanDiscovery.onLost?.invoke(peer) }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery stop failed: $errorCode")
            }
        }
        discovery = listener
        running = true
        runCatching { nsd.discoverServices(LanDiscoverySpec.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.w(TAG, "discovery threw", it)
                stop()
            }
    }

    fun stop() {
        running = false
        val nsd = manager
        val listener = discovery
        manager = null
        discovery = null
        onFound = null
        onLost = null
        synchronized(queue) {
            queue.clear()
            resolving = false
        }
        known.clear()
        if (nsd != null && listener != null) {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(queue) { queue.addLast(info) }
        resolveNext()
    }

    private fun resolveNext() {
        if (!running) return
        val nsd = manager ?: return
        val info: NsdServiceInfo
        synchronized(queue) {
            if (resolving || queue.isEmpty()) return
            info = queue.removeFirst()
            resolving = true
        }
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    // 解析失败通常是对端刚下线或系统栈忙；跳过这一条，稍后再试下一条
                    Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    finishResolve(300L)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    // 广播里的 host 在本机看来可能是 IPv6 或不可路由的地址，
                    // 这里保留系统解析结果，让连接层先直连、失败后提示手输 IP
                    val host = serviceInfo.host?.hostAddress
                    val fingerprint = serviceInfo.attributes
                        ?.get(LanDiscoverySpec.ATTR_FINGERPRINT)
                        ?.toString(Charsets.UTF_8)
                        .orEmpty()
                    if (host != null && serviceInfo.port > 0) {
                        val peer = Peer(
                            serviceName = serviceInfo.serviceName,
                            name = serviceInfo.attributes
                                ?.get(LanDiscoverySpec.ATTR_NAME)
                                ?.toString(Charsets.UTF_8)
                                .orEmpty()
                                .ifEmpty { serviceInfo.serviceName },
                            host = host,
                            port = serviceInfo.port,
                            fingerprint = fingerprint,
                        )
                        known[peer.serviceName] = peer
                        runCatching { onFound?.invoke(peer) }
                    }
                    finishResolve(0L)
                }
            })
        }.onFailure {
            Log.w(TAG, "resolve threw", it)
            finishResolve(300L)
        }
    }

    private fun finishResolve(delayMs: Long) {
        synchronized(queue) { resolving = false }
        // 统一在解析结束后弹到主线程继续下一条：NsdManager 的回调线程可能不是主线程，
        // 队列与 resolving 标记因此始终在同一线程推进
        handler.postDelayed({ resolveNext() }, delayMs)
    }

    private companion object {
        const val TAG = "QuietSentry"
    }
}
