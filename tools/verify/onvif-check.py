#!/usr/bin/env python3
"""ONVIF Profile S 联调自检（T03972）。

为什么自带一个客户端而不是只写操作文档：Frigate / HA 联调失败时，很难区分是
“设备没开集成”“令牌不对”“SOAP 动作没实现”还是“RTSP 端口不通”。这个脚本把
相同的请求逐项打出来，先排除设备侧问题，再把 NVR 侧变量降到最少。

覆盖：GetSystemDateAndTime、GetDeviceInformation、GetCapabilities、GetProfiles、
GetStreamUri、GetSnapshotUri、CreatePullPointSubscription、PullMessages、Renew、
Unsubscribe，以及 WS-Discovery Probe。

用法：
  python tools/verify/onvif-check.py --host 10.0.2.15 --port 8080 --token qc-xxxx
  python tools/verify/onvif-check.py --host 192.168.1.20 --port 8080 --token qc-xxxx --skip-rtsp

判定：全部 PASS 退出码 0；任一项 FAIL 退出码 1。
"""

import argparse
import base64
import hashlib
import os
import re
import socket
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

SOAP_NS = {
    "soap": "http://www.w3.org/2003/05/soap-envelope",
    "tds": "http://www.onvif.org/ver10/device/wsdl",
    "trt": "http://www.onvif.org/ver10/media/wsdl",
    "tev": "http://www.onvif.org/ver10/events/wsdl",
    "tt": "http://www.onvif.org/ver10/schema",
}


def security_header(username: str, password: str, auth: str) -> str:
    """WS-UsernameToken。Digest 是 HA/NVR 默认；Text 用于把“令牌/开关问题”与“摘要计算问题”分开。"""
    if auth == "text":
        credential = f"<wsse:PasswordText>{password}</wsse:PasswordText>"
    else:
        nonce = os.urandom(16)
        created = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        digest = base64.b64encode(
            hashlib.sha1(nonce + created.encode("utf-8") + password.encode("utf-8")).digest()
        ).decode("ascii")
        credential = (
            '<wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/'
            'oasis-200401-wss-username-token-profile-1.0#PasswordDigest">'
            f"{digest}</wsse:Password>"
            f'<wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/'
            f'oasis-200401-wss-soap-message-security-1.0#Base64Binary">{base64.b64encode(nonce).decode("ascii")}</wsse:Nonce>'
            f"<wsu:Created>{created}</wsu:Created>"
        )
    return f"""
    <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                    xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
      <wsse:UsernameToken>
        <wsse:Username>{username}</wsse:Username>
        {credential}
      </wsse:UsernameToken>
    </wsse:Security>"""


def envelope(body: str, username: str, password: str, auth: str) -> str:
    ns = " ".join(f'xmlns:{key}="{value}"' for key, value in SOAP_NS.items())
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope {ns}>
  <soap:Header>{security_header(username, password, auth)}</soap:Header>
  <soap:Body>{body}</soap:Body>
</soap:Envelope>"""


def post(url: str, xml: str, timeout: float) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        data=xml.encode("utf-8"),
        headers={"Content-Type": "application/soap+xml; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def has_fault(body: str) -> bool:
    return "<soap:Fault" in body or ":Fault>" in body or "ActionNotSupported" in body


def extract(body: str, pattern: str) -> str | None:
    """marker 有两种写法：带捕获组取具体值，不带则只判存在（返回匹配全文）。
    早先一律取 group(1)，遇到 `SubscriptionReference` 这类只作存在性判断的 marker 会直接抛 IndexError。"""
    match = re.search(pattern, body)
    if not match:
        return None
    return match.group(1) if match.re.groups else match.group(0)


def check_rtsp(uri: str) -> tuple[bool, str]:
    match = re.match(r"rtsp://([^:/]+):(\d+)", uri)
    if not match:
        return False, f"无法解析 RTSP 地址：{uri}"
    host, port = match.group(1), int(match.group(2))
    try:
        with socket.create_connection((host, port), timeout=4):
            return True, f"RTSP {host}:{port} 可连接"
    except OSError as error:
        return False, f"RTSP {host}:{port} 不可达：{error}"


def discover(timeout: float) -> tuple[bool, str]:
    probe = f"""<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="{SOAP_NS['soap']}"
               xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
               xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
  <soap:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></soap:Body>
