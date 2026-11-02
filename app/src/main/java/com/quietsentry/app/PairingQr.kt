package com.quietsentry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address

/**
 * 配对链接与二维码生成。
 *
 * 为什么把 host / port / code / 指纹全部塞进链接：观看端没有键盘输入流程，
 * 扫码后必须一次拿到“连哪台设备、哪个端口、用什么码换令牌、对方指纹是什么”。
 * 少任何一项，配对就得让用户照着屏幕手输，体验直接退化成看说明书。
 *
 * 链接用自定义 scheme 而不是 https：这是局域网内的一次性握手凭据，
 * 不需要也不可能走公网；scheme 只作为内容约定，由观看端解析。
 */
object PairingQr {

    const val SCHEME_PAIR = "quietsentry://pair"
    const val SCHEME_JOIN = "quietsentry://join"

    fun pairLink(host: String, port: Int, code: String, fingerprint: String): String =
        "$SCHEME_PAIR?host=$host&port=$port&code=$code&fp=$fingerprint"

    fun joinLink(host: String, port: Int, invite: String, fingerprint: String): String =
        "$SCHEME_JOIN?host=$host&port=$port&invite=$invite&fp=$fingerprint"

    /** 扫码得到的配对要素：与 pairLink 的四项一一对应，缺一不可配对 */
    data class PairPayload(val host: String, val port: Int, val code: String, val fingerprint: String)

    /**
     * 解析扫码结果。用户可能扫到任意二维码（网页、WiFi 码），
     * 只有 scheme 与四项参数齐全才接受，避免把无关内容当成配对凭据发出去。
     */
    fun parsePairLink(link: String): PairPayload? {
        if (!link.startsWith(SCHEME_PAIR)) return null
        val uri = android.net.Uri.parse(link)
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val port = uri.getQueryParameter("port")?.trim()?.toIntOrNull() ?: return null
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        val fingerprint = uri.getQueryParameter("fp")?.trim().orEmpty()
        if (host.isEmpty() || port !in 1..65535 || code.isEmpty() || fingerprint.isEmpty()) return null
        return PairPayload(host, port, code, fingerprint)
    }

    fun encode(text: String, sizePx: Int): Bitmap? = runCatching {
        // MARGIN=1 让二维码四周留一个模块的静区，否则部分扫码器识别率明显下降
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
        }
    }.getOrNull()

    /**
     * 当前局域网 IPv4。
     * 二维码里的 host 必须是观看端能直连的地址；用 127.0.0.1 生成的码在另一台设备上是废的，
     * 所以这里按活动网络的实际链路地址取，而不是读 WifiManager 的缓存字段。
     */
    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
