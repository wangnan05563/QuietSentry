package com.quietsentry.app

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络路径判定（PRD FR-08 连接顺序：局域网直连 → 用户自组网 → 提示不可达；FR-07 异常）。
 *
 * 产品不提供中继、也不持有任何组网密钥，所以这里只做“能不能连上”的证据收集与引导，
 * 绝不尝试建立隧道。可达性用 /api/info 探测而不是 ping：ping 依赖 ICMP，
 * 很多路由器与运营商都会丢 ICMP，而 /api/info 与真正的业务请求走同一条 HTTP 路径。
 */
object NetworkPath {

    enum class Kind { LAN, OVERLAY, UNREACHABLE }

    data class Probe(
        val kind: Kind,
        val host: String,
        val port: Int,
        val deviceName: String,
    )

    /** 本机局域网 IPv4；没有 IPv4 或未连 Wi-Fi 时为 null */
    fun lanAddress(context: Context): String? = PairingQr.localIpv4(context)

    /**
     * 本机是否已在自组网里。Tailscale 分配 100.64.0.0/10 的 CGNAT 段地址，
     * 直接枚举本机网络接口比读第三方 App 的进程状态更可靠：
     * 对方 App 可能被系统冻结，但接口存在与否才是“这条路能不能走”的事实。
     */
    fun overlayAddress(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val nic = interfaces.nextElement()
            if (!nic.isUp || nic.isLoopback) continue
            val addresses = nic.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address) {
                    val host = address.hostAddress ?: continue
                    if (isOverlayAddress(host)) return host
                }
            }
        }
        null
    }.getOrNull()

    fun probe(host: String, port: Int): Probe {
        val info = runCatching { DeviceHttp.info(host, port) }.getOrNull()
        val kind = when {
            info == null -> Kind.UNREACHABLE
            // 只有私网段才算“局域网直连”；100.64/10 与用户自定义的 WireGuard 地址都归自组网
            isPrivateAddress(host) -> Kind.LAN
            else -> Kind.OVERLAY
        }
        return Probe(kind, host, port, info?.name.orEmpty())
    }

    fun isPrivateAddress(host: String): Boolean {
        val octets = octetsOf(host) ?: return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            octets[0] == 127
    }

    /** Tailscale 固定使用 CGNAT 段；主机名（MagicDNS）视为自组网，因为局域网内不会有域名解析 */
    fun isOverlayAddress(host: String): Boolean {
        val octets = octetsOf(host) ?: return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    private fun octetsOf(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = parts.mapNotNull { it.toIntOrNull()?.takeIf { v -> v in 0..255 } }
        return if (values.size == 4) values.toIntArray() else null
    }
}