</soap:Envelope>"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.settimeout(timeout)
        sock.sendto(probe.encode("utf-8"), ("239.255.255.250", 3702))
        while True:
            try:
                data, _ = sock.recvfrom(8192)
            except socket.timeout:
                return False, "未收到 ProbeMatch（集成未开或网络禁止多播）"
            text = data.decode("utf-8", "replace")
            if "ProbeMatch" in text:
                address = extract(text, r"<a:XAddrs>([^<]+)")
                return True, f"收到 ProbeMatch：{address or '无地址'}"
    except OSError as error:
        return False, f"多播发送失败：{error}"
    finally:
        sock.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="QuietSentry ONVIF Profile S 自检")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--token", required=True, help="设置页“访问令牌”，即 ONVIF 密码")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--auth", choices=["digest", "text"], default="digest")
    parser.add_argument("--timeout", type=float, default=8)
    parser.add_argument("--skip-discovery", action="store_true")
    parser.add_argument("--skip-rtsp", action="store_true")
    args = parser.parse_args()

    base = f"http://{args.host}:{args.port}/onvif/device_service"
    results: list[tuple[str, bool, str]] = []

    operations = [
        ("GetSystemDateAndTime", "<tds:GetSystemDateAndTime/>", r"<tt:Year>(\d+)"),
        ("GetDeviceInformation", "<tds:GetDeviceInformation/>", r"<tds:Manufacturer>([^<]+)"),
        ("GetCapabilities", "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>",
         r"<tt:XAddr>([^<]+)"),
        ("GetProfiles", "<trt:GetProfiles/>", r'<trt:Profiles token="([^"]+)"'),
        ("GetStreamUri",
         '<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unencrypted</tt:Stream>'
         '<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>'
         "<trt:ProfileToken>main</trt:ProfileToken></trt:GetStreamUri>",
         r"<tt:Uri>([^<]+)</tt:Uri>"),
        ("GetSnapshotUri", "<trt:GetSnapshotUri><trt:ProfileToken>main</trt:ProfileToken></trt:GetSnapshotUri>",
         r"<tt:Uri>([^<]+)</tt:Uri>"),
        ("CreatePullPointSubscription",
         '<tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT10M</tev:InitialTerminationTime>'
         "</tev:CreatePullPointSubscription>", r"<wsa5:Address[^>]*>([^<]+)<"),
        ("PullMessages",
         '<tev:PullMessages><tev:Timeout>PT5S</tev:Timeout><tev:MessageLimit>5</tev:MessageLimit></tev:PullMessages>',
         r"<tev:CurrentTime>([^<]+)"),
        ("Renew", '<tev:Renew><tev:TerminationTime>PT10M</tev:TerminationTime></tev:Renew>',
         r"<tev:TerminationTime>([^<]+)"),
        ("Unsubscribe", "<tev:Unsubscribe/>", None),
    ]

    stream_uri = None
    for name, body, marker in operations:
        xml = envelope(body, args.user, args.token, args.auth)
        try:
            status, response = post(base, xml, args.timeout)
        except OSError as error:
            results.append((name, False, f"请求失败：{error}"))
            continue
        if status == 401:
            results.append((name, False, "401：令牌错误或第三方集成未开启"))
            continue
        if status == 403:
            results.append((name, False, "403：第三方集成未开启"))
            continue
        if status != 200:
            results.append((name, False, f"HTTP {status}"))
            continue
        if has_fault(response):
            fault = extract(response, r"<soap:(?:Text|Value)[^>]*>([^<]+)")
            results.append((name, False, f"SOAP Fault：{fault or '未知'}"))
            continue
        if marker:
            value = extract(response, marker)
            if not value:
                results.append((name, False, f"响应缺少字段 {marker}"))
                continue
            if name in ("GetStreamUri", "GetSnapshotUri") and value.startswith("rtsp://"):
                stream_uri = value
            results.append((name, True, value))
        else:
            results.append((name, True, "已应答"))

    if not args.skip_discovery:
        ok, detail = discover(timeout=3)
        results.append(("WS-Discovery Probe", ok, detail))

    if stream_uri and not args.skip_rtsp:
        ok, detail = check_rtsp(stream_uri)
        results.append(("RTSP 端口", ok, detail))

    print("\n=== ONVIF 自检结果 ===")
    failures = 0
    for name, ok, detail in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {detail}")
        if not ok:
            failures += 1
    print(f"\n合计 {len(results)} 项，失败 {failures} 项")

    if failures == 0:
        print("结论：设备侧 ONVIF 链路可用，可继续在 Frigate / HA 中添加该设备。")
        return 0
    print("结论：先按失败项修复设备侧（集成开关、令牌、端口），再谈 NVR 侧联调。")
    return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
