package com.quietsentry.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * ONVIF Profile S 的最小实现（PRD FR-15：Frigate / Home Assistant 发现设备并拉流）。
 *
 * 只覆盖"发现 + 取流地址 + 取快照地址"这条 NVR 必需链路：
 * 设备管理里的重启、网络配置、用户管理都涉及会中断值守的操作，不属于本任务范围。
 *
 * WS-Discovery 仅在集成开关打开后才回复：它是不带认证的公开探测，
 * 无条件应答等于向整个局域网持续广播"这里有一台摄像头"。
 */
object OnvifService {

    private const val TAG = "OnvifService"
    private const val DISCOVERY_PORT = 3702
    private const val MULTICAST_ADDRESS = "239.255.255.250"

    private var appContext: Context? = null
    private var discoveryThread: Thread? = null
    private var discoverySocket: MulticastSocket? = null

    // 单订阅即可：设备是单用户场景，多客户端同时订阅没有产品需求
    private var subId: String? = null
    private var subExpiresAt = 0L
    private var lastPullAt = 0L
    private const val SUB_TTL_MS = 10 * 60 * 1000L

    private fun isoNow(offsetMs: Long = 0L): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(System.currentTimeMillis() + offsetMs))
    }

    @Synchronized
    fun ensureDiscovery(context: Context, httpPortProvider: () -> Int) {
        appContext = context.applicationContext
        if (discoveryThread != null) return
        val socket = runCatching { MulticastSocket(DISCOVERY_PORT) }.getOrNull() ?: run {
            Log.w(TAG, "ws-discovery unavailable")
            return
        }
        runCatching {
            socket.reuseAddress = true
            socket.joinGroup(InetAddress.getByName(MULTICAST_ADDRESS))
        }.onFailure { Log.w(TAG, "join multicast failed", it) }
        discoverySocket = socket
        discoveryThread = Thread({ discoveryLoop(socket, httpPortProvider) }, "onvif-discovery").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "ws-discovery listening on $DISCOVERY_PORT")
    }

    @Synchronized
    fun stopDiscovery() {
        discoveryThread = null
        runCatching { discoverySocket?.close() }
        discoverySocket = null
    }

    private fun discoveryLoop(socket: MulticastSocket, httpPortProvider: () -> Int) {
        val buffer = ByteArray(4096)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                break
            }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            // ProbeMatches 里同样含 "Probe" 字样，先排除避免设备互相应答形成广播风暴
            if (!text.contains("Probe") || text.contains("ProbeMatch")) continue
            if (appContext?.let { LanIntegration.isEnabled(it) } != true) continue
            val httpPort = httpPortProvider()
            if (httpPort <= 0) continue
            val reply = probeMatch(ip(), httpPort).toByteArray(Charsets.UTF_8)
            val response = DatagramPacket(reply, reply.size, packet.address, packet.port)
            runCatching { socket.send(response) }
        }
    }

    private fun ip(): String = lanIp()

    private fun endpointUuid(): String {
        val seed = "${Build.MODEL}-${Build.FINGERPRINT}-${ip()}"
        val hash = seed.hashCode().toLong() and 0xFFFFFFFFL
        return String.format("urn:uuid:%08x-0000-1000-8000-000000000000", hash)
    }

    private fun probeMatch(ip: String, httpPort: Int): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"
                      xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                      xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing">
          <env:Header>
            <a:MessageID>urn:uuid:${System.nanoTime()}</a:MessageID>
            <a:RelatesTo>urn:uuid:probe</a:RelatesTo>
            <a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>
          </env:Header>
          <env:Body>
            <d:ProbeMatches>
              <d:ProbeMatch>
                <a:EndpointReference>
                  <a:Address>${endpointUuid()}</a:Address>
                </a:EndpointReference>
                <d:Types>dn:NetworkVideoTransmitter</d:Types>
                <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/Profile/Streaming</d:Scopes>
                <d:XAddrs>http://$ip:$httpPort/onvif/device_service</d:XAddrs>
                <d:MetadataVersion>1</d:MetadataVersion>
              </d:ProbeMatch>
            </d:ProbeMatches>
          </env:Body>
        </env:Envelope>
    """.trimIndent()

    /**
     * 处理 SOAP 请求。host 取自 HTTP Host 头，保证返回的地址是客户端实际连得到的地址
     * （设备可能有多张网卡，写死 Wi-Fi IP 会让有线连接的客户端拉不到流）。
     */
    fun handle(
        body: String,
        host: String,
        httpPort: Int,
        rtspPort: Int,
        recentEvents: List<Event> = emptyList(),
    ): String {
        val operation = operationOf(body)
        if (operation == null) return fault("ActionNotSupported", "无法识别的请求")
        val deviceUrl = "http://$host:$httpPort/onvif/device_service"
        val mediaUrl = "http://$host:$httpPort/onvif/media_service"
        val eventsUrl = "http://$host:$httpPort/onvif/events_service"
        return when (operation) {
            "GetSystemDateAndTime" -> envelope(
                """
                <tds:GetSystemDateAndTimeResponse>
                  <tds:SystemDateAndTime>
                    <tt:DateTimeType>Manual</tt:DateTimeType>
                    <tt:DaylightSavings>false</tt:DaylightSavings>
                    <tt:UTCDateTime>
                      <tt:Time><tt:Hour>${utcHour()}</tt:Hour><tt:Minute>${utcMinute()}</tt:Minute><tt:Second>${utcSecond()}</tt:Second></tt:Time>
                      <tt:Date><tt:Year>${utcYear()}</tt:Year><tt:Month>${utcMonth()}</tt:Month><tt:Day>${utcDay()}</tt:Day></tt:Date>
                    </tt:UTCDateTime>
                  </tds:SystemDateAndTime>
                </tds:GetSystemDateAndTimeResponse>
                """
            )

            "GetDeviceInformation" -> envelope(
                """
                <tds:GetDeviceInformationResponse>
                  <tds:Manufacturer>QuietSentry</tds:Manufacturer>
                  <tds:Model>${Build.MODEL}</tds:Model>
                  <tds:FirmwareVersion>1.0</tds:FirmwareVersion>
                  <tds:SerialNumber>${Build.MODEL.replace(' ', '-')}-${Build.FINGERPRINT.hashCode()}</tds:SerialNumber>
                  <tds:HardwareId>Android</tds:HardwareId>
                </tds:GetDeviceInformationResponse>
                """
            )

            "GetCapabilities" -> envelope(
                """
                <tds:GetCapabilitiesResponse>
                  <tds:Capabilities>
                    <tt:Device>
                      <tt:XAddr>$deviceUrl</tt:XAddr>
                      <tt:Network><tt:IPFilter>false</tt:IPFilter></tt:Network>
                    </tt:Device>
                    <tt:Media>
                      <tt:XAddr>$mediaUrl</tt:XAddr>
                      <tt:StreamingCapabilities>
                        <tt:RTP_TCP>true</tt:RTP_TCP>
                        <tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP>
                      </tt:StreamingCapabilities>
                    </tt:Media>
                    <tt:Events><tt:XAddr>$eventsUrl</tt:XAddr></tt:Events>
                  </tds:Capabilities>
                </tds:GetCapabilitiesResponse>
                """
            )

            "GetServices" -> envelope(
                """
                <tds:GetServicesResponse>
                  <tds:Service>
                    <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
                    <tds:XAddr>$deviceUrl</tds:XAddr>
                  </tds:Service>
                  <tds:Service>
                    <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
                    <tds:XAddr>$mediaUrl</tds:XAddr>
                  </tds:Service>
                  <tds:Service>
                    <tds:Namespace>http://www.onvif.org/ver10/events/wsdl</tds:Namespace>
                    <tds:XAddr>$eventsUrl</tds:XAddr>
                  </tds:Service>
                </tds:GetServicesResponse>
                """
            )

            "GetVideoSources" -> envelope(
                """
                <trt:GetVideoSourcesResponse>
                  <trt:VideoSources token="vsc0">
                    <tt:Framerate>12</tt:Framerate>
                    <tt:Resolution><tt:Width>${sizeOf(true)}</tt:Width><tt:Height>${sizeOf(false)}</tt:Height></tt:Resolution>
                  </trt:VideoSources>
                </trt:GetVideoSourcesResponse>
                """
            )

            "GetProfiles" -> envelope(
                """
                <trt:GetProfilesResponse>
                  <trt:Profiles token="main" fixed="true">
                    <tt:Name>Main</tt:Name>
                    <tt:VideoSourceConfiguration token="vsc0"><tt:Name>Camera</tt:Name><tt:SourceToken>vsc0</tt:SourceToken></tt:VideoSourceConfiguration>
                    <tt:VideoEncoderConfiguration token="vec_main">
                      <tt:Name>Main</tt:Name>
                      <tt:Encoding>H264</tt:Encoding>
                      <tt:Resolution><tt:Width>${sizeOf(true)}</tt:Width><tt:Height>${sizeOf(false)}</tt:Height></tt:Resolution>
                      <tt:RateControl><tt:FrameRateLimit>12</tt:FrameRateLimit><tt:BitrateLimit>900</tt:BitrateLimit></tt:RateControl>
                    </tt:VideoEncoderConfiguration>
                  </trt:Profiles>
                  <trt:Profiles token="sub" fixed="true">
                    <tt:Name>Sub</tt:Name>
                    <tt:VideoSourceConfiguration token="vsc0"><tt:Name>Camera</tt:Name><tt:SourceToken>vsc0</tt:SourceToken></tt:VideoSourceConfiguration>
                    <tt:VideoEncoderConfiguration token="vec_sub">
                      <tt:Name>Sub</tt:Name>
                      <tt:Encoding>H264</tt:Encoding>
                      <tt:Resolution><tt:Width>${subSizeOf(true)}</tt:Width><tt:Height>${subSizeOf(false)}</tt:Height></tt:Resolution>
                      <tt:RateControl><tt:FrameRateLimit>12</tt:FrameRateLimit><tt:BitrateLimit>250</tt:BitrateLimit></tt:RateControl>
                    </tt:VideoEncoderConfiguration>
                  </trt:Profiles>
                </trt:GetProfilesResponse>
                """
            )

            "GetStreamUri" -> {
                val token = profileTokenOf(body)
                val path = if (token == "sub") "sub" else "main"
                envelope(
                    """
                    <trt:GetStreamUriResponse>
                      <trt:MediaUri>
                        <tt:Uri>rtsp://$host:$rtspPort/stream/$path</tt:Uri>
                        <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                        <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
                        <tt:Timeout>PT0S</tt:Timeout>
                      </trt:MediaUri>
                    </trt:GetStreamUriResponse>
                    """
                )
            }

            "GetSnapshotUri" -> envelope(
                """
                <trt:GetSnapshotUriResponse>
                  <trt:MediaUri>
                    <tt:Uri>http://$host:$httpPort/api/snapshot</tt:Uri>
                    <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                    <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
                    <tt:Timeout>PT0S</tt:Timeout>
                  </trt:MediaUri>
                </trt:GetSnapshotUriResponse>
                """
            )

            "GetEventProperties" -> envelope(
                """
                <tev:GetEventPropertiesResponse>
                  <tev:TopicSet>
                    <tt:Topic ns="http://www.onvif.org/ver10/topics">tns1:RuleEngine/CellMotionDetector/Motion</tt:Topic>
                  </tev:TopicSet>
                  <tev:TopicExpressionDialect>http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet</tev:TopicExpressionDialect>
                  <tev:MessageContentFilterDialect>http://www.onvif.org/ver10/tev/messageContentFilter/ItemFilter</tev:MessageContentFilterDialect>
                  <tev:MessageContentSchemaLocation>http://www.onvif.org/onvif/ver10/schema/onvif.xsd</tev:MessageContentSchemaLocation>
                </tev:GetEventPropertiesResponse>
                """
            )

            // PullPoint：订阅创建后由客户端轮询 PullMessages，服务端把运动事件转成 ONVIF 通知。
            // 单订阅足够覆盖单机位设备；多客户端场景属于 P2 多机位规划。
            "CreatePullPointSubscription" -> {
                subId = java.util.UUID.randomUUID().toString()
                subExpiresAt = System.currentTimeMillis() + SUB_TTL_MS
                lastPullAt = System.currentTimeMillis()
                envelope(
                    """
                    <tev:CreatePullPointSubscriptionResponse>
                      <tev:SubscriptionReference>
                        <wsa5:Address xmlns:wsa5="http://www.w3.org/2005/08/addressing">http://$host:$httpPort/onvif/sub</wsa5:Address>
                      </tev:SubscriptionReference>
                      <tev:CurrentTime>${isoNow()}</tev:CurrentTime>
                      <tev:TerminationTime>${isoNow(SUB_TTL_MS)}</tev:TerminationTime>
                    </tev:CreatePullPointSubscriptionResponse>
                    """
                )
            }

            "PullMessages" -> {
                if (subId == null || System.currentTimeMillis() > subExpiresAt) {
                    return fault("NotAuthorized", "订阅不存在或已过期")
                }
                val fresh = recentEvents
                    .filter { it.type == EventType.MOTION && it.occurredAt > lastPullAt }
                    .takeLast(5)
                lastPullAt = System.currentTimeMillis()
                val messages = fresh.joinToString("") {
                    """
                    <tev:NotificationMessage>
                      <tev:Topic>tns1:RuleEngine/CellMotionDetector/Motion</tev:Topic>
                      <tev:Message>
                        <tt:Source><tt:SimpleItem Name="VideoSourceConfigurationToken" Value="main"/></tt:Source>
                        <tt:Data><tt:SimpleItem Name="IsMotion" Value="true"/></tt:Data>
                      </tt:Message>
                    </tev:NotificationMessage>
                    """
                }
                envelope(
                    """
                    <tev:PullMessagesResponse>
                      <tev:CurrentTime>${isoNow()}</tev:CurrentTime>
                      <tev:TerminationTime>${isoNow((subExpiresAt - System.currentTimeMillis()).coerceAtLeast(0L))}</tev:TerminationTime>
                      $messages
                    </tev:PullMessagesResponse>
                    """
                )
            }

            "Renew" -> {
                if (subId == null) return fault("NotAuthorized", "订阅不存在")
                subExpiresAt = System.currentTimeMillis() + SUB_TTL_MS
                envelope(
                    "<tev:RenewResponse><tev:TerminationTime>${isoNow(SUB_TTL_MS)}</tev:TerminationTime></tev:RenewResponse>"
                )
            }

            "Unsubscribe" -> {
                subId = null
                subExpiresAt = 0L
                envelope("<tev:UnsubscribeResponse/>")
            }

            else -> fault("ActionNotSupported", "事件订阅未实现：$operation")
        }
    }

    private fun sizeOf(width: Boolean): Int {
        val size = H264Streamer.sizeOf(H264Streamer.Stream.MAIN)
        val value = if (width) size.first else size.second
        return if (value > 0) value else if (width) 640 else 480
    }

    private fun subSizeOf(width: Boolean): Int {
        val size = H264Streamer.sizeOf(H264Streamer.Stream.SUB)
        val value = if (width) size.first else size.second
        return if (value > 0) value else if (width) 320 else 240
    }

    private fun operationOf(body: String): String? {
        val bodyIndex = body.indexOf("Body")
        if (bodyIndex < 0) return null
        val names = Regex("<(?:[A-Za-z0-9]+:)?([A-Za-z][A-Za-z0-9]*)[ >]").findAll(body.substring(bodyIndex))
        names.forEach { match ->
            val name = match.groupValues[1]
            if (name != "Body" && name != "Header" && name != "Envelope") return name
        }
        return null
    }

    private fun profileTokenOf(body: String): String =
        Regex("<(?:[A-Za-z0-9]+:)?ProfileToken>([^<]+)<").find(body)?.groupValues?.get(1) ?: "main"

    private fun envelope(payload: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                    xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                    xmlns:tev="http://www.onvif.org/ver10/events/wsdl"
                    xmlns:tt="http://www.onvif.org/ver10/schema">
          <s:Body>
            ${payload.trim()}
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun fault(reason: String, detail: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
          <s:Body>
            <s:Fault>
              <s:Code><s:Value>s:Sender</s:Value></s:Code>
              <s:Reason><s:Text xml:lang="zh">$reason</s:Text></s:Reason>
              <s:Detail>$detail</s:Detail>
            </s:Fault>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun utcYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    private fun utcMonth(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
    private fun utcDay(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
    private fun utcHour(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    private fun utcMinute(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
    private fun utcSecond(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.SECOND)
}
